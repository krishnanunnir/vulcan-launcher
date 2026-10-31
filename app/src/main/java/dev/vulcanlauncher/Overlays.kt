package dev.vulcanlauncher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Page indicator under the home pages: signal dots, dots or a sliding line. */
class Indicator(context: Context, private val count: Int, private val style: String, private val color: Int, private val signal: Int = color) : View(context) {
    var position = 0f
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = context.resources.displayMetrics.density
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        if (count < 2) return
        val cy = height / 2f
        if (style == "line") {
            val seg = 24 * d
            val left = (width - seg * count) / 2
            paint.color = color and 0x55FFFFFF
            canvas.drawRoundRect(rect.apply { set(left, cy - d, left + seg * count, cy + d) }, d, d, paint)
            paint.color = color
            canvas.drawRoundRect(rect.apply { set(left + seg * position, cy - 1.5f * d, left + seg * (position + 1), cy + 1.5f * d) }, d, d, paint)
            return
        }
        if (style == "plain" || style == "signal") {
            // A row of equal dots like a speaker grille; the current page's dot lights up: in full label colour, or in
            // the signal colour. A soft
            // shadow under each keeps them crisp on any wallpaper, as it does for the labels.
            val r = 3 * d
            val step = 14 * d
            val left = width / 2f - step * (count - 1) / 2
            paint.setShadowLayer(2 * d, 0f, 0.75f * d, 0x4D000000)
            for (i in 0 until count) {
                val near = (1 - kotlin.math.abs(position - i)).coerceIn(0f, 1f)
                paint.color = blend(color and 0xFFFFFF or 0x80000000.toInt(), if (style == "plain") color else signal, near)
                canvas.drawCircle(left + step * i, cy, r, paint)
            }
            paint.clearShadowLayer()
            return
        }
        // M3-style dots: the current page stretches into a pill.
        val dot = 6 * d
        val gap = 6 * d
        val widths = List(count) { i -> dot + 12 * d * (1 - kotlin.math.abs(position - i)).coerceIn(0f, 1f) }
        var x = (width - widths.sum() - gap * (count - 1)) / 2
        for (i in 0 until count) {
            val near = (widths[i] - dot) / (12 * d)
            paint.color = color
            paint.alpha = (110 + 145 * near).toInt()
            canvas.drawRoundRect(RectF(x, cy - dot / 2, x + widths[i], cy + dot / 2), dot / 2, dot / 2, paint)
            x += widths[i] + gap
        }
    }
}

/**
 * Search bar on the home screen, an Appliance part: a raised housing pill with a machined rim, the glyph and label
 * printed on it, and the mic as a key sunk in a round well. Tap opens drawer search, the mic starts voice search.
 */
fun MainActivity.searchBar(): View {
    val system = looks.str("searchColor") == "system"
    val color = themed("searchColor", m3.surface)
    val light = Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114 > 150
    val ink = if (system) m3.onSurface else if (light) 0xFF2A2A28.toInt() else Color.WHITE
    val ink2 = if (system) m3.onSurfaceVariant else ink and 0xFFFFFF or 0xB3000000.toInt()
    val height = 52 * dp
    val radius = when (looks.str("searchStyle")) { "rect" -> 0f; "rounded" -> 14 * dp; else -> height / 2 }
    val key = 40 * dp
    val pill = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        housing(this, radius, color)
        elevation = 6 * dp
        background = pressable(background, radius, ink)
        setPadding((18 * dp).toInt(), 0, (6 * dp).toInt(), 0)
        addView(ImageView(context).apply { setImageDrawable(symbol("search", ink, 22f)) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (14 * dp).toInt() })
        addView(text(getString(R.string.search), 15f, ink2).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, -2, 1f))
        addView(ImageView(context).apply {
            setImageDrawable(symbol("mic", ink, 20f))
            contentDescription = getString(R.string.voice_search)
            scaleType = ImageView.ScaleType.CENTER
            val wellColor = if (system) m3.well else blend(color, Color.BLACK, if (light) 0.07f else 0.3f)
            background = pressable(well(minOf(radius, key / 2), wellColor), minOf(radius, key / 2), ink)
            setOnClickListener { perform("voice") }
        }, LinearLayout.LayoutParams(key.toInt(), key.toInt()))
        setOnClickListener { perform("search") }
        setOnLongClickListener { homeMenu(); true }
    }
    return FrameLayout(this).apply {
        val p = (16 * dp).toInt()
        // Room under the pill for its shadow.
        setPadding(p, p / 2, p, (10 * dp).toInt())
        clipToPadding = false
        addView(pill, FrameLayout.LayoutParams(-1, height.toInt()))
    }
}

