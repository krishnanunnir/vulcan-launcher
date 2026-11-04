package dev.vulcanlauncher

import android.content.Intent
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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SectionIndexer
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** A drawer tab or drawer folder: [type] is "tab" or "folder"; [apps] are app keys. */
data class Group(val name: String, val type: String, val apps: List<String>)

fun groupsFromJson(s: String): List<Group> = JSONArray(s).let { a ->
    List(a.length()) {
        val o = a.getJSONObject(it)
        val apps = o.getJSONArray("apps")
        Group(o.getString("name"), o.getString("type"), List(apps.length()) { i -> apps.getString(i) })
    }
}

fun groupsToJson(groups: List<Group>) = JSONArray().apply {
    groups.forEach { put(JSONObject().put("name", it.name).put("type", it.type).put("apps", JSONArray(it.apps))) }
}.toString()

/**
 * App keys shown in a drawer tab, in [ordered] order. [tab] is "" (all apps), "work", "private", or a tab group's name.
 * The main tab leaves out hidden apps, work apps when they have their own tab, Private Space apps (always in their
 * own tab), and grouped apps unless [keepInAll].
 */
fun tabApps(ordered: List<String>, groups: List<Group>, tab: String, hidden: Set<String>, showHidden: Boolean,
            keepInAll: Boolean, work: Set<String>, workTab: Boolean, private: Set<String> = emptySet()): List<String> {
    val shown = ordered.filter { showHidden || it !in hidden }
    return when (tab) {
        "" -> {
            val grouped = if (keepInAll) emptySet() else groups.flatMap { it.apps }.toSet()
            shown.filter { it !in grouped && !(workTab && it in work) && it !in private }
        }
        "work" -> shown.filter { it in work }
        "private" -> shown.filter { it in private }
        else -> groups.firstOrNull { it.name == tab && it.type == "tab" }?.apps?.toSet()?.let { g -> shown.filter { it in g } } ?: emptyList()
    }
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

/** The tab a sideways swipe lands on: the one after [current] in [keys] if [forward], else before, wrapping round at either end; null with fewer than two. */
fun swipedTab(keys: List<String>, current: String, forward: Boolean): String? =
    if (keys.size < 2) null else keys[(keys.indexOf(current).coerceAtLeast(0) + if (forward) 1 else -1).mod(keys.size)]

/** [groups] with [name] swapped with the nearest group of its type [by] -1 (earlier) or 1 (later); null at that end. */
fun moveGroup(groups: List<Group>, name: String, by: Int): List<Group>? {
    val i = groups.indexOfFirst { it.name == name }
    if (i < 0) return null
    val range = if (by < 0) (i - 1 downTo 0) else (i + 1 until groups.size)
    val j = range.firstOrNull { groups[it].type == groups[i].type } ?: return null
    return groups.toMutableList().apply { this[i] = groups[j]; this[j] = groups[i] }
}

/** Tag on a drawer view that shows an app, so GridView can hand it back for another app. */
private const val APP_VIEW = "app"

/** Material "emphasized" easing: quick start, long soft landing. */
val EASE = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)

