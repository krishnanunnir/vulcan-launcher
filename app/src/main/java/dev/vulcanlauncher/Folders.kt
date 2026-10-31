package dev.vulcanlauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

val PREVIEWS = listOf("grid", "stack", "line", "fan", "radial")
val PREVIEW_NAMES = listOf(
    R.string.preview_grid, R.string.preview_stack, R.string.preview_line, R.string.preview_fan,
    R.string.preview_radial,
)
val FOLDER_BGS = listOf("none", "circle", "disc", "platform", "square")
val FOLDER_BG_NAMES = listOf(
    R.string.folderbg_none, R.string.folderbg_circle, R.string.folderbg_disc, R.string.folderbg_platform,
    R.string.folderbg_square,
)

/** Where one preview icon goes inside a folder icon: centre and size as fractions of the icon, rotation in degrees. */
data class Slot(val cx: Float, val cy: Float, val size: Float, val rotation: Float = 0f)

/** Preview slots for the first [n] folder items, in drawing order of the items (item 0 first). */
fun folderPreview(style: String, n: Int, firstBig: Boolean): List<Slot> {
    if (n == 0) return emptyList()
    if (firstBig) return listOf(Slot(0.5f, 0.42f, 0.56f)) +
        listOf(Slot(0.25f, 0.82f, 0.26f), Slot(0.5f, 0.82f, 0.26f), Slot(0.75f, 0.82f, 0.26f)).take(n - 1)
    return when (style) {
        "stack" -> List(minOf(n, 3)) { Slot(0.42f + 0.08f * it, 0.42f + 0.08f * it, 0.56f) }
        "line" -> List(minOf(n, 3)) { Slot(0.2f + 0.3f * it, 0.5f, 0.28f) }
        "fan" -> listOf(Slot(0.5f, 0.52f, 0.52f), Slot(0.36f, 0.56f, 0.48f, -18f), Slot(0.64f, 0.56f, 0.48f, 18f)).take(n)
        "radial" -> List(minOf(n, 6)) {
            val a = -Math.PI / 2 + 2 * Math.PI * it / minOf(n, 6)
            Slot((0.5 + 0.28 * Math.cos(a)).toFloat(), (0.5 + 0.28 * Math.sin(a)).toFloat(), 0.3f)
        }
        else -> listOf(Slot(0.3f, 0.3f, 0.38f), Slot(0.7f, 0.3f, 0.38f), Slot(0.3f, 0.7f, 0.38f), Slot(0.7f, 0.7f, 0.38f)).take(n)
    }
}

