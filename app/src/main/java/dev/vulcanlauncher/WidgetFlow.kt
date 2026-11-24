package dev.vulcanlauncher

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil

/** Home-screen widgets: their views and stacks, and the pick, bind, configure and place flow. Owns the state of a widget mid-setup. */
class WidgetFlow(private val a: MainActivity) {
    /** The widget being bound or configured. Saved, because the launcher can be killed while the other app's screen is open. */
    var pendingWidget: Pair<Int, AppWidgetProviderInfo>? = null
        set(v) { field = v; a.store.data.edit().putString("pendingWidget", v?.let { "${it.first} ${it.second.provider.flattenToString()}" } ?: "").apply() }

    /** Kept across render(), so a widget isn't inflated again (and reset) whenever any item changes. */
    private val widgetFrames = HashMap<Int, WidgetFrame>()

    /** Forgets the kept views of every widget not in [ids]. */
    fun keepViews(ids: Set<Int>) { widgetFrames.keys.retainAll(ids) }

    @Suppress("DEPRECATION")
    fun widgetView(item: Item): View? {
        val info = a.awm.getAppWidgetInfo(item.widgetId) ?: return null
        // A frame that was in a stack still sits in it, moved aside or hidden.
        widgetFrames[item.widgetId]?.let { (it.parent as? ViewGroup)?.removeView(it); it.translationX = 0f; it.visibility = View.VISIBLE; return it }
        val hv: AppWidgetHostView = a.host.createView(a.applicationContext, item.widgetId, info)
        hv.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) {
                val w = ((r - l) / a.dp).toInt()
                val h = ((b - t) / a.dp).toInt()
                (v as AppWidgetHostView).updateAppWidgetSize(null, w, h, w, h)
            }
        }
        roundCorners(hv, a.looks.int("widgetRadius") * a.dp)
        return WidgetFrame(a).apply { addView(hv) }.also { widgetFrames[item.widgetId] = it }
    }

    /**
     * Rounds the card a widget draws, not its cell (#109): that card often sits inside the cell, so clipping the cell
     * missed its corners. As Launcher3's rounded-corner enforcement: the widget's own background view is rounded, and a
     * widget that already clips its background is left alone.
     */
    private fun roundCorners(hv: AppWidgetHostView, radius: Float) {
        if (radius <= 0) return
        val box = android.graphics.Rect()
        hv.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(box, radius)
        }
        // On every layout: the widget's views come and go with its updates, while the host keeps its size.
        hv.viewTreeObserver.addOnGlobalLayoutListener {
            val bg = widgetBackground(hv)
            if (bg == null || bg.id == android.R.id.background && bg.clipToOutline) { hv.clipToOutline = false; return@addOnGlobalLayoutListener }
            val was = android.graphics.Rect(box)
            box.set(0, 0, bg.width, bg.height)
            var v: View = bg
            while (v !== hv) { box.offset(v.left, v.top); v = v.parent as? View ?: return@addOnGlobalLayoutListener }
            hv.clipToOutline = true
            if (box != was) hv.invalidateOutline()
        }
    }

    /** The view that draws a widget's card: the one marked as its background, else the first view down with a background. */
    private fun widgetBackground(hv: ViewGroup): View? {
        val marked = ArrayList<View>()
        fun walk(v: View) { if (v.id == android.R.id.background) marked += v; (v as? ViewGroup)?.let { g -> repeat(g.childCount) { walk(g.getChildAt(it)) } } }
        walk(hv)
        if (marked.size == 1) return marked[0]
        var v = hv.getChildAt(0) ?: return null
        while (v.background == null && v is ViewGroup && v.childCount == 1) v = v.getChildAt(0)
        return v
    }

    /** Which widget of each stack shows, by the stack's first widget id; back to the first after a restart. */
    private val stackShown = HashMap<Int, Int>()

    /** The widget a stack shows now (a lone widget's own). */
    fun shownWidget(item: Item) = item.widgetIds.getOrElse(stackShown[item.widgetId] ?: 0) { item.widgetId }

    fun stackView(item: Item): View? {
        val shown = item.widgetIds.mapNotNull { id -> widgetView(Item(0, 0, 0, widgetId = id))?.let { id to it } }
        if (shown.size < 2) return shown.firstOrNull()?.second
        val start = shown.indexOfFirst { it.first == shownWidget(item) }.coerceAtLeast(0)
        return WidgetStack(a, shown.map { it.second }, start, a.looks.color("labelColor")) { stackShown[item.widgetId] = item.widgetIds.indexOf(shown[it].first) }
    }

    /** The same-size widget a dragged widget is over: dropped there, they stack. */
    fun stackTarget(cell: CellLayout, px: Float, py: Float, payload: Any?): Item? {
        val moving = payload as? Item
        if (!a.drag.dragMoved || moving == null || !moving.isWidget) return null
        val (fx, fy) = cell.cellAt(px, py)
        return cell.viewAt(fx, fy)?.let { a.viewItems[it] }?.takeIf { it != moving && it.isWidget && it.w == moving.w && it.h == moving.h }
    }

    /** Takes the shown widget out of its stack to the first free spot (this page first), as a widget of its own. */
    fun unstack(item: Item) {
        val id = shownWidget(item)
        val spot = firstFreeSpot(a.items.filter { !isDock(it.page) }, a.looks.pages, a.looks.cols * a.sub, a.looks.rows * a.sub, item.w, item.h, item.page)
        if (spot == null) { a.toast(R.string.no_room); return }
        a.items[a.items.indexOf(item)] = item.withWidgets(item.widgetIds - id)!!
        a.items += Item(spot.page, spot.x, spot.y, item.w, item.h, widgetId = id)
        stackShown.remove(item.widgetId)
        a.save()
        a.render()
        a.workspace.snapTo(spot.page)
    }

    /** The widget being added goes on a Now page card, not home. */
    // ponytail: in memory only, so a widget whose setup outlives the process lands on home instead
    private var widgetToNow = false

    /** [toNow]: for a Now page card. */
    fun pickWidget(pkg: String? = null, toNow: Boolean = false) {
        widgetToNow = toNow
        val order = labelOrder()
        val page = a.pages[a.workspace.current]
        val groups = a.awm.installedProviders.filter { pkg == null || it.provider.packageName == pkg }.groupBy { it.provider.packageName }
            .map { (p, list) ->
                Triple(p, runCatching { a.packageManager.getApplicationLabel(a.packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p),
                    list.map { it to it.loadLabel(a.packageManager).toString() }.sortedWith(compareBy(order) { it.second }))
            }.sortedWith(compareBy(order) { it.second })
        val flat = groups.size <= 1
        val previewH = (120 * a.dp).toInt()
        lateinit var d: android.app.AlertDialog
        // ponytail: a widget with only a previewLayout (Android 12+, no preview image) shows its app icon; inflating the
        // layout through an AppWidgetHostView would show the real thing.
        fun widgetRow(info: AppWidgetProviderInfo, label: String) = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * a.dp).toInt()
            setPadding(pad * 3 / 2, pad / 2, pad * 3 / 2, pad / 2)
            background = a.pressable(null)
            addView(ImageView(context).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_START
                maxHeight = previewH
                setImageDrawable(info.loadPreviewImage(a, 0) ?: info.loadIcon(a, 0))
            }, LinearLayout.LayoutParams(-2, -2))
            val t = info.targetCells()
            val w = t?.first ?: ceil(info.minWidth / page.cellW.toFloat() / a.sub).toInt().coerceAtLeast(1)
            val h = t?.second ?: ceil(info.minHeight / page.cellH.toFloat() / a.sub).toInt().coerceAtLeast(1)
            addView(TextView(context).apply { setTextColor(a.m3.onSurface); textSize = 14f; setPadding(0, pad / 4, 0, 0); text = "$label ($w×$h)" })
            contentDescription = "$label ($w×$h)"
            setOnClickListener { d.dismiss(); addWidget(info) }
        }
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(a).apply { addView(list) }
        var close = {}
        for ((p, app, widgets) in groups) {
            if (flat) { widgets.forEach { (info, label) -> list.addView(widgetRow(info, label)) }; continue }
            val rows by lazy { widgets.map { (info, label) -> widgetRow(info, label) } }
            val chevron = ImageView(a).apply { setImageDrawable(a.symbol("right")); if (layoutDirection == View.LAYOUT_DIRECTION_RTL) scaleX = -1f }
            val icon = a.repo.apps.values.firstOrNull { it.pkg == p }?.let(a.repo::icon) ?: runCatching { a.packageManager.getApplicationIcon(p) }.getOrNull()
            lateinit var header: View
            var open = false
            header = a.listItem(icon, app, a.resources.getQuantityString(R.plurals.widget_count, widgets.size, widgets.size), chevron, appIcon = true) {
                val opening = !open
                close()
                if (!opening) return@listItem
                open = true
                chevron.animate().rotation(90f * chevron.scaleX).setDuration(150).start()
                val at = list.indexOfChild(header) + 1
                rows.forEachIndexed { i, r -> list.addView(r, at + i) }
                close = { rows.forEach(list::removeView); chevron.animate().rotation(0f).setDuration(150).start(); open = false; close = {} }
                header.post { scroll.smoothScrollTo(0, header.top) }
            }
            list.addView(header)
        }
        d = a.sheet().setTitle(R.string.widgets).setView(scroll).create().also { it.show() }
    }

    /** The provider an imported widget's spot asks for, if its app is installed. */
    fun slotProvider(slot: Item): AppWidgetProviderInfo? = ComponentName(slot.pkg, slot.cls).let { c ->
        runCatching { a.awm.getInstalledProvidersForProfile(a.repo.userOf(slot.user)) }.getOrNull()?.firstOrNull { it.provider == c }
    }

    /** An imported widget's spot: dashed outline, the widget's app icon and name, and a tap to add it. */
    fun widgetSlotView(item: Item): View {
        val info = slotProvider(item)
        val name = info?.loadLabel(a.packageManager) ?: item.cls.substringAfterLast('.')
        val radius = maxOf(16f, a.looks.int("widgetRadius").toFloat()) * a.dp
        return LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val p = (8 * a.dp).toInt()
            setPadding(p, p, p, p)
            background = a.pressable(GradientDrawable().apply {
                cornerRadius = radius
                setColor(0x40000000)
                setStroke((1.5f * a.dp).toInt(), 0x99FFFFFF.toInt(), 6 * a.dp, 4 * a.dp)
            }, radius, Color.WHITE)
            info?.loadIcon(a, resources.displayMetrics.densityDpi)?.let { d ->
                addView(ImageView(context).apply { setImageDrawable(d) }, LinearLayout.LayoutParams((36 * a.dp).toInt(), (36 * a.dp).toInt()))
            }
            addView(a.text(name, 13f, Color.WHITE).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                setShadowLayer(4 * a.dp, 0f, a.dp, 0x66000000)
                setPadding(0, (6 * a.dp).toInt(), 0, 0)
            })
            addView(a.legend(a.getString(if (info == null) R.string.widget_slot_missing else R.string.widget_slot_add), 10f, 0xCCFFFFFF.toInt()).apply {
                maxLines = 1; gravity = Gravity.CENTER
                setShadowLayer(4 * a.dp, 0f, a.dp, 0x66000000)
            })
            contentDescription = a.getString(R.string.widget_slot_describe, name)
            setOnClickListener { restoreWidget(item) }
        }
    }

    /** An imported widget's spot while its new widget goes through Android's bind and setup screens; kept across restarts. */
    var pendingSlot: Item?
        get() = a.store.data.getString("pendingSlot", "")!!.takeIf { it.isNotEmpty() }?.let { runCatching { itemsFromJson(it).firstOrNull() }.getOrNull() }
        set(v) { a.store.data.edit().putString("pendingSlot", v?.let { itemsToJson(listOf(it)) } ?: "").apply() }

    /** Puts a new widget from the same provider into an imported widget's spot. */
    fun restoreWidget(slot: Item) {
        val info = slotProvider(slot) ?: run { a.toast(R.string.widget_slot_missing_toast); return }
        pendingSlot = slot
        addWidget(info)
    }

    /**
     * After an import: fills the widget spots one by one. Once the user allows Vulcan to add widgets ("Always allow"),
     * each binds without asking; widgets with a setup screen show it. Stops at the first "no" or cancel.
     */
    fun restoreNextWidget() {
        if (!a.store.data.getBoolean(RESTORING_WIDGETS, false)) return
        val next = a.items.firstOrNull { it.isWidgetSlot && slotKey(it) !in skippedSlots && slotProvider(it) != null }
        if (next != null) { restoreWidget(next); return }
        skippedSlots.clear()
        a.store.data.edit().remove(RESTORING_WIDGETS).apply()
        a.refreshWelcome()
    }

    /** Spots whose widget's own setup screen was backed out of during this restore: they stay "Tap to add", the rest go on. */
    private val skippedSlots = HashSet<String>()
    private fun slotKey(slot: Item) = "${slot.page} ${slot.x} ${slot.y}"

    /** Backed out of a widget's setup screen: drop that widget, keep its spot, and go on to the next one. */
    fun skipWidget() {
        pendingWidget?.let { a.host.deleteAppWidgetId(it.first) }
        pendingWidget = null
        pendingSlot?.let { skippedSlots += slotKey(it) }
        pendingSlot = null
        restoreNextWidget()
    }

    private fun addWidget(info: AppWidgetProviderInfo) {
        val id = a.host.allocateAppWidgetId()
        pendingWidget = id to info
        if (a.awm.bindAppWidgetIdIfAllowed(id, info.provider)) configureWidget()
        else a.startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider), MainActivity.REQ_BIND)
    }

    fun configureWidget() {
        val (id, info) = pendingWidget ?: return
        if (info.configure != null) a.host.startAppWidgetConfigureActivityForResult(a, id, 0, MainActivity.REQ_CONFIG, null)
        else placeWidget()
    }

    fun placeWidget() {
        val (id, info) = pendingWidget ?: return
        val page = a.pages[a.workspace.current]
        // After a restart the result arrives before the first layout; with no cell size yet the widget would fill the page.
        if (page.width == 0) {
            page.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                    v.removeOnLayoutChangeListener(this)
                    // After this layout pass: views added during it (the widget, and render()'s dock) were never laid out.
                    v.post { placeWidget() }
                }
            })
            return
        }
        pendingWidget = null
        if (widgetToNow) {
            widgetToNow = false
            a.saveNowCards(a.nowCards() + Item(0, 0, 0, widgetId = id))
            if (a.overlay is NowPanel) a.reopenNow()
            return
        }
        pendingSlot?.let { slot ->
            pendingSlot = null
            val i = a.items.indexOfFirst { it.isWidgetSlot && it.page == slot.page && it.x == slot.x && it.y == slot.y }
            if (i < 0) a.host.deleteAppWidgetId(id)
            else { a.items[i] = Item(slot.page, slot.x, slot.y, slot.w, slot.h, widgetId = id); a.save(); a.render() }
            restoreNextWidget()
            return
        }
        val t = info.targetCells()
        val w = (t?.first?.times(a.sub) ?: ceil(info.minWidth / page.cellW.toFloat()).toInt()).coerceIn(1, page.cols)
        val h = (t?.second?.times(a.sub) ?: ceil(info.minHeight / page.cellH.toFloat()).toInt()).coerceIn(1, page.rows)
        if (!a.addItem(Item(a.workspace.current, 0, 0, w, h, widgetId = id), quiet = true)) {
            a.host.deleteAppWidgetId(id)
            a.toast(R.string.no_room)
        }
    }

    fun cancelWidget() {
        pendingWidget?.let { a.host.deleteAppWidgetId(it.first) }
        pendingWidget = null
        pendingSlot = null
        a.store.data.edit().remove(RESTORING_WIDGETS).apply()
        a.refreshWelcome()
    }

    fun resizeWidget(item: Item) {
        val v = a.views[item] ?: return
        val cell = v.parent as? CellLayout ?: return
        // The clock: any size from two cells wide and one tall. A widget that is gone has nothing to resize.
        if (!item.isClock && a.awm.getAppWidgetInfo(item.widgetId) == null) return
        // As Nova, every widget resizes both ways and down to a single cell, whatever its resizeMode and minResize
        // say (#74, #115): the grid's half cells are finer than a widget declares, and the user sees what still fits.
        val minW = if (item.isClock) minOf(2 * a.sub, item.w) else 1
        val minH = if (item.isClock) minOf(a.sub, item.h) else 1
        if (a.touchExploring) resizeByList(cell, item, v, minW, minH, true, true)
        else a.showResize(cell, item, v, minW, minH, true, true)
    }

    /** TalkBack can't drag the resize handles: the sizes that fit from the widget's top corner, as a list. */
    private fun resizeByList(cell: CellLayout, item: Item, v: View, minW: Int, minH: Int, canW: Boolean, canH: Boolean) {
        val sizes = widgetSizes(item.x, item.y, item.w, item.h, minW, minH, cell.cols, cell.rows, canW, canH)
            .filter { (w, h) -> a.looks.on("widgetOverlap") || cell.isFree(item.x, item.y, w, h, ignore = v) }
            // Subgrid: whole-cell steps only, or the list runs to a hundred entries.
            .filter { (w, h) -> (w == item.w || w % a.sub == 0) && (h == item.h || h % a.sub == 0) }
        fun cells(n: Int) = if (n % a.sub == 0) "${n / a.sub}" else "${n / a.sub}½"
        a.sheet().setTitle(R.string.resize).setItems(sizes.map { (w, h) -> "${cells(w)} × ${cells(h)}".let { if (w == item.w && h == item.h) a.getString(R.string.resize_current, it) else it } }
            .toTypedArray()) { _, i ->
            val (w, h) = sizes[i]
            a.items[a.items.indexOf(item)] = item.copy(w = w, h = h)
            a.save()
            a.render()
        }.show()
    }

    /** After a restore: widget IDs belong to the install that made them; drop the ones this install doesn't own. */
    fun dropForeignWidgets() {
        val s = a.looks.sub // the restored setting; load converts the restored layout to it
        a.store.save(keepWidgets(a.store.load(s)) { a.awm.getAppWidgetInfo(it) != null }, s)
    }
}
