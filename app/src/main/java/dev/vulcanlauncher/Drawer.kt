package dev.vulcanlauncher

import android.content.Intent
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.SectionIndexer
import kotlin.math.abs

/**
 * Lays out the drawer tabs. Scroll: one row, as Supernova's and Nova's tab bars: past the drawer's width it scrolls
 * sideways (inside a HorizontalScrollView), and the selected tab is brought to the middle when it changes.
 * [wrap]: M3 chips in reading order, wrapping at the drawer's width (inside a capped vertical ScrollView).
 */
internal class DrawerTabs(context: android.content.Context, private val gap: Int, private val chipHeight: Int, private val wrap: Boolean) : ViewGroup(context) {
    private val positions = mutableListOf<android.graphics.Rect>()
    var chipToReveal: View? = null

    override fun onMeasure(widthSpec: Int, heightSpec: Int) = if (wrap) measureWrapped(widthSpec, heightSpec) else measureRow(widthSpec, heightSpec)

    private fun measureWrapped(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val rowWidth = (width - paddingStart - paddingEnd).coerceAtLeast(0)
        val childWidth = MeasureSpec.makeMeasureSpec(rowWidth, MeasureSpec.AT_MOST)
        val childHeight = MeasureSpec.makeMeasureSpec(chipHeight, MeasureSpec.EXACTLY)
        var x = 0
        var y = paddingTop
        positions.clear()
        for (i in 0 until childCount) {
            val chip = getChildAt(i)
            chip.measure(childWidth, childHeight)
            if (x > 0 && x + chip.measuredWidth > rowWidth) { x = 0; y += chipHeight + gap }
            val start = paddingStart + x
            val left = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - start - chip.measuredWidth else start
            positions.add(android.graphics.Rect(left, y, left + chip.measuredWidth, y + chipHeight))
            x += chip.measuredWidth + gap
        }
        setMeasuredDimension(width, resolveSize(y + (if (childCount == 0) 0 else chipHeight) + paddingBottom, heightSpec))
    }