/** A folder's home-screen icon: background plus previews of its first items. */
class FolderIcon(
    private val icons: List<Drawable>, private val style: String, private val bg: String,
    private val color: Int, private val clip: Boolean, private val firstBig: Boolean,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val b = RectF()
    private val oval = RectF()
    private val shape = Path()

    override fun draw(canvas: Canvas) {
        b.set(bounds)
        val w = b.width()
        shape.rewind()
        paint.style = Paint.Style.FILL
        paint.color = color
        when (bg) {
            "circle" -> shape.addCircle(b.centerX(), b.centerY(), w / 2, Path.Direction.CW)
            "disc" -> shape.addCircle(b.centerX(), b.centerY(), w / 2, Path.Direction.CW)
            "square" -> shape.addRoundRect(b, w / 5, w / 5, Path.Direction.CW)
            "platform" -> shape.addOval(oval.apply { set(b.left, b.bottom - w * 0.3f, b.right, b.bottom) }, Path.Direction.CW)
        }
        if (bg != "none") canvas.drawPath(shape, paint)
        if (bg == "disc") {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = w / 24
            paint.color = color or 0xFF000000.toInt()
            canvas.drawCircle(b.centerX(), b.centerY(), w / 2 - w / 48, paint)
        }
        canvas.save()
        if (clip && (bg == "circle" || bg == "disc" || bg == "square")) canvas.clipPath(shape)
        val slots = folderPreview(style, icons.size, firstBig)
        for (i in slots.indices.reversed()) { // first item on top
            val s = slots[i]
            val half = s.size * w / 2
            val cx = b.left + s.cx * w
            val cy = b.top + s.cy * b.height()
            canvas.save()
            canvas.rotate(s.rotation, cx, cy)
            icons[i].setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            icons[i].draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Required override") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Icon for an action shortcut, web search or contact: a tonal disc with a [symbol] or a short [text]. */
class GlyphIcon(private val bg: Int, private val fg: Int, private val text: String? = null, private val symbol: String? = null) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun draw(canvas: Canvas) {
        val b = bounds
        paint.color = bg
        canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), b.width() / 2f, paint)
        if (symbol != null) {
            val q = b.width() / 4
            Symbol(symbol, fg, q * 2).apply { setBounds(b.left + q, b.top + q, b.right - q, b.bottom - q) }.draw(canvas)
            return
        }
        paint.color = fg
        paint.textSize = b.height() * 0.42f
        canvas.drawText(text ?: "", b.exactCenterX(), b.exactCenterY() - (paint.ascent() + paint.descent()) / 2, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Required override") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

// ---- Folder behaviour: making one by dragging, emptying it, and its window (as in Launcher3) ----

/** Fill of folder icons, also used by the hover disc that previews a new folder. */
fun MainActivity.folderTint() = if (looks.str("folderColor") == "system") m3.surfaceHigh and 0xFFFFFF or 0xE6000000.toInt()
    else looks.color("folderColor") and 0xFFFFFF or 0x99000000.toInt()

fun MainActivity.folderIcon(children: List<Item>) = FolderIcon(children.take(6).mapNotNull { resolve(it)?.second },
    looks.str("folderPreview"), looks.str("folderBg"), folderTint(), looks.on("folderClip"), looks.on("folderFirstBig"))

/** Drag feedback for folders: the hover disc while making one, and the settle of a folder icon whose contents changed. */
class FolderFx(private val a: MainActivity) {
    private var hover: Item? = null
    private var hoverCell: CellLayout? = null
    private var ringAnim: ValueAnimator? = null
    private var settling: Item? = null
    private var settleFrom = 1f

    /**
     * While a drag rests on an icon it can merge with, a folder-coloured disc grows behind that icon and the icon sinks
     * a little into it; moving off ([target] null) shrinks the disc away.
     */
    fun hover(cell: CellLayout, target: Item?) {
        if (target == hover && (target == null || cell === hoverCell)) return
        hover?.let { a.views[it] }?.let { (it as? IconView)?.image?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(150)?.start() }
        ringAnim?.cancel()
        hoverCell?.takeIf { it !== cell }?.ringR = 0f
        hover = target
        hoverCell = cell
        val image = target?.let { a.views[it] as? IconView }?.image
        val to = if (image != null) {
            val v = image.parent as View
            cell.ringX = v.left + image.left + image.width / 2f
            cell.ringY = v.top + image.top + image.height / 2f
            cell.ringPaint.color = a.folderTint()
            image.animate().scaleX(0.88f).scaleY(0.88f).setDuration(200).start()
            cell.tick()
            image.width * 0.75f
        } else 0f
        ringAnim = ValueAnimator.ofFloat(cell.ringR, to).apply {
            duration = if (to > 0) 220 else 150
            interpolator = if (to > 0) OvershootInterpolator(1.6f) else DecelerateInterpolator()
            addUpdateListener { cell.ringR = it.animatedValue as Float }
            start()
        }
    }

    fun clearHover() {
        ringAnim?.cancel()
        hoverCell?.ringR = 0f
        hover = null
        hoverCell = null
    }

    /** After the next render, [item]'s icon eases from [from]× to its size: grown out of the disc, or shrunk by a loss. */
    fun settleNext(item: Item, from: Float) { settling = item; settleFrom = from }

    /** Called at the end of every render. */
    fun settle() {
        val image = settling?.let { a.views[it] as? IconView }?.image
        settling = null
        image ?: return
        image.scaleX = settleFrom; image.scaleY = settleFrom
        image.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(2f)).start()
    }
}

/** The icon or folder under ([px], [py]) that dropping [payload] there would merge into, or null. */
fun MainActivity.mergeTarget(cell: CellLayout, px: Float, py: Float, payload: Any?): Item? {
    if (!dragMoved || asChild(payload) == null) return null
    val (fx, fy) = cell.cellAt(px, py)
    val target = cell.viewAt(fx, fy)?.let { viewItems[it] } ?: return null
    return target.takeIf { it != payload && !(payload is FolderChild && payload.folder == it) &&
        (it.isApp || it.folder || it.isShortcut || it.isAction) }
}

/** Drops [child] (the dragged [payload] as a folder item) onto [target]: into it if it's a folder, else a new folder of both. */
fun MainActivity.mergeIntoFolder(target: Item, child: Item, payload: Any?) {
    val merged = if (target.folder) target.copy(children = target.children + child)
        else Item(target.page, target.x, target.y, target.w, target.h, folder = true, label = getString(R.string.folder), children = listOf(asChild(target)!!, child))
    detach(payload)
    items[items.indexOf(target)] = merged
    root.confirm()
    save()
    folderFx.settleNext(merged, 1.3f)
    render()
}

/** Takes one item out of a folder; a folder left with one item turns back into that item. The rest settles in place. */
fun MainActivity.removeFromFolder(fc: FolderChild) {
    val f = fc.folder
    val i = items.indexOf(f)
    if (i < 0) return
    val rest = f.children.filterIndexed { j, _ -> j != fc.index }
    when (rest.size) {
        0 -> items.removeAt(i)
        1 -> items[i] = rest[0].copy(page = f.page, x = f.x, y = f.y, w = f.w, h = f.h)
        else -> items[i] = f.copy(children = rest)
    }
    items.getOrNull(i)?.takeIf { rest.isNotEmpty() }?.let { folderFx.settleNext(it, 0.82f) }
}

/**
 * Launcher3's folder reveal: [card] grows out of the folder icon at [from] (root coordinates) into its rounded box,
 * its contents scaling up from the icon; closing ([open] false) runs it back into the icon and then calls [done].
 */
private fun reveal(card: View, from: Rect, startCorner: Float, corner: Float, open: Boolean, dim: Drawable?, done: () -> Unit = {}) {
    val s0 = 0.7f
    var f = if (open) 0f else 1f
    fun lerp(x: Float, y: Float) = x + (y - x) * f
    card.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(v: View, o: Outline) {
            // The icon's box in the card's unscaled coordinates, grown by 1/s0 so that at scale s0 it covers the icon exactly.
            val cx = from.exactCenterX() - v.left
            val cy = from.exactCenterY() - v.top - v.translationY
            val hw = from.width() / 2f / s0
            val hh = from.height() / 2f / s0
            o.setRoundRect(lerp(cx - hw, 0f).roundToInt(), lerp(cy - hh, 0f).roundToInt(),
                lerp(cx + hw, v.width.toFloat()).roundToInt(), lerp(cy + hh, v.height.toFloat()).roundToInt(), lerp(startCorner / s0, corner))
        }
    }
    card.clipToOutline = true
    fun frame() {
        card.pivotX = from.exactCenterX() - card.left
        card.pivotY = from.exactCenterY() - card.top - card.translationY
        val s = lerp(s0, 1f)
        card.scaleX = s; card.scaleY = s
        dim?.alpha = (255 * f).roundToInt()
        card.invalidateOutline()
    }
    frame()
    ValueAnimator.ofFloat(f, if (open) 1f else 0f).apply {
        duration = if (open) 380 else 240
        // Material's emphasized decelerate in, accelerate out.
        interpolator = if (open) PathInterpolator(0.05f, 0.7f, 0.1f, 1f) else PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
        addUpdateListener { f = it.animatedValue as Float; frame() }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (open) { card.clipToOutline = false; card.outlineProvider = ViewOutlineProvider.BACKGROUND }
                done()
            }
        })
        start()
    }
}