/** [depth]: home recedes (blur, scale) behind it; folders keep home as it is, as in Launcher3. */
fun MainActivity.showOverlay(v: View, depth: Boolean = true, onClose: () -> Unit = {}) {
    closeOverlay()
    root.addView(v)
    overlay = v
    if (depth) blurHome(true)
    overlayClosed = onClose
}

fun MainActivity.closeOverlay() {
    val v = overlay ?: return
    overlay = null
    settingsPage = null
    settingsScroll = null
    root.removeView(v)
    // An exit animation plays in the root's overlay layer: drawn, but already gone for touch, drags and Back.
    overlayExit?.let { exit -> root.overlay.add(v); exit { root.overlay.remove(v) } }
    overlayExit = null
    if (!drawer.visible) blurHome(false)
    overlayClosed().also { overlayClosed = {} }
}

/** Desktop previews: every page as a thumbnail. Tap to go there; long-press to set default, move or remove; "+" adds a page. */
@SuppressLint("SetTextI18n")
fun MainActivity.showOverview() {
    drawer.hide()
    // Three tiles per row, each with 6 dp margins on both sides.
    val w = maxOf(1, ((workspace.width - 48 * dp) / 3).toInt())
    val h = maxOf(1, w * workspace.height / maxOf(1, workspace.width))
    val default = looks.int("defaultPage")
    val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    var row = LinearLayout(this)
    fun add(v: View) {
        if (row.childCount == 0) grid.addView(row)
        row.addView(v, LinearLayout.LayoutParams(w, h + (24 * dp).toInt()).apply { setMargins((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt()) })
        if (row.childCount == 3) row = LinearLayout(this)
    }
    pages.forEachIndexed { i, page ->
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // Some widgets hold hardware bitmaps, which a software canvas can't draw; the thumbnail is then left blank.
        runCatching { Canvas(bmp).apply { scale(w / maxOf(1f, page.width.toFloat()), h / maxOf(1f, page.height.toFloat())); page.draw(this) } }
        add(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ImageView(context).apply {
                setImageBitmap(bmp)
                val current = i == workspace.current
                background = rounded(m3.onSurface and 0xFFFFFF or 0x14000000, 16 * dp, if (current) m3.primary else m3.outlineVariant, (if (current) 3 else 1) * dp)
                clipToOutline = true
                val p = (if (current) 3 * dp else dp).toInt()
                setPadding(p, p, p, p)
                foreground = pressable(null, 16 * dp)
                isDuplicateParentStateEnabled = true
            }, LinearLayout.LayoutParams(w, h))
            addView(text(getString(R.string.page_n, i + 1), 12f, m3.onSurface, medium = i == default).apply {
                gravity = Gravity.CENTER
                setPadding(0, (6 * dp).toInt(), 0, 0)
                if (i == default) {
                    compoundDrawablePadding = (4 * dp).toInt()
                    setCompoundDrawablesRelative(symbol("home", m3.primary, 14f).apply { setBounds(0, 0, intrinsicWidth, intrinsicHeight) }, null, null, null)
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
            setOnClickListener { workspace.snapTo(i, animate = false); closeOverlay() }
            setOnLongClickListener { pageMenu(i); true }
        })
    }
    if (pages.size < 9) add(LinearLayout(this).apply {
        addView(ImageView(context).apply {
            setImageDrawable(symbol("add", m3.onSecondaryContainer, 32f))
            scaleType = ImageView.ScaleType.CENTER
            background = pressable(rounded(m3.secondaryContainer, 16 * dp), 16 * dp, m3.onSecondaryContainer)
            isDuplicateParentStateEnabled = true
        }, LinearLayout.LayoutParams(w, h))
        setOnClickListener { looks["pages"] = (pages.size + 1).toString(); rebuild() }
    })
    showOverlay(FrameLayout(this).apply {
        setBackgroundColor(m3.surface and 0xFFFFFF or 0xD9000000.toInt())
        setOnClickListener { closeOverlay() }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, insets.top + (24 * dp).toInt(), 0, insets.bottom)
            addView(text(getString(R.string.pages), 28f).apply { gravity = Gravity.CENTER })
            addView(text(getString(R.string.pages_hint), 14f, m3.onSurfaceVariant).apply { gravity = Gravity.CENTER; setPadding(0, (4 * dp).toInt(), 0, (16 * dp).toInt()) })
            addView(ScrollView(context).apply { addView(grid) }, LinearLayout.LayoutParams(-1, -2))
        }, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
    })
}

