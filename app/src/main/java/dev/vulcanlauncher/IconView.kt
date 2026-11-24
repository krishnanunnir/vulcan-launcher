package dev.vulcanlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** How an icon looks on one surface (home, dock, drawer, folder). */
class IconStyle(val sizePx: Int, val labels: Boolean, val labelSize: Float, val labelColor: Int, val shadow: Boolean,
                val iconShadow: Boolean, val horizontal: Boolean = false, val font: String = "sans-serif", val lines: Int = 1)

/** Badge settings: style "off" / "dots" / "numeric" / "dynamic". */
class BadgeStyle(val style: String, val pos: String, val scale: Float, val color: Int, val shape: String = "circle")

/** Badge outline in the unit square for [pos]; a teardrop's point faces the icon's centre. */
fun badgeOutline(shape: String, pos: String): List<Pair<Float, Float>> {
    val o = shapeOutline(shape) ?: shapeOutline("circle")!!
    if (shape != "teardrop") return o
    val mx = pos.endsWith("Right"); val my = pos.startsWith("bottom") // the outline's point is at the bottom right
    return o.map { (x, y) -> (if (mx) 1 - x else x) to (if (my) 1 - y else y) }
}

/**
 * Icon size and label visibility for a [w]x[h] cell (px) holding an icon of [size] over a [labelH]-tall label:
 * the icon shrinks to fit, keeping an eighth of the cell as air; if that would halve it, the label goes first.
 */
fun fitIcon(size: Int, w: Int, h: Int, labelH: Int): Pair<Int, Boolean> {
    val bare = minOf(size, w - w / 8, h - h / 8).coerceAtLeast(0)
    val withLabel = minOf(size, w - w / 8, h - labelH)
    return if (labelH == 0) bare to true else if (withLabel >= size / 2) withLabel to true else bare to false
}

/** TalkBack can't drag: an item offers its menu (and on home "Move to page") as accessibility actions instead. */
fun accessibleActions(v: View, menu: () -> Unit, move: (() -> Unit)? = null) {
    v.accessibilityDelegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.addAction(AccessibilityAction(R.id.action_menu, host.context.getString(R.string.a11y_menu)))
            if (move != null) info.addAction(AccessibilityAction(R.id.action_move, host.context.getString(R.string.move_to_page)))
        }
        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?) = when {
            action == R.id.action_menu -> { menu(); true }
            action == R.id.action_move && move != null -> { move(); true }
            else -> super.performAccessibilityAction(host, action, args)
        }
    }
}

/** The icon drawn with an optional drop shadow and notification badge. */
class IconImage(context: Context, icon: Drawable, private val shadow: Boolean, private val badgeStyle: BadgeStyle) : View(context) {
    var icon = icon
        set(v) { field = v; shadowBmp = null; invalidate() }
    var badgeCount = 0
        set(v) { field = v; invalidate() }
    var badgeIcon: Drawable? = null
        set(v) { field = v; invalidate() }
    private val badgeOutline = badgeOutline(badgeStyle.shape, badgeStyle.pos)
    private val badgeRect = android.graphics.Rect()
    private var badgePath: android.graphics.Path? = null // rebuilt only when the badge moves or resizes
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shadowBmp: Bitmap? = null
    /** Blank pixels above and below this image inside its IconView; a badge never draws past them onto a neighbour's label. */
    var roomUp = 0
    var roomDown = 0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { shadowBmp = null }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height)
        val l = (width - s) / 2
        val t = (height - s) / 2
        if (shadow) {
            // A software copy: the alpha of a hardware bitmap can't be read.
            val bmp = shadowBmp ?: rendered(s, s) { icon.setBounds(0, 0, s, s); icon.draw(it) }.let { r ->
                if (r.config == Bitmap.Config.HARDWARE) r.copy(Bitmap.Config.ARGB_8888, false).also { r.recycle() } else r
            }.let { b ->
                // Only the blurred alpha is kept: free the full-colour copy now instead of at some later GC.
                b.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(s / 24f, BlurMaskFilter.Blur.NORMAL) }, null).also { shadowBmp = it; b.recycle() }
            }
            paint.color = 0x66000000
            canvas.drawBitmap(bmp, l.toFloat(), t + s / 30f, paint)
        }
        icon.setBounds(l, t, l + s, t + s)
        icon.draw(canvas)
        if (badgeCount <= 0 || badgeStyle.style == "off") return
        val r = s * 0.16f * badgeStyle.scale * (if (badgeStyle.style == "dots") 0.8f else 1.25f)
        // Sits half off the icon's corner; IconView lets it draw past the image (#82).
        val cx = if (badgeStyle.pos.endsWith("Right")) l + s - r * 0.45f else l + r * 0.45f
        // Vertically only as far as its own view's blank space, or it covers the label of the row above (or its own).
        val cy = if (badgeStyle.pos.startsWith("top")) t + r - minOf(r * 0.55f, roomUp.toFloat()) else t + s - r + minOf(r * 0.55f, roomDown.toFloat())
        val l2 = (cx - r).toInt(); val t2 = (cy - r).toInt(); val r2 = (cx + r).toInt(); val b2 = (cy + r).toInt()
        if (badgePath == null || badgeRect.left != l2 || badgeRect.top != t2 || badgeRect.right != r2 || badgeRect.bottom != b2) {
            badgeRect.set(l2, t2, r2, b2); badgePath = outlinePath(badgeOutline, badgeRect)
        }
        val path = badgePath!!
        val dyn = badgeIcon
        if (badgeStyle.style == "dynamic" && dyn != null) {
            dyn.bounds = badgeRect
            canvas.save()
            canvas.clipPath(path)
            dyn.draw(canvas)
            canvas.restore()
            return
        }
        paint.color = badgeStyle.color
        canvas.drawPath(path, paint)
        if (badgeStyle.style != "dots") {
            paint.color = if (Color.luminance(badgeStyle.color) > 0.5f) Color.BLACK else Color.WHITE // readable on a bright badge (#82)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = r * 1.2f
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(if (badgeCount > 99) "99+" else "$badgeCount", cx, cy - (paint.ascent() + paint.descent()) / 2, paint)
        }
    }
}