/** Opens a home-screen folder; its name is editable in the window. */
fun MainActivity.openFolder(item: Item) {
    drawer.hide()
    var folder = item
    openFolderWindow(item.label, item.children, { name ->
        val i = items.indexOf(folder)
        if (i >= 0 && name != folder.label) { items[i] = folder.copy(label = name.ifEmpty { getString(R.string.folder) }); save(); render() }
    }, reorder = { from, to ->
        val i = items.indexOf(folder)
        if (i >= 0) {
            folder = folder.copy(children = folder.children.toMutableList().apply { add(to, removeAt(from)) })
            items[i] = folder
            save()
        }
        folder.children
    }, anchor = views[item]?.let { screenRect((it as? IconView)?.image ?: it) }) { FolderChild(folder, it) }
}

/** Start of a [size] span centred on [center], kept within [min]..[max] (or at [min] if it doesn't fit). */
fun spanAround(center: Int, size: Int, min: Int, max: Int) = (center - size / 2).coerceIn(min, maxOf(min, max - size))

/**
 * Shows [children] in a pop-up or full-screen folder window. [payload] makes the drag payload for child [i].
 * With [reorder], an icon dropped on the grid moves there (it returns the new order); dragged off the grid, it leaves the window.
 * With [anchor] (the folder icon's image, window coordinates), the pop-up grows out of it and closes back into it, under
 * the thumb, instead of opening mid-screen.
 * It opens over whatever is showing: from the drawer, Back closes it back to the drawer.
 */