private fun MainActivity.pageMenu(i: Int) {
    val n = pages.size
    val options = listOf(R.string.page_set_default, R.string.page_move_left, R.string.page_move_right, R.string.page_remove).map(::getString)
    dialog().setTitle(getString(R.string.page_n, i + 1)).setItems(options.toTypedArray()) { _, which ->
        val default = looks.int("defaultPage")
        when (which) {
            0 -> { looks["defaultPage"] = i.toString(); showOverview() }
            1, 2 -> {
                val to = if (which == 1) i - 1 else i + 1
                if (to !in 0 until n) return@setItems
                items = movePage(items, i, to).toMutableList()
                if (default == i) looks["defaultPage"] = to.toString() else if (default == to) looks["defaultPage"] = i.toString()
                save(); render(); showOverview()
            }
            3 -> {
                if (n == 1) { toast(R.string.page_last); return@setItems }
                val doRemove = {
                    val (kept, gone) = removePage(items, i)
                    gone.filter { it.isWidget }.forEach { host.deleteAppWidgetId(it.widgetId) }
                    items = kept.toMutableList()
                    gone.filter { it.isShortcut }.forEach { syncPins(it.pkg, it.user) }
                    save()
                    looks["pages"] = (n - 1).toString()
                    looks["defaultPage"] = (if (default > i) default - 1 else default).coerceAtMost(n - 2).toString()
                    rebuild()
                }
                if (items.none { it.page == i }) doRemove()
                else dialog().setMessage(getString(R.string.page_remove_confirm, i + 1))
                    .setPositiveButton(R.string.remove) { _, _ -> doRemove() }.setNegativeButton(R.string.cancel, null).show()
            }
        }
    }.show()
}

/** Drag handles on a widget's edges; tapping outside applies the new size. */
fun MainActivity.showResize(cell: CellLayout, item: Item, v: View, minW: Int, minH: Int, canW: Boolean, canH: Boolean) {
    val frame = ResizeFrame(this, cell, item, v, minW, minH, canW, canH)
    showOverlay(frame) {
        val n = frame.result()
        val i = items.indexOf(item)
        if (n != item && i >= 0) { items[i] = n; save(); render() }
    }
}