/**
 * Icon plus label. [badgeKey] is the package whose notifications badge it ("" for none, several joined by "|" for folders).
 * A vertical fling on the icon calls [onSwipe] (icon swipe actions) when set.
 */
class IconView(context: Context, label: CharSequence, icon: Drawable, style: IconStyle, badge: BadgeStyle, badgeKey: String) : LinearLayout(context) {
    var badgeKey = badgeKey
        private set
    val image = IconImage(context, icon, style.iconShadow, badge)
    var onSwipe: (() -> Unit)? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var downX = 0f
    private val sizePx = style.sizePx
    private val text: TextView? = if (!style.labels) null else TextView(context).apply {
            text = label
            maxLines = if (style.horizontal) 1 else style.lines
            // Every label takes all its lines, so a one-line label's icon lines up with a two-line one's (#75).
            minLines = maxLines
            typeface = if (style.font == "system") Typeface.DEFAULT else Typeface.create(style.font, Typeface.NORMAL)
            ellipsize = android.text.TextUtils.TruncateAt.END
            textSize = style.labelSize
            gravity = if (style.horizontal) Gravity.START else Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // A little air between icon and label; touching labels look cramped.
            // Side air too, so a label that fills its cell doesn't run into the next one.
            val side = (3 * resources.displayMetrics.density).toInt()
            if (style.horizontal) setPadding(style.sizePx / 3, 0, 0, 0) else setPadding(side, style.sizePx / 10, side, 0)
            if (style.shadow) setShadowLayer(4f, 0f, 1f, 0xAA000000.toInt())
            setTextColor(style.labelColor)
        }

    init {
        orientation = if (style.horizontal) HORIZONTAL else VERTICAL
        gravity = if (style.horizontal) Gravity.CENTER_VERTICAL else Gravity.CENTER
        contentDescription = label
        clipChildren = false // the badge pokes past the icon's corner
        addView(image, LayoutParams(sizePx, sizePx))
        text?.let { addView(it) }
    }

    // A badge on a large icon reaches past its cell too, so the cell's container mustn't clip it either.
    override fun onAttachedToWindow() { super.onAttachedToWindow(); (parent as? ViewGroup)?.clipChildren = false }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val label = text?.takeIf { it.visibility == VISIBLE && orientation == VERTICAL }
        image.roomUp = image.top
        image.roomDown = label?.paddingTop ?: (height - image.bottom)
    }

    /** Shows another app in this view, for lists that recycle their views. */
    fun bind(label: CharSequence, icon: Drawable, badgeKey: String) {
        text?.text = label
        contentDescription = label
        image.icon = icon
        this.badgeKey = badgeKey
        visibility = VISIBLE
    }

    /** Shrinks the icon (and drops the label) when the cell is too small for them, instead of clipping. */
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        if (orientation == VERTICAL) {
            fun avail(spec: Int) = if (MeasureSpec.getMode(spec) == MeasureSpec.UNSPECIFIED) 1 shl 24 else MeasureSpec.getSize(spec)
            val w = avail(widthSpec)
            val labelH = text?.run {
                measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                measuredHeight
            } ?: 0
            val (s, showLabel) = fitIcon(sizePx, w, avail(heightSpec), labelH)
            text?.let { val v = if (showLabel) VISIBLE else GONE; if (it.visibility != v) it.visibility = v }
            image.layoutParams.width = s
            image.layoutParams.height = s
        }
        super.onMeasure(widthSpec, heightSpec)
    }

    /** Launcher3's press feedback: the icon grows to 1.1× while held, so a long-press visibly lifts it. */
    override fun dispatchSetPressed(pressed: Boolean) {
        super.dispatchSetPressed(pressed)
        val s = if (pressed) 1.1f else 1f
        image.animate().scaleX(s).scaleY(s).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent) = false

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val swipe = onSwipe
        if (swipe != null) when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
            MotionEvent.ACTION_MOVE -> {
                // Claim upward drags before the pager takes them as a home swipe.
                if (downY - ev.y > slop / 6 && abs(ev.x - downX) < downY - ev.y) parent?.requestDisallowInterceptTouchEvent(true)
                if (downY - ev.y > slop && abs(ev.x - downX) < downY - ev.y) {
                cancelLongPress()
                swipe()
                ev.action = MotionEvent.ACTION_CANCEL
                downY = Float.MAX_VALUE.unaryMinus() // ignore the rest of this gesture
                return super.onTouchEvent(ev)
                }
            }
        }
        return super.onTouchEvent(ev)
    }
}
