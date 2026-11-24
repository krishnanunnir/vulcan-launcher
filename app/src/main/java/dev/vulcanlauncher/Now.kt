package dev.vulcanlauncher

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// The Now page: feed cards that work offline (date, media controls, calendar, contacts, frequent apps).
// Weather, news and Google Discover need the network and aren't here.

fun VulcanActivity.granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

/** The locale's own pattern for a date skeleton ("EEEEdMMMM" is "Monday, 19 October" in English, "Montag, 19. Oktober" in German). */
private fun datePattern(skeleton: String) = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)

/** The Now page's cards, in their default order. The date and time always lead, above them. */
val NOW_CARDS = listOf("media", "calendar", "contacts", "apps")

/** The card order saved as "a,b,c" over the built-in and [custom] cards: unknown ids dropped, cards it doesn't name (added since) at the end. */
fun nowOrder(saved: String?, custom: List<String> = emptyList()): List<String> =
    (NOW_CARDS + custom).let { all -> (saved.orEmpty().split(',').filter { it in all } + all).distinct() }

/** data key: the Now page's own cards, as items: one widget, or an item holding a row of apps as its children. */
const val NOW_CUSTOM = "nowCustom"

/** A card's id in the card order. Cards aren't edited, only added and removed, so their content names them. */
fun nowCardId(card: Item) = if (card.isWidget) "w${card.widgetId}" else "a" + card.children.joinToString(",") { it.appKey }.hashCode()

fun MainActivity.nowCards(): List<Item> = runCatching { itemsFromJson(store.data.getString(NOW_CUSTOM, null) ?: "[]") }.getOrDefault(emptyList())
fun MainActivity.saveNowCards(cards: List<Item>) = store.data.edit().putString(NOW_CUSTOM, itemsToJson(cards)).apply()

/** A widget card's height is saved as its item's h, in steps of this many dp. 1, the default, is the widget's own height. */
const val NOW_CARD_STEP = 8

/** The least height of a widget card, in dp, when the widget doesn't resize vertically. */
const val NOW_CARD_MIN = 48

/** The steps of a widget card [dp] tall: the nearest, and never under [minDp]. */
fun nowCardSteps(dp: Float, minDp: Int) = maxOf((dp / NOW_CARD_STEP).roundToInt(), (minDp + NOW_CARD_STEP - 1) / NOW_CARD_STEP)

/** Adds a card of apps picked from a list, or of a widget (through the widget picker, see placeWidget). */
fun MainActivity.addNowCard() {
    sheet().setTitle(R.string.now_add_card).setItems(arrayOf(getString(R.string.now_card_apps), getString(R.string.now_card_widget))) { _, which ->
        if (which == 1) { widgetFlow.pickWidget(toNow = true); return@setItems }
        // As Nova's new drawer tab: name it, then tick its apps in the same list (icons, search) drawer groups use.
        askName(getString(R.string.now_card_apps), "") { name ->
            appSwitches(name, emptyList()) { keys ->
                val picked = keys.mapNotNull(repo.apps::get).map { Item(0, 0, 0, pkg = it.pkg, cls = it.cls, user = it.serial) }
                val card = Item(0, 0, 0, children = picked, label = name)
                // A card is named by its apps (nowCardId): a second one of the same apps would show once and be removed twice.
                if (picked.isNotEmpty() && nowCards().none { nowCardId(it) == nowCardId(card) }) { saveNowCards(nowCards() + card); reopenNow() }
            }
        }
    }.show()
}

/** [all] with the [shown] items (a subset, in [all]'s order) reordered, the one at [from] moved to [to]; the rest keep their places. */
fun <T> reorderWithin(all: List<T>, shown: List<T>, from: Int, to: Int): List<T> {
    val order = moveItem(shown, from, to).iterator()
    val set = shown.toSet()
    return all.map { if (it in set) order.next() else it }
}

/** data key: the Now page's card order. */
const val NOW_ORDER = "nowOrder"