@SuppressLint("ViewConstructor")
class ResizeFrame(
    private val a: MainActivity, private val cell: CellLayout, private val item: Item, private val ignore: View,
    private val minW: Int, private val minH: Int, private val canW: Boolean, private val canH: Boolean,
) : View(a) {
    private var l = item.x
    private var t = item.y
    private var r = item.x + item.w
    private var b = item.y + item.h
    private var edge = -1
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3 * a.dp }
    private val origin = IntArray(2)

    fun result() = item.copy(x = l, y = t, w = r - l, h = b - t)

    /** The grid's top-left corner in this view. */
    private fun cellOrigin(): Pair<Float, Float> {
        val c = IntArray(2).also { cell.getLocationInWindow(it) }
        getLocationInWindow(origin)
        return (c[0] - origin[0]).toFloat() to (c[1] - origin[1]).toFloat()
    }

    /** Home sinks back under an overlay (setDepth): the grid's on-screen scale. */
    private val s get() = a.content.scaleX

    private fun rect(): RectF {
        val (ox, oy) = cellOrigin()
        val left = ox + s * cell.spanLeft(l, r - l)
        return RectF(left, oy + s * t * cell.cellH, left + s * (r - l) * cell.cellW, oy + s * b * cell.cellH)
    }

    /** Right to left, the handle drawn on the left moves the grid's end column, and the one on the right its start. */
    private val rtl get() = cell.layoutDirection == LAYOUT_DIRECTION_RTL

    private fun handles(rc: RectF) = listOf(rc.left to rc.centerY(), rc.centerX() to rc.top, rc.right to rc.centerY(), rc.centerX() to rc.bottom)

    override fun onDraw(canvas: Canvas) {
        val rc = rect()
        paint.style = Paint.Style.STROKE
        paint.color = a.accent
        canvas.drawRoundRect(rc, 8 * a.dp, 8 * a.dp, paint)
        paint.style = Paint.Style.FILL
        handles(rc).forEachIndexed { i, (x, y) ->
            if (if (i % 2 == 0) canW else canH) canvas.drawCircle(x, y, 9 * a.dp, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val rc = rect()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edge = handles(rc).indexOfFirst { (x, y) -> hypot(ev.x - x, ev.y - y) < 32 * a.dp }
                if (edge >= 0 && !(if (edge % 2 == 0) canW else canH)) edge = -1
                if (edge < 0 && !rc.contains(ev.x, ev.y)) a.closeOverlay()
            }
            MotionEvent.ACTION_MOVE -> if (edge >= 0) {
                val (ox, oy) = cellOrigin()
                val cx = cell.column((ev.x - ox) / s).roundToInt()
                val cy = ((ev.y - oy) / s / cell.cellH).roundToInt()
                var nl = l; var nt = t; var nr = r; var nb = b
                when (if (rtl && edge % 2 == 0) 2 - edge else edge) {
                    0 -> nl = cx.coerceIn(0, r - minW)
                    1 -> nt = cy.coerceIn(0, b - minH)
                    2 -> nr = cx.coerceIn(l + minW, cell.cols)
                    3 -> nb = cy.coerceIn(t + minH, cell.rows)
                }
                if (cell.isFree(nl, nt, nr - nl, nb - nt, ignore) || a.looks.on("widgetOverlap")) { l = nl; t = nt; r = nr; b = nb; invalidate() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> edge = -1
        }
        return true
    }
}

/** "Item removed · Undo" for 2.5 seconds, just above the navigation bar; [finalize] runs if it wasn't undone. */
// Finalize is skipped if the activity is destroyed within the 2.5 s; the next start frees that widget ID (MainActivity.onCreate).
@SuppressLint("SetTextI18n")
fun MainActivity.showUndo(item: Item, finalize: () -> Unit) {
    if (!looks.on("undoBar")) { finalize(); return }
    // M3 snackbar: inverse surface with an inverse-primary action.
    val bar = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = (48 * dp).toInt()
        val p = (16 * dp).toInt()
        setPadding(p, 0, p / 2, 0)
        background = rounded(m3.inverseSurface, 8 * dp)
        elevation = 6 * dp
        addView(text(getString(R.string.item_removed), 14f, m3.inverseOnSurface), LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(getString(R.string.undo), 14f, m3.inversePrimary, medium = true).apply {
            gravity = Gravity.CENTER
            setPadding(p * 3 / 4, 0, p * 3 / 4, 0)
            background = pressable(null, 20 * dp, m3.inversePrimary)
        }, LinearLayout.LayoutParams(-2, (40 * dp).toInt()))
    }
    bar.getChildAt(1).setOnClickListener {
        root.removeView(bar)
        items += item
        save()
        render()
    }
    root.addView(bar, FrameLayout.LayoutParams(minOf(root.width - (32 * dp).toInt(), (560 * dp).toInt()), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
        bottomMargin = insets.bottom + (16 * dp).toInt()
    })
    bar.translationY = 24 * dp; bar.alpha = 0f
    bar.animate().translationY(0f).alpha(1f).setDuration(200).start()
    bar.postDelayed({ if (bar.parent != null) { root.removeView(bar); finalize() } }, 2500)
}