fun MainActivity.openFolderWindow(title: String, children: List<Item>, onRename: ((String) -> Unit)?,
                                  reorder: ((from: Int, to: Int) -> List<Item>)? = null, anchor: Rect? = null, payload: (Int) -> Any) {
    val full = looks.str("folderWindow") == "fullscreen"
    val base = style("")
    val style = IconStyle(base.sizePx, true, looks.labelSize("folderLabelSize"), panelText, false, base.iconShadow, font = base.font, lines = base.lines)
    // Sized to its contents, as in Launcher3: 2 items 2×1, 4 are 2×2, 6 are 3×2, up to four columns.
    val cols = ceil(sqrt(children.size.toDouble())).toInt().coerceIn(1, 4)
    val rows = maxOf(1, ceil(children.size / cols.toDouble()).toInt())
    val cellH = base.sizePx + (44 * dp).toInt()
    val cellW = base.sizePx + (36 * dp).toInt()
    val cell = CellLayout(this, cols, rows, 0)
    fun fill(list: List<Item>) {
        cell.removeAllViews()
        list.forEachIndexed { i, c ->
            val (label, icon) = resolve(c) ?: return@forEachIndexed
            cell.addView(IconView(this, label, icon, style, badgeStyle(), c.pkg).apply {
                layoutParams = CellLayout.CellParams(i % cols, i / cols, 1, 1)
                if (looks.str("badges") != "off") badge(image, c.pkg)
                setOnClickListener { v -> closeOverlay(); open(c, v) }
                setOnLongClickListener { v -> startDrag(v, payload(i)); true }
            })
        }
    }
    fill(children)
    // ponytail: the icon lands where it's dropped, with no live shuffle of the others while dragging (Nova shows one).
    if (reorder != null) cell.setOnDragListener { v, e ->
        trackDrag(v, e, keepOverlay = true)
        when (e.action) {
            DragEvent.ACTION_DROP -> {
                val from = (e.localState as? FolderChild)?.index ?: return@setOnDragListener false
                if (!dragMoved) return@setOnDragListener false
                val (x, y) = cell.cellAt(e.x, e.y)
                fill(reorder(from, minOf(y * cols + x, children.size - 1)))
                root.tick()
            }
            // Only the view that took the drop hears ENDED.
            DragEvent.ACTION_DRAG_ENDED -> dragEnded()
        }
        true
    }
    val name: TextView = (if (onRename != null) EditText(this) else TextView(this)).apply {
        text = title
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(m3.onSurface)
        background = null
        setSingleLine()
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
    }
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (full) background = panelBg(100, 0f, m3.surfaceHigh) else frost(this, looks.int("folderRadius") * dp)
        isClickable = true
        // Full screen: name and icons centred, within reach of the thumb as in the pop-up; a long folder scrolls.
        if (full) gravity = Gravity.CENTER_VERTICAL
        val p = (16 * dp).toInt()
        setPadding(p, p + if (full) insets.top else 0, p, p + if (full) insets.bottom else 0)
        addView(ScrollView(context).apply {
            addView(cell, FrameLayout.LayoutParams(cols * cellW, rows * cellH))
        }, LinearLayout.LayoutParams(-2, if (full) -2 else minOf(rows, 4) * cellH).apply { gravity = Gravity.CENTER_HORIZONTAL })
        // The name sits under the icons, as in Launcher3.
        addView(name)
    }
    val w = cols * cellW + (32 * dp).toInt()
    val margin = (16 * dp).toInt()
    // The pop-up stays clear of the dock: above it, or left of it in landscape where it's a column on the right.
    val dockR = if (dockBox.visibility == View.VISIBLE && dockBox.isLaidOut) screenRect(dockBox) else null
    val bottom = if (dockR != null && dockR.width() > dockR.height()) dockR.top else root.height - insets.bottom
    val right = if (dockR != null && dockR.width() <= dockR.height()) dockR.left else root.width
    val spot = if (full || anchor == null) null else {
        card.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        Point(spanAround(anchor.centerX(), w, margin, right - margin),
            spanAround(anchor.centerY(), card.measuredHeight, insets.top + margin, bottom - margin))
    }
    val dim = FrameLayout(this).apply {
        setBackgroundColor(0x52000000)
        setOnClickListener { closeOverlay() }
        // Off the folder's grid, a reorderable icon leaves the window for the home screen.
        setOnDragListener { v, e ->
            if (reorder != null && dragMoved && e.action == DragEvent.ACTION_DRAG_LOCATION) closeOverlay()
            passDrag.onDrag(v, e)
        }
        addView(card, when {
            full -> FrameLayout.LayoutParams(-1, -1)
            // LEFT, not START: x is measured from the left edge in right-to-left languages too.
            spot != null -> FrameLayout.LayoutParams(w, -2, Gravity.TOP or Gravity.LEFT).apply { leftMargin = spot.x; topMargin = spot.y }
            else -> FrameLayout.LayoutParams(w, -2, Gravity.CENTER)
        })
    }
    if (spot != null) {
        // Renaming opens the keyboard, which raises the bottom inset: lift the card above it, and back down after.
        val lift = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            card.translationY = minOf(0, minOf(bottom, root.height - insets.bottom) - margin - spot.y - card.height).coerceAtLeast(insets.top - spot.y).toFloat()
        }
        dim.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = v.viewTreeObserver.addOnGlobalLayoutListener(lift)
            override fun onViewDetachedFromWindow(v: View) = v.viewTreeObserver.removeOnGlobalLayoutListener(lift)
        })
    }
    showOverlay(dim) { onRename?.invoke(name.text.toString().trim()) }
    if (spot != null && anchor != null) {
        val corner = looks.int("folderRadius") * dp
        val iconCorner = anchor.width() * if (looks.str("folderBg") == "square") 0.2f else 0.5f
        reveal(card, anchor, iconCorner, corner, open = true, dim = dim.background)
        // Dragging an icon out, Back, or a tap outside: the window shrinks back into its folder.
        overlayExit = { done -> reveal(card, anchor, iconCorner, corner, open = false, dim = dim.background, done = done) }
    } else if (!full) {
        card.scaleX = 0.9f; card.scaleY = 0.9f; card.alpha = 0f
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
    }
}