@SuppressLint("SetTextI18n", "ClickableViewAccessibility")
fun MainActivity.showNow(slide: Boolean = true) {
    drawer.hide()
    val pad = (16 * dp).toInt()
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, insets.bottom + pad) }
    // M3 filled cards on a translucent surface.
    /** Cards of your own, to their menu: held and let go without dragging, they offer Resize (widgets) and Remove, as Nova's widgets. */
    val menus = HashMap<View, List<Triple<String, String, () -> Unit>>>()
    fun card(title: String, body: View, onRemove: (() -> Unit)? = null, onResize: (() -> Unit)? = null): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(m3.surfaceContainer, 24 * dp)
        setPadding(pad + pad / 4, pad, pad + pad / 4, pad + pad / 4)
        addView(text(title, 14f, m3.primary, medium = true).apply { setPadding(0, 0, 0, pad * 3 / 4) })
        addView(body)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = pad * 3 / 4 }
        if (onRemove != null) menus[this] = listOfNotNull(onResize?.let { Triple("resize", getString(R.string.resize), it) },
            Triple("close", getString(R.string.remove), onRemove))
    }
    fun text(s: CharSequence, size: Float = 15f) = text(s, size, m3.onSurface)
    fun action(label: String, onClick: () -> Unit) = LinearLayout(this).apply { addView(tonalButton(label, onClick)) }
    fun ask(permission: String, why: String) = action(why) { requestPermissions(arrayOf(permission), MainActivity.REQ_PERMISSION) }

    if (looks.on("nowClock")) list.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad / 2, pad, 0, pad * 3 / 2)
        addView(TextClock(context).apply {
            format12Hour = "h:mm"; format24Hour = "HH:mm"; textSize = 57f; setTextColor(m3.onSurface)
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        })
        addView(TextClock(context).apply { format12Hour = datePattern("EEEEdMMMM"); format24Hour = format12Hour; textSize = 16f; setTextColor(m3.onSurfaceVariant) })
    })

    // As Nova's "show when media is playing": a card with nothing to show stays away, so the page keeps only what's live.
    fun media(): View? {
        if (!looks.on("nowMedia")) return null
        val sessions = runCatching {
            getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(this, BadgeService::class.java))
        }
        val c = sessions.getOrNull()?.firstOrNull()
        return card(getString(R.string.now_media), when {
            sessions.isFailure -> action(getString(R.string.now_notification_access)) {
                startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            c == null -> return null
            else -> LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                val md = c.metadata
                md?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { art ->
                    addView(ImageView(context).apply {
                        setImageBitmap(art); scaleType = ImageView.ScaleType.CENTER_CROP
                        background = rounded(m3.surfaceHigh, 12 * dp); clipToOutline = true
                    },
                        LinearLayout.LayoutParams((56 * dp).toInt(), (56 * dp).toInt()).apply { marginEnd = pad })
                }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: getString(R.string.now_playing), 16f).apply { maxLines = 1 })
                    addView(text(md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "", 14f).apply { maxLines = 1; setTextColor(m3.onSurfaceVariant) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
                addView(iconButton("prev", getString(R.string.media_previous), m3.onSurfaceVariant) { c.transportControls.skipToPrevious() })
                // The play/pause button is the card's filled primary action.
                addView(ImageView(context).apply {
                    // The player reports its new state later than the tap, so the button follows the tap, not the player.
                    var on = playing
                    fun show() {
                        setImageDrawable(symbol(if (on) "pause" else "play", m3.onPrimary))
                        contentDescription = getString(if (on) R.string.media_pause else R.string.media_play)
                    }
                    show()
                    scaleType = ImageView.ScaleType.CENTER
                    background = pressable(rounded(m3.primary, 24 * dp), 24 * dp, m3.onPrimary)
                    setOnClickListener { if (on) c.transportControls.pause() else c.transportControls.play(); on = !on; show() }
                }, LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt()))
                addView(iconButton("next", getString(R.string.media_next), m3.onSurfaceVariant) { c.transportControls.skipToNext() })
                setOnClickListener { c.sessionActivity?.let { runCatching { it.send() } } }
            }
        })
    }

    fun calendar(): View? {
        if (!looks.on("nowCalendar")) return null
        if (!granted(Manifest.permission.READ_CALENDAR)) return card(getString(R.string.now_upcoming), ask(Manifest.permission.READ_CALENDAR, getString(R.string.now_allow_calendar)))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now); ContentUris.appendId(it, now + 7 * 86_400_000L)
        }.build()
        val fmt = SimpleDateFormat(datePattern("EEEjmm"), Locale.getDefault())
        // All-day events are stored as UTC midnights, so they're compared and shown in UTC.
        val dayFmt = SimpleDateFormat("EEE", Locale.getDefault()).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val todayUtc = java.util.Calendar.getInstance().let { l ->
            java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                clear(); set(l.get(java.util.Calendar.YEAR), l.get(java.util.Calendar.MONTH), l.get(java.util.Calendar.DAY_OF_MONTH))
            }.timeInMillis
        }
        runCatching {
            contentResolver.query(uri, arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY),
                null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext() && body.childCount < 5) {
                    val id = c.getLong(0)
                    val allDay = c.getInt(4) == 1
                    if (allDay && c.getLong(3) <= todayUtc) continue // ended before today
                    val whenText = if (allDay) getString(R.string.now_all_day, dayFmt.format(Date(c.getLong(2)))) else fmt.format(Date(c.getLong(2)))
                    body.addView(LinearLayout(this).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, pad / 3, 0, pad / 3)
                        background = pressable(null, 8 * dp)
                        addView(View(context).apply { background = rounded(m3.primary, 2 * dp) }, LinearLayout.LayoutParams((4 * dp).toInt(), (36 * dp).toInt()).apply { marginEnd = pad * 3 / 4 })
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(text(c.getString(1) ?: "", 16f).apply { maxLines = 1 })
                            addView(text(whenText, 14f).apply { setTextColor(m3.onSurfaceVariant) })
                        })
                        setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id))) } }
                    })
                }
            }
        }
        if (body.childCount == 0) return null
        return card(getString(R.string.now_upcoming), body)
    }

    fun contacts(): View? {
        if (!looks.on("nowContacts")) return null
        if (!granted(Manifest.permission.READ_CONTACTS)) return card(getString(R.string.now_favourites), ask(Manifest.permission.READ_CONTACTS, getString(R.string.now_allow_contacts)))
        val row = LinearLayout(this)
        runCatching {
            contentResolver.query(ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI),
                "${ContactsContract.Contacts.STARRED}=1", null, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)?.use { c ->
                while (c.moveToNext() && row.childCount < 12) {
                    val look = ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1))
                    row.addView(contactView(c.getString(2) ?: "", c.getString(3)) { startActivity(Intent(Intent.ACTION_VIEW, look)) })
                }
            }
        }
        if (row.childCount == 0) return null
        return card(getString(R.string.now_favourites), HorizontalScrollView(this).apply { addView(row) })
    }

    fun apps(): View? {
        if (!looks.on("nowApps")) return null
        val counts = launches()
        val top = repo.apps.values.filter { counts.optInt(it.key) > 0 }.sortedByDescending { counts.optInt(it.key) }.take(8)
        if (top.isEmpty()) return null
        return card(getString(R.string.now_frequent), LinearLayout(this).apply {
            val st = style("").let { IconStyle(it.sizePx * 3 / 4, false, it.labelSize, panelText, false, false) }
            top.forEach { app -> addView(IconView(context, repo.label(app), repo.icon(app), st, badgeStyle(), repo.badgeKey(app.pkg, app.serial)).apply { setOnClickListener { v -> launch(app, v) } },
                LinearLayout.LayoutParams(0, (st.sizePx + 16 * dp).toInt(), 1f)) }
        })
    }

    // A widget card whose app was uninstalled goes.
    val cards = nowCards().filter { !it.isWidget || awm.getAppWidgetInfo(it.widgetId) != null }.also { if (it.size != nowCards().size) saveNowCards(it) }
    fun custom(c: Item): View? {
        // Picked from the card's menu, so no second question: as Nova's, and as a home widget's Remove.
        val remove = {
            saveNowCards(nowCards().filter { nowCardId(it) != nowCardId(c) })
            if (c.isWidget) host.deleteAppWidgetId(c.widgetId)
            reopenNow()
        }
        if (c.isWidget) {
            val info = awm.getAppWidgetInfo(c.widgetId) ?: return null
            val frame = widgetFlow.widgetView(c) ?: return null
            frame.layoutParams = LinearLayout.LayoutParams(-1, if (c.h > 1) (c.h * NOW_CARD_STEP * dp).toInt() else maxOf(info.minHeight, (64 * dp).toInt()))
            return card(info.loadLabel(packageManager), frame, remove) { resizeNowCard(c, info, frame) }
        }
        val shownApps = c.children.mapNotNull { child -> resolve(child)?.let { child to it } }
        if (shownApps.isEmpty()) return null
        // Labels scaled with the icons: at full size, six to a row ran into each other.
        val st = style("").let { IconStyle(it.sizePx * 3 / 4, true, it.labelSize * 3 / 4, panelText, false, false) }
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Six to a row, as the frequent apps card; a short last row keeps the same spacing.
            shownApps.chunked(6).forEach { row ->
                addView(LinearLayout(context).apply {
                    row.forEach { (child, li) ->
                        addView(IconView(context, li.first, li.second, st, badgeStyle(), repo.badgeKey(child.pkg, child.user)).apply { setOnClickListener { v -> open(child, v) } },
                            LinearLayout.LayoutParams(0, (st.sizePx + 40 * dp).toInt(), 1f))
                    }
                    repeat(6 - row.size) { addView(View(context), LinearLayout.LayoutParams(0, 0, 1f)) }
                })
            }
        }
        // Cards from before they were named take their first app's name.
        return card(c.label.ifEmpty { shownApps[0].second.first.toString() }, rows, remove)
    }

    val order = nowOrder(store.data.getString(NOW_ORDER, null), cards.map(::nowCardId))
    val shown = order.mapNotNull { id ->
        when (id) {
            "media" -> media(); "calendar" -> calendar(); "contacts" -> contacts(); "apps" -> apps()
            else -> cards.firstOrNull { nowCardId(it) == id }?.let(::custom)
        }?.let { id to it }
    }
    shown.forEach { list.addView(it.second) }
    list.addView(action(getString(R.string.now_add_card)) { addNowCard() }.apply { gravity = Gravity.CENTER_HORIZONTAL })
    // Hold a card (its title or any part that isn't a button) and drag it to a new place; the order is kept.
    val reorder = Reorder(this, shown.map { it.second }) { from, to ->
        store.data.edit().putString(NOW_ORDER, reorderWithin(order, shown.map { it.first }, from, to).joinToString(",")).apply()
        reopenNow()
    }
    val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
    for ((_, v) in shown) {
        var downY = 0f
        var menuOnUp = false
        val hold = View.OnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = ev.rawY; menuOnUp = false }
                MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(ev.rawY - downY) > slop) menuOnUp = false
                MotionEvent.ACTION_UP -> if (menuOnUp) {
                    menuOnUp = false
                    menus[v]?.let { m -> homeMenus.showMenu(homeMenus.screenRect(v), narrow = true) { with(homeMenus) { addActions(m, 4, emptySet()) } } }
                }
            }
            reorder.onTouch(ev)
        }
        val start = View.OnLongClickListener { menuOnUp = v in menus; if (shown.size > 1) reorder.begin(v, downY); shown.size > 1 || menuOnUp }
        listOf(v, (v as LinearLayout).getChildAt(0)).forEach { it.setOnTouchListener(hold); it.setOnLongClickListener(start) }
        // Holding the widget itself, the obvious place, opens the same menu (#105): the widget takes the DOWN, so its
        // frame's long-press is the start, and the frame gets the rest of the touch once it has fired.
        (v.getChildAt(1) as? WidgetFrame)?.let { f ->
            f.setOnTouchListener(hold)
            f.setOnLongClickListener { downY = f.downRawY; start.onLongClick(it) }
        }
    }

    val panel = NowPanel(this).apply {
        // Nearly opaque: home's icons showing through made the cards hard to read.
        setBackgroundColor(m3.surface and 0xFFFFFF or 0xF5000000.toInt())
        isClickable = true
        // Clipped below the status bar, so the big clock doesn't scroll up under its icons.
        addView(ScrollView(context).apply { setPadding(0, insets.top, 0, 0); addView(list) })
    }
    showOverlay(panel) { content.translationX = 0f; content.alpha = 1f }
    // Back slides it out the way it came.
    overlayExit = { done -> panel.settle(false, done) }
    // Slides in from the left, the way the swipe right that opened it points; a live pull (pullNow) moves it itself.
    panel.progress = 0f
    if (slide) panel.settle(true)
}

