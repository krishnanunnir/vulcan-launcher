package dev.vulcanlauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator

/** Do two cell rectangles share at least one cell? Edges touching is not overlap. */
fun overlaps(ax: Int, ay: Int, aw: Int, ah: Int, bx: Int, by: Int, bw: Int, bh: Int) =
    ax < bx + bw && bx < ax + aw && ay < by + bh && by < ay + ah

/**
 * A grid span set up for portrait, scaled to the screen's current length [now] along that axis ([portrait] is that
 * axis's length in portrait), so landscape cells keep their portrait size instead of stretching.
 */
fun scaleSpan(n: Int, portrait: Int, now: Int) = maxOf(1, Math.round(n * now / portrait.toFloat()))

/**
 * After a grid, page-count or dock change: keeps items that still fit and moves the rest to the first free
 * spot (own page first; dock items try the dock first). Oversized widgets shrink to the grid.
 */
// ponytail: items with no room anywhere keep their old spot and stay hidden until the grid grows back
fun fitItems(items: List<Item>, pages: Int, cols: Int, rows: Int, dockCols: Int, dockPages: Int = 1): List<Item> {
    fun fits(i: Item) = if (isDock(i.page)) dockIndex(i.page) < dockPages && i.x + i.w <= dockCols && i.y == 0
        else i.page in 0 until pages && i.x + i.w <= cols && i.y + i.h <= rows
    val placed = items.filter(::fits).toMutableList()
    fun free(i: Item) = fits(i) && placed.none { it.page == i.page && overlaps(i.x, i.y, i.w, i.h, it.x, it.y, it.w, it.h) }
    for (item in items.filterNot(::fits)) {
        val w = item.w.coerceAtMost(cols)
        val h = item.h.coerceAtMost(rows)
        val dockSpots = if (isDock(item.page) && item.w == 1) (0 until dockPages).flatMap { p -> (0 until dockCols).map { item.copy(page = -1 - p, x = it, y = 0) } } else emptyList()
        val pageOrder = (listOf(item.page) + (0 until pages)).filter { it in 0 until pages }.distinct()
        val homeSpots = pageOrder.asSequence().flatMap { p ->
            (0 until rows).asSequence().flatMap { y -> (0 until cols).asSequence().map { x -> item.copy(page = p, x = x, y = y, w = w, h = h) } }
        }
        placed += (dockSpots.asSequence() + homeSpots).firstOrNull(::free) ?: item
    }
    return placed
}

/** First free cell rectangle of size [w]x[h] on [page] (or any page from it onward), or null. */
fun firstFreeSpot(items: List<Item>, pages: Int, cols: Int, rows: Int, w: Int, h: Int, page: Int = 0): Item? {
    for (p in (page until pages) + (0 until page)) for (y in 0..rows - h) for (x in 0..cols - w)
        if (items.none { it.page == p && overlaps(x, y, w, h, it.x, it.y, it.w, it.h) }) return Item(p, x, y, w, h)
    return null
}

/**
 * Widget sizes (w to h, in cells) reachable from its top corner at ([x], [y]) inside a [cols]×[rows] grid: at least
 * [minW]×[minH], and only the current width or height along an axis the provider doesn't let resize.
 */
fun widgetSizes(x: Int, y: Int, w: Int, h: Int, minW: Int, minH: Int, cols: Int, rows: Int, canW: Boolean, canH: Boolean): List<Pair<Int, Int>> {
    val ws = if (canW) (minW..cols - x).toList() else listOf(w)
    val hs = if (canH) (minH..rows - y).toList() else listOf(h)
    return hs.flatMap { hh -> ws.map { ww -> ww to hh } }
}

/** Deletes home page [page]: its items are dropped and later pages shift left. Returns kept items and dropped ones. */
fun removePage(items: List<Item>, page: Int): Pair<List<Item>, List<Item>> {
    val (gone, kept) = items.partition { it.page == page }
    return kept.map { if (it.page > page) it.copy(page = it.page - 1) else it } to gone
}

/** Moves home page [from] to position [to], shifting the pages between. */
fun movePage(items: List<Item>, from: Int, to: Int): List<Item> = items.map {
    val p = it.page
    when {
        isDock(p) -> it
        p == from -> it.copy(page = to)
        from < to && p in from + 1..to -> it.copy(page = p - 1)
        to < from && p in to until from -> it.copy(page = p + 1)
        else -> it
    }
}

/** Converts home items between normal and subgrid (half-cell) coordinates. [factor] is 2 to enable, -2 to disable. */
fun scaleItems(items: List<Item>, factor: Int): List<Item> = items.map {
    when {
        isDock(it.page) -> it
        factor > 0 -> it.copy(x = it.x * factor, y = it.y * factor, w = it.w * factor, h = it.h * factor)
        else -> { val f = -factor; it.copy(x = it.x / f, y = it.y / f, w = maxOf(1, it.w / f), h = maxOf(1, it.h / f)) }
    }
}

/**
 * Fixed grid; each child carries its cell rectangle in [CellParams]. [transposed] stands the grid on its side (the
 * landscape dock): cell (x, y) is drawn at column y, row x. [cellW]/[cellH] are always along x/y.
 */