    private fun measureRow(widthSpec: Int, heightSpec: Int) {
        val childWidth = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val childHeight = MeasureSpec.makeMeasureSpec(chipHeight, MeasureSpec.EXACTLY)
        var row = paddingStart
        for (i in 0 until childCount) {
            getChildAt(i).measure(childWidth, childHeight)
            row += getChildAt(i).measuredWidth + if (i < childCount - 1) gap else 0
        }
        row += paddingEnd
        // At least the visible width (the scroll view's fillViewport), so a short row still starts at the edge.
        val width = maxOf(row, MeasureSpec.getSize(widthSpec))
        var x = paddingStart
        positions.clear()
        for (i in 0 until childCount) {
            val chip = getChildAt(i)
            val left = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - x - chip.measuredWidth else x
            positions.add(android.graphics.Rect(left, paddingTop, left + chip.measuredWidth, paddingTop + chipHeight))
            x += chip.measuredWidth + gap
        }
        setMeasuredDimension(width, resolveSize(paddingTop + (if (childCount == 0) 0 else chipHeight) + paddingBottom, heightSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        positions.forEachIndexed { i, bounds -> getChildAt(i).layout(bounds.left, bounds.top, bounds.right, bounds.bottom) }
        chipToReveal?.let { chip ->
            chipToReveal = null
            if (wrap) { chip.post { chip.requestRectangleOnScreen(android.graphics.Rect(0, 0, chip.width, chip.height), true) }; return@let }
            // Centred, as a scrollable TabLayout keeps its selected tab; the scroll view clamps it at the ends.
            val scroll = parent as? android.widget.HorizontalScrollView ?: return@let
            scroll.post { scroll.smoothScrollTo(chip.left + chip.width / 2 - scroll.width / 2, 0) }
        }
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
}

/**
 * Whether a released drawer pull ends open: flung up faster than [fling] (px/s, upward positive), or past [threshold]
 * unless flung down. A pull up commits at 30% (Launcher3: 40%), a pull down closes below 70%.
 */
fun drawerOpens(progress: Float, vy: Float, fling: Float, threshold: Float = 0.5f) = vy > fling || vy > -fling && progress > threshold

/**
 * Launcher3's swipe-release duration (BaseSwipeDetector.calculateDuration), on an 800 ms base instead of its 1200 for a
 * quicker finish: [v] release speed in px/ms, [remaining] progress left.
 */
fun swipeDuration(v: Float, remaining: Float) = maxOf(80L, (800f / maxOf(2f, abs(0.5f * v)) * maxOf(0.2f, remaining)).toLong())

/** Launcher3's release curve (scrollInterpolatorForVelocity): quintic ease-out after a fast fling, else cubic. */
private val SCROLL_CUBIC = android.view.animation.Interpolator { t -> val u = t - 1; u * u * u + 1 }

/** [from]..[to] of [p] as 0..1, clamped (Launcher3's clampToProgress over a linear curve). */
private fun span(p: Float, from: Float, to: Float) = ((p - from) / (to - from)).coerceIn(0f, 1f)

/**
 * The tab a sideways swipe lands on: the one after [current] in [keys] if [forward], else before, wrapping round at
 * either end when [wrap]; null with fewer than two, or at an end without [wrap].
 */
fun swipedTab(keys: List<String>, current: String, forward: Boolean, wrap: Boolean = true): String? {
    if (keys.size < 2) return null
    val i = keys.indexOf(current).coerceAtLeast(0) + if (forward) 1 else -1
    return if (wrap) keys[i.mod(keys.size)] else keys.getOrNull(i)
}

/** Tag on a drawer view that shows an app, so GridView can hand it back for another app. */
private const val APP_VIEW = "app"

/** Material "emphasized" easing: quick start, long soft landing. */
val EASE = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)

/** The app drawer overlay. Built once per activity; [refresh] rebuilds its contents. */
class Drawer(private val a: MainActivity) {
    internal sealed class Entry {
        class AppE(val app: App) : Entry()
        class FolderE(val group: Group) : Entry()
        class WebE(val query: String) : Entry()
        class ContactE(val name: String, val photo: String?, val uri: android.net.Uri) : Entry()
        /** A search result that does one thing when tapped: an app shortcut, a settings page, a sum, a timer. */
        class ActionE(val label: String, val icon: android.graphics.drawable.Drawable, val run: (View?) -> Unit) : Entry()
    }

    private val styleName = a.looks.str("drawerStyle")
    private val screen = a.resources.displayMetrics
    private val long = maxOf(screen.widthPixels, screen.heightPixels)
    private val short = minOf(screen.widthPixels, screen.heightPixels)
    // Columns and rows are set for portrait; landscape gets more columns and fewer rows of the same-size cells.
    private val gridCols = scaleSpan(a.looks.int("drawerCols"), short, screen.widthPixels)
    private val cols = if (styleName == "list") 1 else gridCols
    private val iconStyle = a.style("drawer").let {
        // Glass: quieter labels (smaller, slightly see-through) so the icons carry the screen.
        val quiet = styleName == "glass"
        IconStyle(it.sizePx, it.labels || styleName == "list", if (quiet) it.labelSize - 1 else it.labelSize,
            if (quiet) a.panelText and 0xFFFFFF or 0xD9000000.toInt() else a.panelText, false, it.iconShadow,
            horizontal = styleName == "list", font = it.font, lines = it.lines)
    }
    /** The frequent row is icons over labels in every style, list included. */
    private val rowStyle = iconStyle.run { IconStyle(sizePx, labels, labelSize, labelColor, shadow, iconShadow, font = font, lines = lines) }
    private var entries = listOf<Entry>()
    private var tab = ""
    private var showHidden = false
    private val found = DrawerSearch(a)
    private val adapter = Adapter({ entries }, { grid })
    /** The neighbouring tab's apps, shown beside the list while a sideways swipe moves it. */
    private var peekEntries = listOf<Entry>()
    private val peekAdapter = Adapter({ peekEntries }, { peek })
    /** [peekEntries]' tab key, or null while no swipe shows one. */
    private var peekTab: String? = null
    /** The apps of any tab, as [refresh] last worked them out; a swipe asks it for the neighbouring tab's. */
    private var entriesOf: (String) -> List<Entry> = { emptyList() }
    /** The tab the drawer opens on: All apps, or the first tab when All apps is hidden. */
    private var homeTab = ""
    /** Tab bar "wrap": square M3 chips over as many rows as they need. Housing keeps its raised keys either way. */
    private val wrapTabs = a.looks.str("tabBar") == "wrap"
    private val tabs = DrawerTabs(a, (8 * a.dp).toInt(), (32 * a.dp).toInt(), wrapTabs)
    private val tabScroll: FrameLayout = if (wrapTabs) object : android.widget.ScrollView(a) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            // Keep the grid usable even with dozens of tabs or in a short window.
            val limit = minOf((128 * a.dp).toInt(), View.MeasureSpec.getSize(heightSpec) / 3)
            super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
        }
    }.apply { isVerticalScrollBarEnabled = false; addView(tabs, FrameLayout.LayoutParams(-1, -2)) }
    else android.widget.HorizontalScrollView(a).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        addView(tabs, FrameLayout.LayoutParams(-2, -2))
    }
    private val grid = GridView(a).apply {
        // No scrollbar (the letter index scrolls); rows fade out at the edges instead of being cut by the tabs, status bar or search bar.
        isVerticalScrollBarEnabled = false; isVerticalFadingEdgeEnabled = true; setFadingEdgeLength((32 * a.dp).toInt())
    }
    private val peek = GridView(a).apply {
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isVerticalScrollBarEnabled = false; isVerticalFadingEdgeEnabled = true; setFadingEdgeLength((32 * a.dp).toInt())
    }
    /** Shown when a search matches no app or contact. */
    private val empty = a.text("", 15f, a.m3.onSurfaceVariant).apply { gravity = Gravity.CENTER; visibility = View.GONE }
    /** Glass style: a tint over the blurred home screen and a frosted search bar with a hairline edge. */
    private val glass = styleName == "glass"
    /** Appliance housing: an opaque warm-grey body, a recessed search slot at the bottom and a letter index. */
    private val housing = styleName == "housing"
    /**
     * Our own letter index in a strip the grid leaves free. Android's fast scroller took taps on a 48 dp strip over the
     * last column, visible or not, so the right edge of those icons did nothing (#47). Horizontal pages have none.
     */
    private val letterIndex = a.looks.on("fastScroll") && styleName != "pages"
    private val searchPlace = a.looks.str("drawerSearch")
    /** Search bar under the thumb, results stacked above it: glass and housing put it there unless the user chose. */
    private val bottomBar = if (searchPlace == "style") glass || housing else searchPlace == "bottom"
    /** As Nova's "None": no search bar, except while searching (pull to search, show keyboard). */
    private val barOff = searchPlace == "off"
    /** One-handed: apps fill from the bottom up and the tabs sit under them, near the thumb. Not for horizontal pages. */
    private val fromBottom = a.looks.on("fillFromBottom") && styleName != "pages"
    /** As Nova: the grid's height holds this many rows; the icons shrink to fit if they must. */
    private val rows = scaleSpan(a.looks.int("drawerRows"), long, screen.heightPixels)
    /** A grid row's height, measured while the keyboard is down so searching doesn't squash it. 0 until known. */
    private var cellH = 0
    private val index = LetterIndex()
    private val searchIcon = android.widget.ImageView(a).apply { setImageDrawable(a.symbol("search")) }
    private val pager = Workspace(a)
    private val search: EditText = EditText(a).apply {
        hint = a.getString(R.string.search_apps)
        textSize = if (styleName == "glass" || styleName == "housing") 15f else 16f
        setSingleLine()
        setTextColor(a.m3.onSurface)
        setHintTextColor(a.m3.onSurfaceVariant)
        background = null
        setPadding(0, 0, 0, 0)
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refresh()
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        setOnEditorActionListener { _, _, _ -> entries.firstOrNull()?.let { open(it, null) }; true }
        // Housing: a caret in the legend ink, so the search glyph stays the only signal.
        if (housing && android.os.Build.VERSION.SDK_INT >= 29) textCursorDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(a.m3.onSurface); setSize((2 * a.dp).toInt(), (20 * a.dp).toInt())
        }
        // Housing: the search glyph lights in the signal colour while the field is live.
        // While it's live it holds the screen's one signal, so the letter index gives its dot up.
        setOnFocusChangeListener { _, f ->
            if (housing) { searchIcon.setImageDrawable(a.symbol("search", if (f) a.m3.signalInk else a.m3.onSurfaceVariant)); index.invalidate() }
            if (!f && barOff && text.isEmpty()) searchBar.visibility = View.GONE
        }
    }

    private lateinit var searchBar: View

    /** Focuses search with the keyboard up, showing the bar first when it's turned off. */
    private fun focusSearch() {
        searchBar.visibility = View.VISIBLE
        search.requestFocus()
        a.getSystemService(InputMethodManager::class.java).showSoftInput(search, 0)
    }

    private lateinit var menuButton: View

    /** On the Private tab: the space's name and state, and a lock/unlock button. */
    private val privateBar = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
    /** The locked Private tab: a closed padlock, the state, and an Unlock button, centred where the apps would be. */
    private val privateLockedView = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; visibility = View.GONE
        addView(android.widget.ImageView(a).apply { setImageDrawable(a.symbol("lock", a.m3.onSurfaceVariant, 48f)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        addView(a.text(a.getString(R.string.private_locked), 16f, a.m3.onSurface).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = (16 * a.dp).toInt(); bottomMargin = (20 * a.dp).toInt() })
        addView(a.tonalButton(a.getString(R.string.unlock)) { a.repo.setPrivateLocked(false) }, LinearLayout.LayoutParams(-2, -2))
    }
    /** As Nova: the most-launched apps in their own row above the list, in every style; they stay in the list too. */
    private val frequentRow = object : LinearLayout(a) {
        // A pull down from the row's apps: their own touch would keep the drag, so take it over once it's a pull.
        override fun onInterceptTouchEvent(ev: MotionEvent) = pullDown.handle(ev) || super.onInterceptTouchEvent(ev)
        // The row is the touch's target on the gaps between its apps; claim those too, so a pull can start anywhere on it.
        override fun onTouchEvent(ev: MotionEvent): Boolean { pullDown.handle(ev); return true }
    }.apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }

    private fun buildPrivateBar() {
        privateBar.removeAllViews()
        val locked = a.repo.privateLocked
        privateBar.addView(a.text(a.getString(if (locked) R.string.private_locked else R.string.private_space), 14f, a.m3.onSurfaceVariant, medium = true),
            LinearLayout.LayoutParams(0, -2, 1f))
        // Install apps into the space: Android opens the app store inside it. Hidden with no store installed.
        // ponytail: checks for a store in the main profile, not the space; a store only in the space hides the button.
        val hasStore = a.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MARKET), 0) != null
        val market = if (!locked && hasStore && android.os.Build.VERSION.SDK_INT >= 35) a.repo.privateUser?.let { u ->
            runCatching { a.launcherApps.getAppMarketActivityIntent(null, u) }.getOrNull() } else null
        if (market != null) privateBar.addView(a.iconButton("download", a.getString(R.string.private_install)) {
            runCatching { a.startIntentSender(market, null, 0, 0, 0) }
        })
        privateBar.addView(a.iconButton(if (locked) "unlock" else "lock", a.getString(if (locked) R.string.private_unlock else R.string.private_lock)) {
            a.repo.setPrivateLocked(!locked)
        })
    }

    /** The bar, the field and the frequent row: one pull, all of it closing. */
    private val pullDown = PullDown()
    /** Also cancels the view's own touch (a tap, a cursor) once the pull owns the gesture. */
    private val pullTouch = View.OnTouchListener { v, ev ->
        val handled = pullDown.handle(ev)
        if (pullDown.started) MotionEvent.obtain(ev).also { it.action = MotionEvent.ACTION_CANCEL; v.onTouchEvent(it); it.recycle() }
        handled
    }

    val root = object : LinearLayout(a) {
        private var ignoring = false
        // Only a drawer that's fully open and at rest takes a new touch, so no app opens or lifts mid-animation. While it
        // closes the touch goes to home underneath; while it opens or follows a finger (even pulled all the way), it's dropped.
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                ignoring = progress < 1f || held
                if (ignoring && anim != null && !settlingOpen) return false
                // Land the previous swipe before hit testing a touch on the moving grids. Not elsewhere: landing rebuilds the
                // chips and frequent row, and the new views aren't laid out yet, so a tap on them would hit nothing.
                if (!ignoring) (grid.parent as? View)?.takeIf { ev.y >= it.top && ev.y < it.bottom }?.let { tabAnim?.end() }
            }
            return ignoring || super.dispatchTouchEvent(ev)
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        // Glass: a tint over the blurred home screen, lighter where the system really blurs what's behind.
        background = if (housing) Sheet()
            else if (glass) android.graphics.drawable.ColorDrawable(a.glassTint(a.glassBlur))
            else a.panelBg(a.looks.int("drawerAlpha"), 0f, a.m3.surface)
        visibility = View.GONE
        setOnDragListener(a.drag.passDrag)
        isClickable = true
        val pad = (16 * a.dp).toInt()
        // M3 search bar: a pill with a leading search icon and a trailing menu button. Glass puts it at the bottom,
        // under the thumb; it rides up with the keyboard.
        val bar = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = if (housing) a.well(24 * a.dp) else if (glass) a.rounded(if (a.m3.dark) 0x1FFFFFFF else 0x8CFFFFFF.toInt(), 28 * a.dp, if (a.m3.dark) 0x1AFFFFFF else 0x14000000, a.dp / 2)
                else a.rounded(a.m3.surfaceHighest, 28 * a.dp)
            setPadding(pad, 0, (4 * a.dp).toInt(), 0)
            addView(searchIcon, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = pad })
            // The bar's full height, so the field TalkBack focuses is a 48 dp target too.
            addView(search, LinearLayout.LayoutParams(0, -1, 1f))
            menuButton = a.iconButton("more", a.getString(R.string.more_options)) { menu() }
            addView(menuButton)
            setOnClickListener { focusSearch() }
            if (barOff) visibility = View.GONE
        }
        searchBar = bar
        // A pull down on the search bar closes the drawer, wherever the list is scrolled (#56). Taps still reach the field.
        bar.setOnTouchListener(pullTouch)
        search.setOnTouchListener(pullTouch)
        val barParams = LinearLayout.LayoutParams(-1, ((if (bottomBar) 48 else 56) * a.dp).toInt()).apply { setMargins(pad, pad / 2, pad, pad / 2) }
        if (!bottomBar) addView(bar, barParams)
        tabs.setPaddingRelative(pad + (4 * a.dp).toInt(), if (bottomBar || fromBottom) pad / 2 else 0, pad, (8 * a.dp).toInt())
        val content = FrameLayout(a)
        if (styleName == "pages") content.addView(pager) else { content.addView(peek); content.addView(grid) }
        content.addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(pad * 2, 0, pad * 2, 0) })
        content.addView(privateLockedView, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        if (letterIndex) content.addView(index, FrameLayout.LayoutParams((28 * a.dp).toInt(), -1, Gravity.END))
        if (fromBottom) addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        // Nova: above the tabs, except horizontal pages, where it sits under them. A divider closes it off.
        if (styleName != "pages") addView(frequentRow, LinearLayout.LayoutParams(-1, -2))
        addView(tabScroll, LinearLayout.LayoutParams(-1, -2))
        if (styleName == "pages") addView(frequentRow, LinearLayout.LayoutParams(-1, -2))
        addView(privateBar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(pad, 0, pad / 2, 0) })
        if (!fromBottom) addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        if (bottomBar) addView(bar, barParams)
        // setProgress fades this sheet and each of its parts. Drawn as overlapping, every faded part would render into its
        // own offscreen buffer (the sheet alone 10 MB at 1080×2340) that the GPU cache then holds for a minute or two.
        forceHasOverlappingRendering(false)
        for (i in 0 until childCount) getChildAt(i).forceHasOverlappingRendering(false)
    }

    val visible get() = root.visibility == View.VISIBLE

    /** The list's pull down (see [PullDown]), only from its top, and pull-to-search when that's on. */
    private val listPull = PullDown(start = { !grid.canScrollVertically(-1) }, search = ::focusSearch)

    /** Where the housing sheet's top edge sits below the status bar, and the grip area under it. */
    private val sheetGap = 12 * a.dp
    private val gripArea = 28 * a.dp

    /** System insets: the housing sheet starts below the status bar, over a dimmed home. */
    fun applyInsets(l: Int, t: Int, r: Int, b: Int) =
        root.setPadding(l, t + if (housing) (sheetGap + gripArea).toInt() else 0, r, b)

    /**
     * The housing drawer's background: home dimmed behind, a soft shadow above the sheet, then the sheet itself with
     * a machined top edge and a recessed grip.
     */
    private inner class Sheet : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val radius = 22 * a.dp
        private val face = a.housingFace(a.m3.surface, radius)
        private val grip = a.well(3 * a.dp)
        private var shadeTop = Float.NaN

        override fun draw(c: android.graphics.Canvas) {
            val b = bounds
            val top = root.paddingTop - gripArea
            c.drawColor(0x66000000)
            if (top != shadeTop) { shadeTop = top; paint.shader = android.graphics.LinearGradient(0f, top - 20 * a.dp, 0f, top + 4 * a.dp, 0, 0x40000000, android.graphics.Shader.TileMode.CLAMP) }
            c.drawRect(0f, top - 20 * a.dp, b.width().toFloat(), top + radius, paint)
            face.setBounds(b.left, top.toInt(), b.right, (b.bottom + radius).toInt())
            face.draw(c)
            val gw = 36 * a.dp
            grip.setBounds(((b.width() - gw) / 2).toInt(), (top + 11 * a.dp).toInt(), ((b.width() + gw) / 2).toInt(), (top + 16 * a.dp).toInt())
            grip.draw(c)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(f: android.graphics.ColorFilter?) {}
        @Deprecated("Required override") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** The list's layout, shared by the swipe's neighbouring tab so the two line up. */
    private fun GridView.lay() {
        numColumns = cols
        // Grid styles: the row height holds the air between rows.
        verticalSpacing = if (styleName == "list") (16 * a.dp).toInt() else 0
        val side = ((if (bottomBar) 12 else 8) * a.dp).toInt()
        // Room at the end (the right, or the left in right-to-left languages) for the letter index.
        setPaddingRelative(side, (if (bottomBar) 16 * a.dp else 8 * a.dp).toInt(), if (letterIndex) (28 * a.dp).toInt() else side, (8 * a.dp).toInt())
        clipToPadding = false
    }

    init {
        peek.apply { lay(); adapter = peekAdapter; selector = android.graphics.drawable.ColorDrawable(0) }
        grid.apply {
            lay()
            // The stock selector is a sharp full-cell rectangle that flashes on a long press; a rounded state layer instead.
            selector = a.pressable(null, 16 * a.dp)
            adapter = this@Drawer.adapter
            setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
                // As Launcher3: a finger scrolling the list puts the keyboard away (the query stays).
                override fun onScrollStateChanged(v: android.widget.AbsListView, state: Int) {
                    scrolling = state != android.widget.AbsListView.OnScrollListener.SCROLL_STATE_IDLE
                    // Only with the field focused: hiding is a call to the input method, on every scroll that starts.
                    if (state == android.widget.AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL && search.hasFocus()) hideKeyboard()
                }
                // onScroll comes every frame; the letter changes only with the first row or the list (a new sections array).
                var last: Pair<Int, Array<String>>? = null
                override fun onScroll(v: android.widget.AbsListView, first: Int, visible: Int, total: Int) {
                    val now = first to this@Drawer.adapter.getSections()
                    if (index.dragging || now == last) return
                    last = now
                    index.current = this@Drawer.adapter.getSectionForPosition(first)
                }
            })
            // The reset from [forgetPosition], once the rows are laid out (and posted: a selection during layout warns).
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (forgetScroll) {
                    forgetScroll = false
                    post { setSelection(if (fromBottom) this@Drawer.adapter.count - 1 else 0) }
                }
            }
            setOnItemClickListener { _, v, pos, _ -> this@Drawer.adapter.getItem(pos)?.let { open(it, v) } }
            setOnItemLongClickListener { _, v, pos, _ -> this@Drawer.adapter.getItem(pos)?.let { longPress(it, v) }; true }
            val touchSlop = android.view.ViewConfiguration.get(a).scaledTouchSlop
            var startX = 0f
            var startY = 0f
            var sideways = false
            var sideStart = 0f
            var sideVelocity: android.view.VelocityTracker? = null
            setOnTouchListener { _, ev ->
                // GridView keeps a tap alive until the finger leaves the whole list, so a sideways swipe that started on
                // an icon would open it on release. Sideways is no tap: cancel it, and the rest of that touch moves the tabs:
                // the list follows the finger with the next or previous tab beside it, as pages do.
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) { sideways = false; startX = ev.x; startY = ev.y }
                else if (sideways) {
                    track(sideVelocity, ev)
                    val dx = ev.rawX - sideStart
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_MOVE -> dragTabs(dx)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            sideVelocity?.computeCurrentVelocity(1000)
                            val cancelled = ev.actionMasked == MotionEvent.ACTION_CANCEL
                            val vx = sideVelocity?.xVelocity ?: 0f
                            sideVelocity?.recycle(); sideVelocity = null
                            sideways = false
                            if (!cancelled) dragTabs(dx)
                            releaseTabs(dx, vx, cancelled)
                        }
                    }
                    return@setOnTouchListener true
                }
                if (ev.actionMasked == MotionEvent.ACTION_MOVE && !listPull.closing && abs(ev.x - startX) > touchSlop && abs(ev.x - startX) > abs(ev.y - startY)) {
                    sideways = true
                    val dx = ev.x - startX
                    sideStart = ev.rawX - dx + if (dx > 0) touchSlop else -touchSlop
                    sideVelocity?.recycle()
                    sideVelocity = android.view.VelocityTracker.obtain().also { track(it, ev) }
                    MotionEvent.obtain(ev).also { it.action = MotionEvent.ACTION_CANCEL; onTouchEvent(it); it.recycle() }
                    dragTabs(ev.rawX - sideStart)
                    return@setOnTouchListener true
                }
                // Pull down from the top of the list: the drawer follows the finger and closes on release (or searches).
                val handled = listPull.handle(ev)
                // The list sees no more of this touch: cancel it, or its pending long press lifts an app.
                if (listPull.started) MotionEvent.obtain(ev).also { it.action = MotionEvent.ACTION_CANCEL; onTouchEvent(it); it.recycle() }
                handled
            }
        }
        pager.onGesture = { if (it == "swipeDown") hide() }
    }

    /** How open the drawer is: 0 closed, 1 open. Follows the finger while it's pulled, then animates the rest. */
    private var progress = 0f
    private var anim: android.animation.ValueAnimator? = null
    /** Where a running [anim] is heading: true for open. */
    private var settlingOpen = false
    /** A finger is moving the drawer (home's swipe up, or its own pull down). */
    private var held = false
    /** [forgetPosition] waits for the list's next layout to send it to the top. */
    private var forgetScroll = false
    private var keyboardOnOpen = false

    /** Finger travel that fully opens or closes the drawer (Launcher3's phones: 300 dp; shorter here, so it's quicker). */
    private fun travel() = 220 * a.dp

    /** A VelocityTracker sample in screen coordinates: the list moves with the sheet, so its own would halve a flick. */
    private fun track(tracker: android.view.VelocityTracker?, ev: MotionEvent) {
        tracker?.let {
            val sample = MotionEvent.obtain(ev)
            sample.setLocation(ev.rawX, ev.rawY)
            it.addMovement(sample)
            sample.recycle()
        }
    }

    /**
     * A finger pulled down on the list, the search bar, the field or the frequent row: the drawer follows it and
     * closes on release, or springs back open. [start] gates the pull (the list only pulls from its top); [search], on
     * the list alone, turns a longer pull into search instead when 'pull to search' is on.
     */
    private inner class PullDown(
        private val start: () -> Boolean = { true },
        private val search: (() -> Unit)? = null,
    ) {
        /** The drawer is following this touch. */
        var closing = false
            private set
        /** This event started the pull, so the view's own touch must be cancelled. */
        var started = false
            private set
        private var downY = 0f
        private var velocity: android.view.VelocityTracker? = null

        fun handle(ev: MotionEvent): Boolean {
            started = false
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                // Screen coordinates: the list moves with the sheet, so its own would halve the pull.
                closing = false; downY = ev.rawY
                velocity?.recycle(); velocity = android.view.VelocityTracker.obtain()
            }
            track(velocity, ev)
            if (!closing) {
                if (ev.actionMasked == MotionEvent.ACTION_MOVE && ev.rawY - downY > 24 * a.dp && start()) {
                    if (search != null && a.looks.on("pullToSearch")) {
                        if (ev.rawY - downY > 96 * a.dp) { downY = Float.MAX_VALUE; search() }
                    } else {
                        closing = true; started = true; held = true; downY = ev.rawY; stop()
                        // As Launcher3, the keyboard goes as the drag down starts, not once the drawer is gone (#73).
                        hideKeyboard()
                    }
                }
                if (!closing) {
                    if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                        velocity?.recycle(); velocity = null
                    }
                    return false
                }
            }
            // On release too: the finger's last position decides whether it's past the threshold, even if the last move dropped.
            if (ev.actionMasked == MotionEvent.ACTION_MOVE || ev.actionMasked == MotionEvent.ACTION_UP)
                setProgress(1 - (ev.rawY - downY) / travel())
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                velocity?.computeCurrentVelocity(1000)
                release(-(velocity?.yVelocity ?: 0f), 0.7f)
                velocity?.recycle(); velocity = null
                closing = false; held = false
            }
            return true
        }
    }

    /**
     * Opaque panels hand home over, as One UI does: home lifts and fades out as the drawer covers it, so it isn't
     * blurred every frame. Glass and housing keep home showing (blurred or dimmed) behind them.
     */
    private val handover = !housing && !glass

    /**
     * The drawer at [p], staged as Launcher3's swipe to all apps on phones but earlier: the sheet rises [travel] with the
     * finger, its background fading in over 5–30% and the apps over 25–60%, while home sinks back and is gone by 30%.
     */
    private fun setProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        root.visibility = View.VISIBLE
        root.alpha = span(progress, 0.05f, 0.3f)
        val apps = span(progress, 0.25f, 0.6f)
        for (i in 0 until root.childCount) root.getChildAt(i).alpha = apps
        root.translationY = (1 - progress) * travel()
        val home = span(progress, 0f, 0.3f)
        if (glass && a.glassBlur) root.semBlur((40 * a.dp * home).toInt(), a.glassTint(true), 0f)
        if (a.overlay == null) a.setDepth(home, blur = !handover)
        if (handover) { a.content.alpha = 1 - home; a.content.translationY = -home * 48 * a.dp }
    }

    /**
     * Animates to open or closed from wherever the drawer is. [speed]: the release speed (px/s, upward positive) after a
     * finger pull, timed as Launcher3 does; null for a button or Back.
     */
    private fun settle(open: Boolean, speed: Float? = null) {
        stop()
        val to = if (open) 1f else 0f
        val left = kotlin.math.abs(to - progress)
        // Already there: finish now, so the keyboard isn't held back by an animation that moves nothing.
        if (left == 0f) { setProgress(to); if (!open) gone() else opened(); return }
        val pxMs = (speed ?: 0f) / 1000
        settlingOpen = open
        anim =android.animation.ValueAnimator.ofFloat(progress, to).apply {
            duration = if (speed != null) swipeDuration(pxMs, left) else ((if (open) 260 else 200) * maxOf(0.4f, left)).toLong()
            interpolator = if (speed == null) android.view.animation.DecelerateInterpolator(1.6f) else if (abs(pxMs) > 10) SCROLL else SCROLL_CUBIC
            addUpdateListener { setProgress(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(x: android.animation.Animator) { if (anim === x) { anim = null; if (!open) gone() else opened() } }
            })
            start()
        }
    }

    /** Stops a running open/close animation where it is (without its end action). */
    private fun stop() { val old = anim; anim = null; old?.cancel() }

    /**
     * 'Remember tab and scroll position' off: back to the home tab, and the list to the top (or the bottom, one-handed).
     * The scroll waits for a layout: a rebuild's data change lays the list out at its old place first and a selection set
     * before that is dropped, which is how a launch (the launch count changed) kept the old position (#111).
     */
    private fun forgetPosition() {
        tab = homeTab
        if (styleName == "pages") pager.snapTo(0, animate = false)
        forgetScroll = true
        grid.requestLayout()
    }

    /** Clears search and resets position, ready to open. */
    private fun prepare() {
        // Not while it's open and at rest; a close still animating is fair game, so the next open lands at the top.
        if (visible && progress >= 1f) return
        if (search.text.isNotEmpty()) search.text.clear()
        if (!a.looks.on("rememberPosition")) forgetPosition()
        if (inputs() != shown) refresh()
    }

    private fun opened() {
        if (keyboardOnOpen || a.looks.on("showKeyboard")) focusSearch()
        keyboardOnOpen = false
    }

    private fun gone() {
        tabAnim?.end()
        root.alpha = 1f
        for (i in 0 until root.childCount) root.getChildAt(i).alpha = 1f
        // Every close ends here, whatever started it: the keyboard mustn't stay up over home (#73).
        // Before GONE, which takes the focus and leaves the keyboard behind.
        hideKeyboard()
        root.visibility = View.GONE
        root.semBlur(0, 0, 0f)
        a.content.alpha = 1f
        a.content.translationY = 0f
        // A drag in progress still routes events to a GONE view by its bounds, so move it off-screen too.
        root.translationY = root.height.toFloat()
        progress = 0f
        held = false
    }

    fun show(keyboard: Boolean) {
        prepare()
        keyboardOnOpen = keyboard
        settle(open = true)
    }

    /**
     * Home's swipe up, while the finger is still down: [up] px pulled so far. On release ([end]) the drawer opens
     * if flung up ([vy] px/s, upward positive) or pulled past 30% of [travel]. Returns false for a pull that starts downward.
     */
    fun pull(up: Float, vy: Float, end: Boolean): Boolean {
        // A menu or overlay held open by this same finger: the swipe is swallowed, not opening the drawer over it.
        if (!visible && (a.homeMenus.menu != null || a.overlay != null)) return true
        if (!end && !visible && up <= 0f) return false
        // Once per gesture: a close still animating (held is false until this pull moves) is forgotten too.
        if (!visible || !held) prepare()
        stop()
        held = !end
        if (end) {
            val open = release(vy, 0.3f)
            if (open && !armed) root.tick() // a fling that never crossed the line still gets its tick
            armed = false
        } else {
            setProgress(up / travel())
            // The tick comes as the pull crosses the line where letting go opens the drawer, not on release (#97).
            if (!armed && progress >= 0.3f) { armed = true; root.tick() } else if (armed && progress < 0.3f) armed = false
        }
        return true
    }

    /** Whether this pull has passed the open line and ticked for it. */
    private var armed = false

    /** Ends a finger-driven pull: opens if flung up or past [threshold], unless flung down. Returns whether it opens. */
    private fun release(vy: Float, threshold: Float): Boolean {
        // Launcher3 counts anything over 1 dp/ms as a fling.
        val open = drawerOpens(progress, vy, 1000 * a.dp, threshold)
        settle(open, vy)
        return open
    }

    /** Puts the keyboard away and takes the focus off search, as Launcher3's ExtendedEditText.hideKeyboard. */
    private fun hideKeyboard() {
        a.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    /** [animate] false for a drag leaving the drawer: it must be out of the way at once. */
    fun hide(animate: Boolean = true) {
        if (!visible) return
        hideKeyboard()
        search.text.clear()
        found.forget()
        showHidden = false
        if (animate) settle(open = false)
        else { stop(); if (a.overlay == null) a.setDepth(0f); gone() }
    }

    fun groups() = groupsFromJson(a.store.data.getString("groups", "[]")!!)

    /** What [refresh] reads; when it's unchanged, opening the drawer reuses what's already built. */
    private fun inputs() = listOf(a.repo.apps, a.store.data.all, a.looks.all(), a.repo.privateUser, tab, showHidden, search.text.toString())
    private var shown: List<Any?>? = null

    fun refresh() {
        shown = inputs()
        val q = search.text.toString().trim()
        val hidden = a.store.strings("hidden")
        val launches = a.launches()
        // Each key once: a comparator's selector runs on every comparison.
        val sort = a.looks.str("drawerSort")
        val rank = a.store.strings("drawerOrder").withIndex().associate { it.value to it.index }
        val ordered = a.repo.apps.values.map { it to SortKey(a.repo.label(it), it.info.firstInstallTime, launches.optInt(it.key), rank[it.key] ?: Int.MAX_VALUE) }
            .sortedWith(compareBy(drawerComparator(sort)) { it.second }).map { it.first }
        // Hidden tabs aren't in the drawer at all; their apps are back in All apps.
        val groups = groups().filterNot { it.type == "tab" && it.hidden }
        val work = a.repo.apps.values.filter { a.repo.isWork(it) }.map { it.key }.toSet()
        val workTab = a.workTabShown() && work.isNotEmpty()
        val private = a.repo.apps.values.filter { a.repo.isPrivate(it) }.map { it.key }.toSet()
        val privateTab = a.repo.privateUser != null
        // All apps can be hidden while there's a tab to show instead; its folders then show on that tab.
        homeTab = groups.firstOrNull { it.type == "tab" }?.takeIf { a.store.data.getBoolean(ALL_HIDDEN, false) }?.name ?: ""
        if (tab == "work" && !workTab || tab == "private" && !privateTab || tab == "" && homeTab != "" ||
            tab.isNotEmpty() && tab != "work" && tab != "private" && groups.none { it.name == tab && it.type == "tab" }) tab = homeTab
        val keepInAll = a.looks.on("keepInAll")
        val foldersFirst = a.looks.on("foldersFirst")
        val keys = ordered.map { it.key }
        entriesOf = { t ->
            val list = tabApps(keys, groups, t, hidden, showHidden, keepInAll, work, workTab, private, sort == "manual").mapNotNull { a.repo.apps[it] }.map { Entry.AppE(it) }
            val folders = if (t == homeTab) groups.filter { it.type == "folder" }.map { Entry.FolderE(it) } else emptyList()
            if (foldersFirst) folders + list else list + folders
        }
        entries = if (q.isNotEmpty()) {
            // Best match first; sortedByDescending is stable, so equal matches keep the drawer's order.
            found.quick(q) + ordered.filter { showHidden || it.key !in hidden }.map { it to matchScore(a.repo.label(it), q) }
                .filter { it.second > 0 }.sortedByDescending { it.second }.map { Entry.AppE(it.first) } +
                found.shortcutHits(q, hidden, showHidden, iconStyle.sizePx) + found.settingsHits(q) +
                found.contacts(q) + if (a.looks.on("webSearch")) listOf(Entry.WebE(q)) else emptyList()
        } else entriesOf(tab)
        // As Nova: the row sits above the tabs and stays whichever tab is open.
        buildFrequent(if (q.isEmpty() && a.looks.on("frequent"))
            frequentApps(a.store.strings("favourites").toList(), a.repo.apps.keys.associateWith { launches.optInt(it) },
                { it in a.repo.apps && it !in hidden && it !in private }, gridCols).mapNotNull { a.repo.apps[it] } else emptyList())
        // Glass and housing: search results sit just above the bottom search bar, under the thumb.
        grid.isStackFromBottom = fromBottom || bottomBar && q.isNotEmpty()
        // No index for a list that fits on one screen: there's nothing to jump to.
        index.visibility = if (q.isEmpty() && entries.size > cols * rows) View.VISIBLE else View.GONE
        empty.text = a.getString(R.string.no_apps_match, q)
        empty.visibility = if (q.isNotEmpty() && entries.none { it !is Entry.WebE }) View.VISIBLE else View.GONE
        // The user's tabs with Work at its place among them, hidden tabs counted so hiding one doesn't move Work.
        val shownTabs = groups.filter { it.type == "tab" }.map { it.name }.toSet()
        val order = withWorkSlot(this.groups().filter { it.type == "tab" }.map { it.name }, a.store.data.getInt(WORK_AT, 0))
            .filter { if (it == WORK_SLOT) workTab else it in shownTabs }.map { if (it == WORK_SLOT) "work" else it }
        rebuildTabs = { buildTabs(groups, order, privateTab, q.isEmpty()) }
        rebuildTabs()
        // Locked, the centred lock takes over; the bar (state, install, lock) is for the open space.
        val locked = a.repo.privateLocked
        privateLockedView.visibility = if (q.isEmpty() && tab == "private" && locked) View.VISIBLE else View.GONE
        privateBar.visibility = if (q.isEmpty() && tab == "private" && !locked) View.VISIBLE else View.GONE
        if (privateBar.visibility == View.VISIBLE) buildPrivateBar()
        // Warm the first two screens off the UI thread; the rest is prefetched as the list scrolls (see Adapter).
        a.repo.prefetchIcons(entries.filterIsInstance<Entry.AppE>().map { it.app }.take(cols * rows * 2))
        if (styleName == "pages") buildPages() else adapter.notifyDataSetChanged()
        index.invalidate() // its letters are the new list's
    }

    /** Something it shows changed: rebuilt now if open, else on the next open. */
    fun invalidate() { if (visible) refresh() else shown = null }

    fun refreshBadges() { if (visible) grid.invalidateViews() }

    /** The tab keys in chip order, as [buildTabs] last laid them out; empty while the chips are hidden. */
    private var tabKeys = listOf<String>()

    private var tabAnim: android.animation.ValueAnimator? = null
    /** [buildTabs] as [refresh] last called it, to mark a newly landed tab before its apps are swapped in. */
    private var rebuildTabs: () -> Unit = {}

    /**
     * A sideways swipe [dx] px so far: the list follows the finger and the tab it's heading for slides in beside it. A
     * finger moving left brings the next tab (the previous one in right-to-left), wrapping round at the ends.
     */
    private fun dragTabs(dx: Float) {
        val w = grid.width.toFloat()
        val offset = dx.coerceIn(-w, w)
        val rtl = root.layoutDirection == View.LAYOUT_DIRECTION_RTL
        // Tabs wrap round as home's pages do: with Infinite scroll (#97).
        val next = swipedTab(tabKeys, tab, (dx < 0) != rtl, a.looks.on("infiniteScroll"))
        if (next == null) { grid.translationX = dx / 4; peek.visibility = View.GONE; peekTab = null; return } // nowhere to go: a little give
        if (next != peekTab) {
            peekTab = next
            peekEntries = entriesOf(next)
            peek.isStackFromBottom = fromBottom
            peekAdapter.notifyDataSetChanged()
            peek.setSelection(if (fromBottom) peekAdapter.count - 1 else 0)
            peek.visibility = View.VISIBLE
        }
        grid.translationX = offset
        peek.translationX = offset + if (dx < 0) w else -w
        index.alpha = 1 - minOf(1f, abs(offset) / (w / 3).coerceAtLeast(1f))
    }

    /** Let go after [dragTabs]: on to the next tab if pulled a third of the way or flung that way ([vx] px/s), else back. */
    private fun releaseTabs(dx: Float, vx: Float, cancelled: Boolean) {
        val w = grid.width.toFloat()
        val fling = 500 * a.dp
        val flung = abs(vx) > fling
        val landed = peekTab?.takeIf { !cancelled && (if (flung) vx * dx > 0 else abs(dx) > w / 3) }
        val to = if (landed != null) (if (dx < 0) -w else w) else 0f
        val peekOffset = peek.translationX - grid.translationX
        // The chip and the tick come as the finger lets go, not after the slide: switching fast felt laggy (#97).
        if (landed != null) { tab = landed; rebuildTabs(); grid.tick() }
        val animation = android.animation.ValueAnimator.ofFloat(grid.translationX, to).apply {
            duration = (120 + 160 * abs(to - grid.translationX) / maxOf(1f, w)).toLong()
            interpolator = EASE
            addUpdateListener {
                val x = it.animatedValue as Float
                grid.translationX = x
                if (peekTab != null) peek.translationX = x + peekOffset
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(x: android.animation.Animator) {
                    tabAnim = null
                    if (landed != null) {
                        refresh()
                        grid.setSelection(if (fromBottom) adapter.count - 1 else 0)
                    }
                    grid.translationX = 0f
                    peek.visibility = View.GONE
                    peekTab = null
                    index.alpha = 1f
                }
            })
        }
        tabAnim = animation
        animation.start()
    }

    /** [order]: the tab keys after All apps and Private, "work" among the user's tab names. */
    private fun buildTabs(groups: List<Group>, order: List<String>, privateTab: Boolean, show: Boolean) {
        tabs.removeAllViews()
        val names = (if (homeTab == "") listOf("" to a.getString(R.string.tab_all)) else emptyList()) +
            (if (privateTab) listOf("private" to a.getString(R.string.tab_private)) else emptyList()) +
            order.map { it to if (it == "work") a.getString(R.string.tab_work) else it }
        tabScroll.visibility = if (show && names.size > 1) View.VISIBLE else View.GONE
        tabKeys = if (tabScroll.visibility == View.VISIBLE) names.map { it.first } else emptyList()
        // The selected tab is a solid ink chip (a raised key in housing); the others are plain legends with no border. Selected or not, a tab is the same width, so the rows never rewrap on a switch.
        for ((key, title) in names) {
            val on = key == tab
            // A tab's own colour (set in its editor, as Nova's) marks it while selected: the bar, or the housing key's fill.
            val own = groups.firstOrNull { it.name == key && it.type == "tab" }?.color?.let(android.graphics.Color::parseColor)
            val chip = !housing
            val ink = onColor(own ?: a.tabFill())
            tabs.addView(a.text(title, 14f, if (on) ink else a.m3.onSurfaceVariant, medium = true).apply {
                gravity = Gravity.CENTER
                isSelected = on
                setSingleLine()
                ellipsize = android.text.TextUtils.TruncateAt.END
                val p = (16 * a.dp).toInt()
                setPaddingRelative(p, 0, p, 0)
                background = a.pressable(when {
                    housing -> if (on) a.rounded(own ?: a.tabFill(), 16 * a.dp, if (a.m3.dark) 0x1FFFFFFF else 0x14000000, a.dp / 2) else null
                    chip -> if (on) a.rounded(own ?: a.tabFill(), 8 * a.dp) else null
                    else -> null
                }, if (housing) 16 * a.dp else 8 * a.dp)
                // A swipe still settling would otherwise land after this and take its tab back.
                setOnClickListener { tabAnim?.end(); tab = key; refresh() }
                if (key != "" && key != "work" && key != "private") setOnLongClickListener { a.editGroup(groups.first { it.name == key }); true }
                // All apps can be hidden once there's a tab of the user's own to open on instead.
                if (key == "" && groups.any { it.type == "tab" }) setOnLongClickListener {
                    a.pickList(title, listOf(a.symbol("hide") to a.getString(R.string.tab_all_hide)), summaries = listOf(a.getString(R.string.tab_all_hide_info))) {
                        a.store.data.edit().putBoolean(ALL_HIDDEN, true).apply(); refresh()
                    }
                    true
                }
            })
        }
        // Keep the selected chip visible when a swipe lands beyond the visible rows.
        tabs.chipToReveal = tabs.getChildAt(tabKeys.indexOf(tab))
    }

    private fun buildFrequent(apps: List<App>) {
        frequentRow.removeAllViews()
        frequentRow.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
        if (apps.isEmpty()) return
        val side = ((if (bottomBar) 12 else 8) * a.dp).toInt()
        frequentRow.addView(LinearLayout(a).apply {
            setPaddingRelative(side, (8 * a.dp).toInt(), if (letterIndex) (28 * a.dp).toInt() else side, (8 * a.dp).toInt())
            clipToPadding = false // a top badge pokes into the padding
            // Empty slots keep each icon in its grid column.
            for (i in 0 until gridCols) addView(apps.getOrNull(i)?.let { app -> val e = Entry.AppE(app)
                entryView(e, style = rowStyle).apply {
                    setOnClickListener { v -> open(e, v) }
                    setOnLongClickListener { v -> longPress(e, v); true }
                }
            } ?: View(a), LinearLayout.LayoutParams(0, if (i < apps.size) -2 else 0, 1f))
        })
        // Every style: without it, the row reads as the first row of the list, its apps as duplicates.
        frequentRow.addView(a.divider())
    }

    private fun buildPages() {
        pager.removeAllViews()
        entries.chunked(cols * rows).ifEmpty { listOf(emptyList()) }.forEach { chunk ->
            pager.addView(CellLayout(a, cols, rows, 0).apply {
                chunk.forEachIndexed { i, e ->
                    addView(entryView(e).apply {
                        layoutParams = CellLayout.CellParams(i % cols, i / cols, 1, 1)
                        setOnClickListener { v -> open(e, v) }
                        setOnLongClickListener { v -> longPress(e, v); true }
                    })
                }
            })
        }
        pager.snapTo(0, animate = false)
    }

    /** [recycled]: a view GridView no longer shows; an app's view is rebound instead of built again. */
    /** The grid is moving: an icon missing from memory is decoded off the UI thread, not in the middle of a frame. */
    private var scrolling = false
    /** The app each recycled cell shows now, so a late icon lands only where it still belongs. */
    private val bound = java.util.WeakHashMap<IconView, String>()
    private val blank = android.graphics.drawable.ColorDrawable(0)

    private fun entryView(e: Entry, recycled: View? = null, style: IconStyle = iconStyle): IconView = when (e) {
        is Entry.AppE -> {
            var view: IconView? = null
            val icon = if (!scrolling) a.repo.icon(e.app)
                else a.repo.iconLater(e.app) { d -> view?.takeIf { bound[it] == e.app.key }?.image?.icon = d } ?: blank
            ((recycled as? IconView)?.takeIf { it.tag == APP_VIEW }?.apply { bind(a.repo.label(e.app), icon, e.app.pkg) }
                ?: IconView(a, a.repo.label(e.app), icon, style, a.badgeStyle(), e.app.pkg).apply { tag = APP_VIEW }).also {
                view = it
                bound[it] = e.app.key
                if (a.looks.str("badges") != "off") a.badge(it.image, e.app.pkg)
                accessibleActions(it, { a.homeMenus.showPopup(it, e.app) })
            }
        }
        // As a folder on home: badged by the apps inside it (#118).
        is Entry.FolderE -> folderItems(e.group).let { items ->
            val keys = items.map { it.pkg }.distinct().joinToString("|")
            IconView(a, e.group.name, a.folderIcon(items), iconStyle, a.badgeStyle(), keys)
                .also { if (a.looks.str("badges") != "off") a.badge(it.image, keys) }
        }
        // Short label so the query survives the one-line ellipsis; the globe says "web".
        is Entry.WebE -> IconView(a, a.getString(R.string.search_web_for, e.query),GlyphIcon(a.m3.primaryContainer, a.m3.onPrimaryContainer, symbol = "globe"), iconStyle, a.badgeStyle(), "")
        is Entry.ContactE -> IconView(a, e.name, e.photo?.let { p ->
            runCatching { a.contentResolver.openInputStream(android.net.Uri.parse(p))!!.use { android.graphics.drawable.BitmapDrawable(a.resources, android.graphics.BitmapFactory.decodeStream(it)) } }.getOrNull()
        } ?: GlyphIcon(a.m3.primaryContainer, a.m3.onPrimaryContainer, text = e.name.take(1).uppercase()), iconStyle, a.badgeStyle(), "")
        is Entry.ActionE -> IconView(a, e.label, e.icon, iconStyle, a.badgeStyle(), "")
    }

    private fun folderItems(g: Group) = g.apps.mapNotNull { a.repo.apps[it] }.map { Item(0, 0, 0, pkg = it.pkg, cls = it.cls, user = it.serial) }

    /**
     * Fully open, at rest, no finger moving it. As Launcher3's item handlers: a tap or hold that lands any other way
     * (a long press that fires after the drawer started closing, say) is dropped rather than opening or lifting an app.
     */
    private val settled get() = progress >= 1f && anim == null && !held

    private fun open(e: Entry, v: View?) {
        if (!settled) return
        when (e) {
            is Entry.AppE -> a.launch(e.app, v)
            is Entry.FolderE -> {
                var group = e.group
                var apps = group.apps.mapNotNull { a.repo.apps[it] }
                a.openFolderWindow(group.name, folderItems(group), null, reorder = { from, to ->
                    // Uninstalled apps' keys aren't shown: they keep their place at the end.
                    val keys = apps.map { it.key }.toMutableList().apply { add(to, removeAt(from)) }
                    group = group.copy(apps = keys + (group.apps - keys.toSet()))
                    apps = group.apps.mapNotNull { a.repo.apps[it] }
                    a.saveGroups(groups().map { if (it.name == group.name) group else it })
                    folderItems(group)
                }, icon = (v as? IconView)?.image, reload = {
                    groups().firstOrNull { it.name == group.name && it.type == "folder" }?.let {
                        group = it; apps = it.apps.mapNotNull { k -> a.repo.apps[k] }; folderItems(it)
                    }
                }) { i -> GroupChild(group.name, apps[i]) }
            }
            is Entry.WebE -> a.webSearch(e.query)
            is Entry.ContactE -> runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, e.uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            is Entry.ActionE -> e.run(v)
        }
    }

    private fun longPress(e: Entry, v: View) {
        if (!settled) return
        when (e) {
            is Entry.AppE -> a.drag.startDrag(v, e.app)
            // As an app: drag to copy it to home; its menu (Add, Edit) shows if the finger stays.
            is Entry.FolderE -> a.drag.startDrag(v, e.group)
            is Entry.WebE, is Entry.ContactE, is Entry.ActionE -> {}
        }
    }

    /** Arranges the open tab's apps by hand; that switches the drawer to the manual sort. */
    private fun arrange() {
        if (search.text.isNotEmpty()) search.setText("") // the tab's apps, not search results
        a.arrangeTab(tab, entries.mapNotNull { (it as? Entry.AppE)?.app?.key })
    }

    private fun menu() {
        a.homeMenus.showMenu(a.homeMenus.screenRect(menuButton), narrow = true) {
            fun row(sym: String, title: String, act: () -> Unit) = addView(a.listItem(a.symbol(sym), title, dense = true) { a.homeMenus.closeMenu(); act() })
            row(if (showHidden) "hide" else "show", a.getString(if (showHidden) R.string.hidden_hide else R.string.hidden_show)) { showHidden = !showHidden; refresh() }
            row("sort", a.getString(R.string.set_drawerSort)) {
                a.sheet().setTitle(R.string.set_drawerSort).setSingleChoiceItems(SORT_NAMES.map(a::getString).toTypedArray(), SORTS.indexOf(a.looks.str("drawerSort"))) { d, i ->
                    a.looks["drawerSort"] = SORTS[i]; d.dismiss(); refresh()
                    if (SORTS[i] == "manual") arrange()
                }.show()
            }
            row("drag", a.getString(R.string.arrange_apps)) { arrange() }
            row("star", a.getString(R.string.favourites)) { a.chooseFavourites() }
            row("tabs", a.getString(R.string.group_tabs)) { settings(TABS_GROUP) }
            row("folder", a.getString(R.string.group_drawer_folders)) { settings(DRAWER_FOLDERS) }
            row("hide", a.getString(R.string.choose_hidden_apps)) { a.hideAppsMenu() }
            row("settings", a.getString(R.string.launcher_settings)) { settings() }
        }
    }

    /** Settings fades in over the open drawer, which closes unseen once home is hidden (MainActivity.onStop). */
    private fun settings(page: String = "") {
        a.showSettings(page)
        closeOnStop = true
    }
    var closeOnStop = false

    /** The drawer's letter index down the right edge: the section in view is marked with the signal dot; touch or drag to jump. */
    private inner class LetterIndex : View(a) {
        var current = 0
            set(v) { if (field != v) { field = v; invalidate() } }
        var dragging = false
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 11 * a.resources.displayMetrics.scaledDensity
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        init { contentDescription = context.getString(R.string.letter_index) }

        private fun step(n: Int) = minOf(height / n.toFloat(), 20 * a.dp)

        override fun onDraw(c: android.graphics.Canvas) {
            val s = adapter.getSections()
            if (s.size < 2) return
            val step = step(s.size)
            val top = (height - step * s.size) / 2
            val cx = width / 2f + 3 * a.dp
            s.forEachIndexed { i, l ->
                val cy = top + step * i + step / 2
                paint.color = if (i == current) a.m3.onSurface else a.m3.onSurfaceVariant
                c.drawText(l, cx, cy - (paint.ascent() + paint.descent()) / 2, paint)
                if (i == current && !search.hasFocus()) { paint.color = a.m3.signalInk; c.drawCircle(cx - 10 * a.dp, cy, 2.5f * a.dp, paint) }
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val s = adapter.getSections()
            if (s.size < 2) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    dragging = true
                    parent.requestDisallowInterceptTouchEvent(true)
                    val step = step(s.size)
                    val i = ((e.y - (height - step * s.size) / 2) / step).toInt().coerceIn(0, s.size - 1)
                    if (i != current) { current = i; tick() }
                    grid.setSelection(adapter.getPositionForSection(i))
                }
                else -> dragging = false
            }
            return true
        }
    }

    /** A list of [source]'s entries in [host]: the drawer's own, or the neighbouring tab's during a swipe. */
    private inner class Adapter(private val source: () -> List<Entry>, private val host: () -> GridView) : BaseAdapter(), SectionIndexer {
        private val entries get() = source()
        private val grid get() = host()
        private fun letter(e: Entry) = when (e) {
            is Entry.AppE -> indexLetter(a.repo.label(e.app))
            is Entry.FolderE -> "▤"
            is Entry.WebE -> "⌕"
            is Entry.ContactE -> "☺"
            is Entry.ActionE -> "⌕"
        }
        private var sections = arrayOf<String>()

        override fun notifyDataSetChanged() {
            sections = entries.map(::letter).distinct().toTypedArray()
            super.notifyDataSetChanged()
        }
        // Stacked from the bottom, GridView right-aligns the short row; padding to whole rows keeps results reading left to right.
        override fun getCount() = if (grid.isStackFromBottom) (entries.size + cols - 1) / cols * cols else entries.size
        /** Fill from bottom pads before the apps, so the short row is the top one and the bottom row is full (#33). */
        private val lead get() = if (fromBottom) count - entries.size else 0
        override fun getItem(pos: Int) = entries.getOrNull(pos - lead)
        override fun getItemId(pos: Int) = pos.toLong()
        override fun isEnabled(pos: Int) = getItem(pos) != null
        override fun getView(pos: Int, convert: View?, parent: ViewGroup): View {
            // Once per row, warm the next screen off the UI thread while this one is on show.
            if (pos % cols == 0 && pos < entries.size) a.repo.prefetchIcons(entries.drop(pos).take(cols * rows).filterIsInstance<Entry.AppE>().map { it.app })
            return (getItem(pos)?.let { entryView(it, convert) }?.apply {
            if (styleName == "list") setPadding((8 * a.dp).toInt(), 0, 0, 0)
        } ?: entryView(entries[0]).apply { visibility = View.INVISIBLE }).apply { // a filler as tall as a real cell, or GridView misplaces the row
            if (styleName == "list") return@apply
            if (!search.hasFocus() && parent.height > 0) cellH = (parent.height - parent.paddingTop - parent.paddingBottom) / rows
            // Kept, not replaced: GridView marks the first cell it measures forceAdd, and new params lost that, so the
            // cell was never attached to the window and accessibility skipped it.
            (layoutParams as? android.widget.AbsListView.LayoutParams ?: android.widget.AbsListView.LayoutParams(-1, -2).also { layoutParams = it })
                .height = if (cellH > 0) cellH else -2
        }
        }
        override fun getSections(): Array<String> = sections
        override fun getPositionForSection(s: Int) = entries.indexOfFirst { letter(it) == sections.getOrNull(s) }.coerceAtLeast(0) + lead
        override fun getSectionForPosition(p: Int) = sections.indexOf(getItem(p)?.let(::letter)).coerceAtLeast(0)
    }
}