/** Builds the open Now page again in place (after play/pause or a card moved), keeping it open. */
fun MainActivity.reopenNow() {
    overlayExit = null; closeOverlay(); showNow(slide = false)
    (overlay as? NowPanel)?.progress = 1f
}

/** Tags a widget card's resize handle, so Resize again puts it away instead of adding another. */
private const val RESIZE_HANDLE = "nowResizeHandle"

/**
 * A widget card's height ([info]'s widget in [frame]): a handle under it that drags its bottom edge, saved in
 * [NOW_CARD_STEP]s when let go. TalkBack can't drag it: the heights, as a list.
 */
@SuppressLint("ClickableViewAccessibility")
private fun MainActivity.resizeNowCard(c: Item, info: android.appwidget.AppWidgetProviderInfo, frame: View) {
    val card = frame.parent as? LinearLayout ?: return
    card.findViewWithTag<View>(RESIZE_HANDLE)?.let { card.removeView(it); return }
    // As home: a widget that resizes vertically keeps its minResizeHeight; one that doesn't can shrink to NOW_CARD_MIN.
    val vertical = info.resizeMode and android.appwidget.AppWidgetProviderInfo.RESIZE_VERTICAL != 0
    val min = maxOf(NOW_CARD_MIN, if (vertical) ((info.minResizeHeight.takeIf { it > 0 } ?: info.minHeight) / dp).roundToInt() else 0)
    val save = { steps: Int -> saveNowCards(nowCards().map { if (nowCardId(it) == nowCardId(c)) it.copy(h = steps) else it }) }
    if (touchExploring) {
        val now = nowCardSteps(frame.height / dp, min)
        // Twelve heights 48dp apart from the least, and the current one.
        val first = nowCardSteps(0f, min)
        val sizes = (List(12) { first + it * 48 / NOW_CARD_STEP } + now).distinct().sorted()
        val names = sizes.map { steps ->
            val s = getString(R.string.now_card_height, steps * NOW_CARD_STEP)
            if (steps == now) getString(R.string.resize_current, s) else s
        }
        sheet().setTitle(R.string.resize).setItems(names.toTypedArray()) { _, i -> save(sizes[i]); reopenNow() }.show()
        return
    }
    var downY = 0f
    var start = 0
    var steps = -1
    card.addView(FrameLayout(this).apply {
        tag = RESIZE_HANDLE
        contentDescription = getString(R.string.resize)
        addView(View(context).apply { background = rounded(m3.onSurfaceVariant, 2 * dp) },
            FrameLayout.LayoutParams((32 * dp).toInt(), (4 * dp).toInt(), Gravity.CENTER))
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                // The page scrolls, and closes on a sideways swipe: neither while the edge is held.
                MotionEvent.ACTION_DOWN -> { downY = ev.rawY; start = frame.height; v.parent.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> {
                    steps = nowCardSteps((start + ev.rawY - downY) / dp, min)
                    frame.layoutParams.height = (steps * NOW_CARD_STEP * dp).toInt(); frame.requestLayout()
                }
                // Removed once the touch is through: removing a view while it's handling the touch crashes.
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { card.post { card.removeView(v) }; if (steps > 0) save(steps) }
            }
            true
        }
    }, LinearLayout.LayoutParams(-1, (32 * dp).toInt()))
}