class CellLayout(context: Context, val cols: Int, val rows: Int, val page: Int, val transposed: Boolean = false) : ViewGroup(context) {

    class CellParams(val x: Int, val y: Int, val w: Int, val h: Int) : LayoutParams(0, 0)

    /** Folder-create disc drawn behind the icons while a drag hovers on one: centre and radius (px); radius 0 hides it. */
    var ringX = 0f
    var ringY = 0f
    var ringR = 0f
        set(v) { field = v; invalidate() }
    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Launcher3's drop outline: where a drop would land (grid units, null for nowhere), stroked in [outlineColor].
     * Each new spot fades in over 500 ms while the last one fades out, so a moving drag leaves a short trail.
     */
    var landing: Rect? = null
        set(v) {
            if (field == v) return
            field = v
            outlines.lastOrNull()?.let { o -> if (o.r != v) o.fade(0f) }
            if (v != null) outlines += Outline(Rect(v)).also { it.fade(1f) }
        }
    var outlineColor = 0
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f }
    private val outlines = ArrayList<Outline>()

    private inner class Outline(val r: Rect) {
        var alpha = 0f
        private var anim: ValueAnimator? = null
        fun fade(to: Float) {
            anim?.cancel()
            anim = ValueAnimator.ofFloat(alpha, to).apply {
                duration = 500
                interpolator = DecelerateInterpolator()
                addUpdateListener { alpha = it.animatedValue as Float; if (alpha == 0f && to == 0f) outlines.remove(this@Outline); invalidate() }
                start()
            }
        }
    }

    private fun cellRect(x: Int, y: Int, w: Int, h: Int, inset: Float) = (if (transposed)
        RectF((y * cellH).toFloat(), (x * cellW).toFloat(), ((y + h) * cellH).toFloat(), ((x + w) * cellW).toFloat())
        else RectF(spanLeft(x, w).toFloat(), (y * cellH).toFloat(), (spanLeft(x, w) + w * cellW).toFloat(), ((y + h) * cellH).toFloat()))
        .apply { inset(inset, inset) }

    override fun dispatchDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        for (o in outlines) {
            outlinePaint.color = outlineColor and 0xFFFFFF or ((o.alpha * 255).toInt() shl 24)
            canvas.drawRoundRect(cellRect(o.r.left, o.r.top, o.r.width(), o.r.height(), 6 * d), 16 * d, 16 * d, outlinePaint)
        }
        if (ringR > 0) canvas.drawCircle(ringX, ringY, ringR, ringPaint)
        super.dispatchDraw(canvas)
    }

    val cellW get() = (if (transposed) height else width) / cols
    val cellH get() = (if (transposed) width else height) / rows
    /** Right to left: column 0 is on the right. Not the transposed dock, which runs top to bottom. */
    private val mirrored get() = !transposed && layoutDirection == LAYOUT_DIRECTION_RTL

    /** Left edge (px) of a [w]-column span starting at column [x]. */
    fun spanLeft(x: Int, w: Int) = (if (mirrored) cols - x - w else x) * cellW

    /** Grid column under [px] (px from this view's left edge), fractional. */
    fun column(px: Float) = if (mirrored) cols - px / cellW else px / cellW

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        // Inside a ScrollView the height is unspecified: use the explicit layout height instead.
        val h = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) layoutParams?.height?.takeIf { it > 0 } ?: MeasureSpec.getSize(heightSpec)
            else MeasureSpec.getSize(heightSpec)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val p = c.layoutParams as CellParams
            val (cw, ch) = if (transposed) w / rows * p.h to h / cols * p.w else w / cols * p.w to h / rows * p.h
            c.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val p = c.layoutParams as CellParams
            val left = if (transposed) p.y * cellH else spanLeft(p.x, p.w)
            val top = if (transposed) p.x * cellW else p.y * cellH
            c.layout(left, top, left + c.measuredWidth, top + c.measuredHeight)
        }
    }

    /** Touch point (px, relative to this view) in grid units along x and y. */
    fun toGrid(px: Float, py: Float) = if (transposed) py / cellW to px / cellH else column(px) to py / cellH

    fun cellAt(px: Float, py: Float) = toGrid(px, py).let { (gx, gy) ->
        gx.toInt().coerceIn(0, cols - 1) to gy.toInt().coerceIn(0, rows - 1)
    }

    /** True when the rectangle is inside the grid and overlaps no child except [ignore]. */
    fun isFree(x: Int, y: Int, w: Int, h: Int, ignore: View? = null): Boolean {
        if (x < 0 || y < 0 || x + w > cols || y + h > rows) return false
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c === ignore) continue
            val p = c.layoutParams as CellParams
            if (overlaps(x, y, w, h, p.x, p.y, p.w, p.h)) return false
        }
        return true
    }

    /** The child covering cell ([x], [y]), if any. */
    fun viewAt(x: Int, y: Int): View? = (0 until childCount).map(::getChildAt).firstOrNull {
        val p = it.layoutParams as CellParams
        overlaps(x, y, 1, 1, p.x, p.y, p.w, p.h)
    }
}