/** The app drawer overlay. Built once per activity; [refresh] rebuilds its contents. */
class Drawer(private val a: MainActivity) {
    private sealed class Entry {
        class AppE(val app: App) : Entry()
        class FolderE(val group: Group) : Entry()
        class WebE(val query: String) : Entry()
        class ContactE(val name: String, val photo: String?, val uri: android.net.Uri) : Entry()
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
    private val adapter = Adapter()
    private val tabs = LinearLayout(a)
    private val tabScroll = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(tabs) }
    private val grid = GridView(a).apply {
        // Glass and housing: no scrollbar; rows fade out at the edges instead of being cut by the status bar or search bar.
        if (styleName == "glass" || styleName == "housing") { isVerticalScrollBarEnabled = false; isVerticalFadingEdgeEnabled = true; setFadingEdgeLength((32 * a.dp).toInt()) }
    }
    /** Shown when a search matches no app or contact. */
    private val empty = a.text("", 15f, a.m3.onSurfaceVariant).apply { gravity = Gravity.CENTER; visibility = View.GONE }
    /** Glass style: a tint over the blurred home screen and a frosted search bar with a hairline edge. */
    private val glass = styleName == "glass"
    /** Appliance housing: an opaque warm-grey body, a recessed search slot at the bottom and a letter index. */
    private val housing = styleName == "housing"
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
    /** As Nova: the most-launched apps in their own row above the list, in every style; they stay in the list too. */
    private val frequentRow = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }

    private fun buildPrivateBar() {
        privateBar.removeAllViews()
        val locked = a.privateLocked
        privateBar.addView(a.text(a.getString(if (locked) R.string.private_locked else R.string.private_space), 14f, a.m3.onSurfaceVariant, medium = true),
            LinearLayout.LayoutParams(0, -2, 1f))
        // Install apps into the space: Android opens the app store inside it. Hidden with no store installed.
        // ponytail: checks for a store in the main profile, not the space; a store only in the space hides the button.
        val hasStore = a.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MARKET), 0) != null
        val market = if (!locked && hasStore && android.os.Build.VERSION.SDK_INT >= 35) a.privateUser?.let { u ->
            runCatching { a.launcherApps.getAppMarketActivityIntent(null, u) }.getOrNull() } else null
        if (market != null) privateBar.addView(a.iconButton("download", a.getString(R.string.private_install)) {
            runCatching { a.startIntentSender(market, null, 0, 0, 0) }
        })
        privateBar.addView(a.iconButton(if (locked) "unlock" else "lock", a.getString(if (locked) R.string.private_unlock else R.string.private_lock)) {
            a.setPrivateLocked(!locked)
        })
    }

    val root = object : LinearLayout(a) {
        private var ignoring = false
        // Only a drawer that's fully open and at rest takes a new touch, so no app opens or lifts mid-animation. While it
        // closes the touch goes to home underneath; while it opens or follows a finger (even pulled all the way), it's dropped.
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                ignoring = progress < 1f || held
                if (ignoring && anim != null && !settlingOpen) return false
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
        setOnDragListener(a.passDrag)
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
        val barParams = LinearLayout.LayoutParams(-1, ((if (bottomBar) 48 else 56) * a.dp).toInt()).apply { setMargins(pad, pad / 2, pad, pad / 2) }
        if (!bottomBar) addView(bar, barParams)
        tabs.setPadding(pad - (4 * a.dp).toInt(), if (bottomBar || fromBottom) pad / 2 else 0, pad, (8 * a.dp).toInt())
        val content = FrameLayout(a)
        if (styleName == "pages") content.addView(pager) else content.addView(grid)
        content.addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(pad * 2, 0, pad * 2, 0) })
        if (housing && a.looks.on("fastScroll")) content.addView(index, FrameLayout.LayoutParams((28 * a.dp).toInt(), -1, Gravity.END))
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

    init {
        grid.apply {
            numColumns = cols
            // The stock selector is a sharp full-cell rectangle that flashes on a long press; a rounded state layer instead.
            selector = a.pressable(null, 16 * a.dp)
            // Grid styles: the row height holds the air between rows.
            verticalSpacing = if (styleName == "list") (16 * a.dp).toInt() else 0
            val side = ((if (bottomBar) 12 else 8) * a.dp).toInt()
            // Housing leaves room at the end (the right, or the left in right-to-left languages) for the letter index.
            setPaddingRelative(side, (if (bottomBar) 16 * a.dp else 8 * a.dp).toInt(), if (housing && a.looks.on("fastScroll")) (28 * a.dp).toInt() else side, (8 * a.dp).toInt())
            clipToPadding = false
            adapter = this@Drawer.adapter
            isFastScrollEnabled = a.looks.on("fastScroll") && !housing
            setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: android.widget.AbsListView, state: Int) {}
                override fun onScroll(v: android.widget.AbsListView, first: Int, visible: Int, total: Int) {
                    if (!index.dragging) index.current = this@Drawer.adapter.getSectionForPosition(first)
                }
            })
            setOnItemClickListener { _, v, pos, _ -> this@Drawer.adapter.getItem(pos)?.let { open(it, v) } }
            setOnItemLongClickListener { _, v, pos, _ -> this@Drawer.adapter.getItem(pos)?.let { longPress(it, v) }; true }
            var downY = 0f
            var closing = false
            var velocity: android.view.VelocityTracker? = null
            val touchSlop = android.view.ViewConfiguration.get(a).scaledTouchSlop
            var startX = 0f
            var startY = 0f
            var sideways = false
            setOnTouchListener { _, ev ->
                // GridView keeps a tap alive until the finger leaves the whole list, so a sideways swipe that started on
                // an icon would open it on release. Sideways is no tap: cancel it, and ignore the rest of that touch.
                // A sideways swipe moves to the next or previous tab.
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) sideways = false
                else if (sideways) {
                    if (ev.actionMasked == MotionEvent.ACTION_UP) swipeTab(ev.x - startX)
                    return@setOnTouchListener true
                }
                if (ev.actionMasked == MotionEvent.ACTION_MOVE && !closing && abs(ev.x - startX) > touchSlop && abs(ev.x - startX) > abs(ev.y - startY)) {
                    sideways = true
                    MotionEvent.obtain(ev).also { it.action = MotionEvent.ACTION_CANCEL; onTouchEvent(it); it.recycle() }
                    return@setOnTouchListener true
                }
                // Pull down from the top of the list: the drawer follows the finger and closes on release (or searches).
                // In screen coordinates too, so a flick's speed isn't halved by the moving list.
                fun track(t: android.view.VelocityTracker?) = t?.let { v -> MotionEvent.obtain(ev).also { it.setLocation(ev.rawX, ev.rawY); v.addMovement(it); it.recycle() } }
                track(velocity)
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // Screen coordinates: the list moves with the sheet, so its own would halve the pull.
                        downY = ev.rawY; closing = false
                        startX = ev.x; startY = ev.y
                        velocity?.recycle(); velocity = android.view.VelocityTracker.obtain().also { track(it) }
                    }
                    MotionEvent.ACTION_MOVE -> if (!closing && ev.rawY - downY > 24 * a.dp && !canScrollVertically(-1)) {
                        if (a.looks.on("pullToSearch")) {
                            if (ev.rawY - downY > 96 * a.dp) {
                                downY = Float.MAX_VALUE
                                focusSearch()
                            }
                        } else {
                            closing = true; held = true; downY = ev.rawY; stop()
                            // The list sees no more of this touch: cancel it, or its pending long press lifts an app.
                            MotionEvent.obtain(ev).also { it.action = MotionEvent.ACTION_CANCEL; onTouchEvent(it); it.recycle() }
                        }
                    }
                }
                if (!closing) return@setOnTouchListener false
                if (ev.actionMasked == MotionEvent.ACTION_MOVE) setProgress(1 - (ev.rawY - downY) / travel())
                if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                    velocity?.computeCurrentVelocity(1000)
                    release(-(velocity?.yVelocity ?: 0f), 0.7f)
                    closing = false; held = false
                }
                true
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
    private var keyboardOnOpen = false

    /** Finger travel that fully opens or closes the drawer (Launcher3's phones: 300 dp; shorter here, so it's quicker). */
    private fun travel() = 220 * a.dp

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

    /** Clears search and resets position, ready to open. */
    private fun prepare() {
        if (visible) return
        if (search.text.isNotEmpty()) search.text.clear()
        val reset = !a.looks.on("rememberPosition")
        if (reset) { tab = ""; if (styleName == "pages") pager.snapTo(0, animate = false) }
        if (inputs() != shown) refresh()
        if (reset) grid.setSelection(if (fromBottom) adapter.count - 1 else 0)
    }

    private fun opened() {
        if (keyboardOnOpen || a.looks.on("showKeyboard")) focusSearch()
        keyboardOnOpen = false
    }

    private fun gone() {
        root.alpha = 1f
        for (i in 0 until root.childCount) root.getChildAt(i).alpha = 1f
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
        if (!visible && (a.menu != null || a.overlay != null)) return true
        if (!end && !visible && up <= 0f) return false
        if (!visible) prepare()
        stop()
        held = !end
        if (end) {
            val open = release(vy, 0.3f)
            if (open) root.tick()
        } else setProgress(up / travel())
        return true
    }

    /** Ends a finger-driven pull: opens if flung up or past [threshold], unless flung down. Returns whether it opens. */
    private fun release(vy: Float, threshold: Float): Boolean {
        // Launcher3 counts anything over 1 dp/ms as a fling.
        val open = drawerOpens(progress, vy, 1000 * a.dp, threshold)
        settle(open, vy)
        return open
    }

    /** [animate] false for a drag leaving the drawer: it must be out of the way at once. */
    fun hide(animate: Boolean = true) {
        if (!visible) return
        a.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
        search.text.clear()
        search.clearFocus()
        showHidden = false
        if (animate) settle(open = false)
        else { stop(); if (a.overlay == null) a.setDepth(0f); gone() }
    }

    fun groups() = groupsFromJson(a.store.data.getString("groups", "[]")!!)

    /** What [refresh] reads; when it's unchanged, opening the drawer reuses what's already built. */
    private fun inputs() = listOf(a.apps, a.store.data.all, a.looks.all(), a.privateUser, tab, showHidden, search.text.toString())
    private var shown: List<Any?>? = null

    fun refresh() {
        shown = inputs()
        val q = search.text.toString().trim()
        val hidden = a.store.strings("hidden")
        val launches = a.launches()
        // Each key once: a comparator's selector runs on every comparison.
        val ordered = a.apps.values.map { it to SortKey(a.label(it), it.info.firstInstallTime, launches.optInt(it.key)) }
            .sortedWith(compareBy(drawerComparator(a.looks.str("drawerSort"))) { it.second }).map { it.first }
        val groups = groups()
        val work = a.apps.values.filter { a.isWork(it) }.map { it.key }.toSet()
        val workTab = a.looks.on("workTab") && work.isNotEmpty()
        val private = a.apps.values.filter { a.isPrivate(it) }.map { it.key }.toSet()
        val privateTab = a.privateUser != null
        if (tab == "work" && !workTab || tab == "private" && !privateTab ||
            tab.isNotEmpty() && tab != "work" && tab != "private" && groups.none { it.name == tab && it.type == "tab" }) tab = ""
        entries = if (q.isNotEmpty()) {
            ordered.filter { (showHidden || it.key !in hidden) && a.label(it).contains(q, ignoreCase = true) }.map { Entry.AppE(it) } +
                contacts(q) + if (a.looks.on("webSearch")) listOf(Entry.WebE(q)) else emptyList()
        } else {
            val keys = tabApps(ordered.map { it.key }, groups, tab, hidden, showHidden, a.looks.on("keepInAll"), work, workTab, private)
            val list = keys.mapNotNull { a.apps[it] }.map { Entry.AppE(it) }
            val folders = if (tab == "") groups.filter { it.type == "folder" }.map { Entry.FolderE(it) } else emptyList()
            if (a.looks.on("foldersFirst")) folders + list else list + folders
        }
        buildFrequent(if (q.isEmpty() && tab == "" && a.looks.on("frequent"))
            a.apps.values.filter { launches.optInt(it.key) > 0 && it.key !in hidden && it.key !in private }.sortedByDescending { launches.optInt(it.key) }
                .take(gridCols) else emptyList())
        // Glass and housing: search results sit just above the bottom search bar, under the thumb.
        grid.isStackFromBottom = fromBottom || bottomBar && q.isNotEmpty()
        index.visibility = if (q.isEmpty() && entries.isNotEmpty()) View.VISIBLE else View.GONE
        empty.text = a.getString(R.string.no_apps_match, q)
        empty.visibility = if (q.isNotEmpty() && entries.none { it is Entry.AppE || it is Entry.ContactE }) View.VISIBLE else View.GONE
        buildTabs(groups, workTab, privateTab, q.isEmpty())
        privateBar.visibility = if (q.isEmpty() && tab == "private") View.VISIBLE else View.GONE
        if (privateBar.visibility == View.VISIBLE) buildPrivateBar()
        if (styleName == "pages") buildPages() else adapter.notifyDataSetChanged()
    }

    /** Up to four matching contacts, when contact search is on and the user granted access. */
    private fun contacts(q: String): List<Entry> {
        if (!a.looks.on("contactSearch") || !a.granted(android.Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = ArrayList<Entry>()
        runCatching {
            a.contentResolver.query(android.net.Uri.withAppendedPath(android.provider.ContactsContract.Contacts.CONTENT_FILTER_URI, android.net.Uri.encode(q)),
                arrayOf("_id", "lookup", "display_name", "photo_thumb_uri"), null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < 4) out += Entry.ContactE(c.getString(2) ?: "", c.getString(3),
                    android.provider.ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1)))
            }
        }
        return out
    }

    /** Something it shows changed: rebuilt now if open, else on the next open. */
    fun invalidate() { if (visible) refresh() else shown = null }

    fun refreshBadges() { if (visible) grid.invalidateViews() }

    /** The tab keys in chip order, as [buildTabs] last laid them out; empty while the chips are hidden. */
    private var tabKeys = listOf<String>()

    /** A sideways swipe of [dx] px over the list: a finger moving left goes to the next tab (right in right-to-left). */
    private fun swipeTab(dx: Float) {
        if (abs(dx) < 48 * a.dp) return
        val rtl = root.layoutDirection == View.LAYOUT_DIRECTION_RTL
        swipedTab(tabKeys, tab, (dx < 0) != rtl)?.let { tab = it; refresh() }
    }

    private fun buildTabs(groups: List<Group>, workTab: Boolean, privateTab: Boolean, show: Boolean) {
        tabs.removeAllViews()
        val names = listOf("" to a.getString(R.string.tab_all)) + (if (workTab) listOf("work" to a.getString(R.string.tab_work)) else emptyList()) +
            (if (privateTab) listOf("private" to a.getString(R.string.tab_private)) else emptyList()) +
            groups.filter { it.type == "tab" }.map { it.name to it.name }
        tabScroll.visibility = if (show && names.size > 1) View.VISIBLE else View.GONE
        tabKeys = if (tabScroll.visibility == View.VISIBLE) names.map { it.first } else emptyList()
        // Keep the selected chip on screen when a swipe lands on one scrolled out of view.
        tabs.post { tabs.getChildAt(tabKeys.indexOf(tab).coerceAtLeast(0))?.let { it.requestRectangleOnScreen(android.graphics.Rect(0, 0, it.width, it.height)) } }
        // M3 filter chips: the selected one filled with the secondary container and a check mark. Housing: a raised
        // key; the others are plain legends.
        for ((key, title) in names) tabs.addView(a.text(title, 14f, if (key == tab) a.m3.onSecondaryContainer else a.m3.onSurfaceVariant, medium = true).apply {
            val on = key == tab
            gravity = Gravity.CENTER_VERTICAL
            val p = (16 * a.dp).toInt()
            setPadding(if (on && !housing) p / 2 else p, 0, p, 0)
            compoundDrawablePadding = (8 * a.dp).toInt()
            // Housing: the raised key alone marks the selected tab; the screen's signal belongs to the index or search.
            if (on && !housing) setCompoundDrawablesRelative(a.symbol("check", a.m3.onSecondaryContainer, 18f).apply { setBounds(0, 0, intrinsicWidth, intrinsicHeight) }, null, null, null)
            background = a.pressable(when {
                housing -> if (on) a.rounded(a.m3.raised, 16 * a.dp, if (a.m3.dark) 0x1FFFFFFF else 0x14000000, a.dp / 2) else null
                on -> a.rounded(a.m3.secondaryContainer, 8 * a.dp)
                else -> a.rounded(0, 8 * a.dp, a.m3.outlineVariant)
            }, if (housing) 16 * a.dp else 8 * a.dp)
            layoutParams = LinearLayout.LayoutParams(-2, (32 * a.dp).toInt()).apply { marginStart = (8 * a.dp).toInt() }
            setOnClickListener { tab = key; refresh() }
            if (key != "" && key != "work" && key != "private") setOnLongClickListener { a.editGroup(groups.first { it.name == key }); true }
        })
    }

    private fun buildFrequent(apps: List<App>) {
        frequentRow.removeAllViews()
        frequentRow.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
        if (apps.isEmpty()) return
        val side = ((if (bottomBar) 12 else 8) * a.dp).toInt()
        frequentRow.addView(LinearLayout(a).apply {
            setPaddingRelative(side, (8 * a.dp).toInt(), if (housing && a.looks.on("fastScroll")) (28 * a.dp).toInt() else side, (8 * a.dp).toInt())
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
    private fun entryView(e: Entry, recycled: View? = null, style: IconStyle = iconStyle): IconView = when (e) {
        is Entry.AppE -> ((recycled as? IconView)?.takeIf { it.tag == APP_VIEW }?.apply { bind(a.label(e.app), a.icon(e.app), e.app.pkg) }
            ?: IconView(a, a.label(e.app), a.icon(e.app), style, a.badgeStyle(), e.app.pkg).apply { tag = APP_VIEW }).also {
            if (a.looks.str("badges") != "off") a.badge(it.image, e.app.pkg)
            accessibleActions(it, { a.showPopup(it, e.app) })
        }
        is Entry.FolderE -> IconView(a, e.group.name, a.folderIcon(folderItems(e.group)), iconStyle, a.badgeStyle(), "")
        // Short label so the query survives the one-line ellipsis; the globe says "web".
        is Entry.WebE -> IconView(a, a.getString(R.string.search_web_for, e.query),GlyphIcon(a.m3.primaryContainer, a.m3.onPrimaryContainer, symbol = "globe"), iconStyle, a.badgeStyle(), "")
        is Entry.ContactE -> IconView(a, e.name, e.photo?.let { p ->
            runCatching { a.contentResolver.openInputStream(android.net.Uri.parse(p))!!.use { android.graphics.drawable.BitmapDrawable(a.resources, android.graphics.BitmapFactory.decodeStream(it)) } }.getOrNull()
        } ?: GlyphIcon(a.m3.primaryContainer, a.m3.onPrimaryContainer, text = e.name.take(1).uppercase()), iconStyle, a.badgeStyle(), "")
    }

    private fun folderItems(g: Group) = g.apps.mapNotNull { a.apps[it] }.map { Item(0, 0, 0, pkg = it.pkg, cls = it.cls, user = it.serial) }

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
                var apps = group.apps.mapNotNull { a.apps[it] }
                a.openFolderWindow(group.name, folderItems(group), null, reorder = { from, to ->
                    // Uninstalled apps' keys aren't shown: they keep their place at the end.
                    val keys = apps.map { it.key }.toMutableList().apply { add(to, removeAt(from)) }
                    group = group.copy(apps = keys + (group.apps - keys.toSet()))
                    apps = group.apps.mapNotNull { a.apps[it] }
                    a.saveGroups(groups().map { if (it.name == group.name) group else it })
                    folderItems(group)
                }, icon = (v as? IconView)?.image) { i -> GroupChild(group.name, apps[i]) }
            }
            is Entry.WebE -> a.webSearch(e.query)
            is Entry.ContactE -> runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, e.uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun longPress(e: Entry, v: View) {
        if (!settled) return
        when (e) {
            is Entry.AppE -> a.startDrag(v, e.app)
            // As an app: drag to copy it to home; its menu (Add, Edit) shows if the finger stays.
            is Entry.FolderE -> a.startDrag(v, e.group)
            is Entry.WebE, is Entry.ContactE -> {}
        }
    }

    private fun menu() {
        a.showMenu(a.screenRect(menuButton), narrow = true) {
            fun row(sym: String, title: String, act: () -> Unit) = addView(a.listItem(a.symbol(sym), title, dense = true) { a.closeMenu(); act() })
            row(if (showHidden) "hide" else "show", a.getString(if (showHidden) R.string.hidden_hide else R.string.hidden_show)) { showHidden = !showHidden; refresh() }
            row("sort", a.getString(R.string.set_drawerSort)) {
                a.dialog().setTitle(R.string.set_drawerSort).setSingleChoiceItems(SORT_NAMES.map(a::getString).toTypedArray(), SORTS.indexOf(a.looks.str("drawerSort"))) { d, i ->
                    a.looks["drawerSort"] = SORTS[i]; d.dismiss(); refresh()
                }.show()
            }
            row("tabs", a.getString(R.string.drawer_groups)) { hide(); a.groupsMenu() }
            row("hide", a.getString(R.string.hidden_apps)) { a.hideAppsMenu() }
            row("settings", a.getString(R.string.launcher_settings)) { hide(); a.showSettings() }
        }
    }

    /** The housing drawer's letter index down the right edge: the section in view is marked with the signal dot; touch or drag to jump. */
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

    private inner class Adapter : BaseAdapter(), SectionIndexer {
        private fun letter(e: Entry) = when (e) {
            is Entry.AppE -> indexLetter(a.label(e.app))
            is Entry.FolderE -> "▤"
            is Entry.WebE -> "⌕"
            is Entry.ContactE -> "☺"
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
        override fun getView(pos: Int, convert: View?, parent: ViewGroup): View = (getItem(pos)?.let { entryView(it, convert) }?.apply {
            if (styleName == "list") setPadding((8 * a.dp).toInt(), 0, 0, 0)
        } ?: entryView(entries[0]).apply { visibility = View.INVISIBLE }).apply { // a filler as tall as a real cell, or GridView misplaces the row
            if (styleName == "list") return@apply
            if (!search.hasFocus() && parent.height > 0) cellH = (parent.height - parent.paddingTop - parent.paddingBottom) / rows
            // Kept, not replaced: GridView marks the first cell it measures forceAdd, and new params lost that, so the
            // cell was never attached to the window and accessibility skipped it.
            (layoutParams as? android.widget.AbsListView.LayoutParams ?: android.widget.AbsListView.LayoutParams(-1, -2).also { layoutParams = it })
                .height = if (cellH > 0) cellH else -2
        }
        override fun getSections(): Array<String> = sections
        override fun getPositionForSection(s: Int) = entries.indexOfFirst { letter(it) == sections.getOrNull(s) }.coerceAtLeast(0) + lead
        override fun getSectionForPosition(p: Int) = sections.indexOf(getItem(p)?.let(::letter)).coerceAtLeast(0)
    }
}