/**
 * Swipe right on the first page: the Now page follows the finger in, as Launcher3's -1 screen does. [px]: pulled so
 * far; on [end] it opens when flung right, or let go past a quarter of the width without a fling back.
 */
fun MainActivity.pullNow(px: Float, vx: Float, end: Boolean): Boolean {
    if (!looks.on("nowPage")) return false
    val panel = overlay as? NowPanel ?: if (end) return true else { showNow(slide = false); overlay as NowPanel }
    val w = maxOf(1, root.width).toFloat()
    val fling = 500 * dp
    if (!end) panel.progress = minOf(1f, px / w)
    else panel.settle(vx > fling || vx > -fling && px > w / 4)
    return true
}

/**
 * The Now page's frame: a leftward swipe anywhere (even over the scrolling cards) drags the page off to the left,
 * home sharpening behind it; let go past a third of the width, or fling, and it closes, otherwise it springs back.
 */
@SuppressLint("ViewConstructor")
class NowPanel(private val a: MainActivity) : FrameLayout(a) {
    private var anim: android.animation.ValueAnimator? = null

    /** 0: off to the left, 1: open. Home sinks back, shifts a little right and fades out with it. */
    var progress = 1f
        set(p) {
            field = p
            val w = a.root.width
            translationX = (p - 1) * w
            a.setDepth(p)
            a.content.translationX = p * w * 0.2f
            // Home fades with the slide, so no shifted ghost of it shows through the panel.
            a.content.alpha = 1 - p
        }

    /** Animates to open or closed (then removes the page, or calls [done] when it's already leaving through Back). */
    fun settle(open: Boolean, done: (() -> Unit)? = null) {
        anim?.cancel()
        anim = android.animation.ValueAnimator.ofFloat(progress, if (open) 1f else 0f).apply {
            duration = (120 + 200 * kotlin.math.abs((if (open) 1f else 0f) - progress)).toLong()
            interpolator = EASE
            addUpdateListener { progress = it.animatedValue as Float }
            if (!open) addListener(object : android.animation.AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled) return
                    if (done != null) done() else if (a.overlay === this@NowPanel) { a.overlayExit = null; a.closeOverlay() }
                }
            })
            start()
        }
    }

    private val slop = android.view.ViewConfiguration.get(a).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var velocity: android.view.VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; anim?.cancel() }
            MotionEvent.ACTION_MOVE -> {
                val dx = downX - ev.rawX
                if (dx > slop && dx > 2 * kotlin.math.abs(ev.rawY - downY)) {
                    downX = ev.rawX // follow from here, without jumping the slop
                    velocity = android.view.VelocityTracker.obtain()
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val v = velocity ?: return super.onTouchEvent(ev)
        v.addMovement(ev)
        val x = minOf(0f, ev.rawX - downX)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> progress = 1 + x / maxOf(1, width)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.computeCurrentVelocity(1000)
                val fling = 1000 * a.dp
                val close = ev.actionMasked == MotionEvent.ACTION_UP && (v.xVelocity < -fling || v.xVelocity < fling && -x > width / 3f)
                v.recycle(); velocity = null
                settle(!close)
            }
        }
        return true
    }
}

private fun MainActivity.contactView(name: String, photo: String?, onClick: () -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_HORIZONTAL
    val s = (52 * dp).toInt()
    setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), 0)
    addView(if (photo != null) ImageView(context).apply {
        setImageURI(Uri.parse(photo))
        clipToOutline = true
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(m3.primaryContainer) }
    } else TextView(context).apply {
        text = name.take(1).uppercase()
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(m3.onPrimaryContainer)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(m3.primaryContainer) }
    }, LinearLayout.LayoutParams(s, s))
    addView(text(name.substringBefore(" "), 12f, m3.onSurfaceVariant).apply { maxLines = 1; setPadding(0, (6 * dp).toInt(), 0, 0) })
    setOnClickListener { onClick() }
}

// ---- Clock: a home item showing the time, date and one line of status ----

/**
 * The clock item's view. Appliance: a large light clock over a printed legend (date · status); otherwise the date over
 * a status line ("glanceStyle" picks either). The big clock grows with the item, like a clock widget. The date or
 * clock opens the calendar or clock; the status line is filled by [updateGlance]. A long-press anywhere on it is the
 * item's own (move, resize, remove).
 */
fun MainActivity.clockView() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.BOTTOM
    val big = when (looks.str("glanceStyle")) { "clock" -> true; "date" -> false; else -> m3.housing }
    tag = big
    val box = this
    val hold = View.OnLongClickListener { box.performLongClick() }
    setPadding((16 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
    val color = looks.color("labelColor")
    fun TextView.look() = apply { if (looks.on("labelShadow")) setShadowLayer(4 * dp, 0f, dp, 0x66000000) }
    val calendar = { runCatching { startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; Unit }
    if (big) {
        addView(TextClock(context).apply {
            format12Hour = "h:mm"; format24Hour = "HH:mm"
            setAutoSizeTextTypeUniformWithConfiguration(20, 200, 2, android.util.TypedValue.COMPLEX_UNIT_SP)
            gravity = Gravity.START or Gravity.BOTTOM
            maxLines = 1
            letterSpacing = -0.02f
            includeFontPadding = false
            setTextColor(color)
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            look()
            setOnClickListener { runCatching { startActivity(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            setOnLongClickListener(hold)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    // Appliance: date and status on one printed legend line ("MON 19 OCT · CHARGED"); otherwise two lines.
    val legend = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    legend.addView(TextClock(context).apply {
        if (big) {
            format12Hour = datePattern("EEEdMMM"); format24Hour = format12Hour
            textSize = 13f
            letterSpacing = 0.12f
            isAllCaps = true
            setPadding(0, (6 * dp).toInt(), 0, 0)
        } else {
            format12Hour = datePattern("EEEEdMMMM"); format24Hour = format12Hour
            textSize = 22f
        }
        maxLines = 1
        setTextColor(color)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        look()
        setOnClickListener { calendar() }
        setOnLongClickListener(hold)
    }, LinearLayout.LayoutParams(-2, -2))
    if (big) addView(legend, LinearLayout.LayoutParams(-2, -2)) else addView(legend.getChildAt(0).also { legend.removeAllViews() })
    (if (big) legend else this).addView(TextView(context).apply {
        tag = "status"
        textSize = if (big) 13f else 15f
        if (big) {
            letterSpacing = 0.12f; isAllCaps = true
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(0, (6 * dp).toInt(), 0, 0)
        } else setPadding(0, (2 * dp).toInt(), 0, 0)
        setTextColor(if (big) color else color and 0xFFFFFF or 0xD9000000.toInt())
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        look()
        setOnLongClickListener(hold)
    }, LinearLayout.LayoutParams(-2, -2))
}

/** The clock views on home, from [MainActivity.views]. */
val MainActivity.clocks get() = views.filterKeys { it.isClock }.values

/** Refreshes the glance status line: what's playing, else the next event today, else charging, else nothing. */
fun MainActivity.updateGlance() {
    if (clocks.isEmpty()) return
    val (text, tap) = glanceStatus() ?: (null to null)
    for (c in clocks) {
        val line = c.findViewWithTag<TextView>("status") ?: continue
        line.text = if (text != null && c.tag == true) " · $text" else text
        line.visibility = if (text == null) View.GONE else View.VISIBLE
        line.setOnClickListener(tap?.let { f -> View.OnClickListener { f() } })
    }
}

private fun MainActivity.glanceStatus(): Pair<String, (() -> Unit)?>? {
    // Media sessions are readable only with notification access (the badge service).
    runCatching {
        getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(this, BadgeService::class.java))
            .firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
    }.getOrNull()?.let { c ->
        val md = c.metadata
        val what = listOfNotNull(md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE), md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST))
            .filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { getString(R.string.now_playing) }
        return "♪  $what" to { c.sessionActivity?.let { runCatching { it.send() } }; Unit }
    }
    if (granted(Manifest.permission.READ_CALENDAR)) runCatching {
        val now = System.currentTimeMillis()
        val endOfDay = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 23); set(java.util.Calendar.MINUTE, 59); set(java.util.Calendar.SECOND, 59)
        }.timeInMillis
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, now); ContentUris.appendId(it, endOfDay) }.build()
        contentResolver.query(uri, arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN),
            "${CalendarContract.Instances.ALL_DAY}=0 AND ${CalendarContract.Instances.END}>?", arrayOf(now.toString()),
            "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                val at = if (c.getLong(2) <= now) getString(R.string.now_now) else android.text.format.DateFormat.getTimeFormat(this).format(Date(c.getLong(2)))
                return "$at  ${c.getString(1) ?: ""}" to {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    Unit
                }
            }
        }
    }
    val b = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) * 100 / maxOf(1, b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100))
    return when (b.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)) {
        android.os.BatteryManager.BATTERY_STATUS_CHARGING -> getString(R.string.charging, level) to null
        android.os.BatteryManager.BATTERY_STATUS_FULL -> getString(R.string.charged) to null
        else -> null
    }
}
