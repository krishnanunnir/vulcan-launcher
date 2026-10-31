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
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** How an icon looks on one surface (home, dock, drawer, folder). */
class IconStyle(val sizePx: Int, val labels: Boolean, val labelSize: Float, val labelColor: Int, val shadow: Boolean,
                val iconShadow: Boolean, val horizontal: Boolean = false, val font: String = "sans-serif", val lines: Int = 1)

/** Badge settings: style "off" / "dots" / "numeric" / "dynamic". */
class BadgeStyle(val style: String, val pos: String, val scale: Float, val color: Int)

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
    private val badgeClip = android.graphics.Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shadowBmp: Bitmap? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { shadowBmp = null }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height)
        val l = (width - s) / 2
        val t = (height - s) / 2
        if (shadow) {
            val bmp = shadowBmp ?: Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888).also { b ->
                icon.setBounds(0, 0, s, s); icon.draw(Canvas(b))
                shadowBmp = b.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(s / 24f, BlurMaskFilter.Blur.NORMAL) }, null)
            }
            paint.color = 0x66000000
            canvas.drawBitmap(bmp, l.toFloat(), t + s / 30f, paint)
        }
        icon.setBounds(l, t, l + s, t + s)
        icon.draw(canvas)
        if (badgeCount <= 0 || badgeStyle.style == "off") return
        val r = s * 0.16f * badgeStyle.scale * (if (badgeStyle.style == "dots") 0.8f else 1.25f)
        val cx = if (badgeStyle.pos.endsWith("Right")) l + s - r * 0.8f else l + r * 0.8f
        val cy = if (badgeStyle.pos.startsWith("top")) t + r * 0.8f else t + s - r * 0.8f
        val dyn = badgeIcon
        if (badgeStyle.style == "dynamic" && dyn != null) {
            dyn.setBounds((cx - r).toInt(), (cy - r).toInt(), (cx + r).toInt(), (cy + r).toInt())
            canvas.save()
            canvas.clipPath(badgeClip.apply { rewind(); addCircle(cx, cy, r, android.graphics.Path.Direction.CW) })
            dyn.draw(canvas)
            canvas.restore()
            return
        }
        paint.color = badgeStyle.color
        canvas.drawCircle(cx, cy, r, paint)
        if (badgeStyle.style != "dots") {
            paint.color = Color.WHITE
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
            typeface = if (style.font == "system") Typeface.DEFAULT else Typeface.create(style.font, Typeface.NORMAL)
            ellipsize = android.text.TextUtils.TruncateAt.END
            textSize = style.labelSize
            gravity = if (style.horizontal) Gravity.START else Gravity.CENTER
            // A little air between icon and label; touching labels look cramped.
            if (style.horizontal) setPadding(style.sizePx / 3, 0, 0, 0) else setPadding(0, style.sizePx / 10, 0, 0)
            if (style.shadow) setShadowLayer(4f, 0f, 1f, 0xAA000000.toInt())
            setTextColor(style.labelColor)
        }

    init {
        orientation = if (style.horizontal) HORIZONTAL else VERTICAL
        gravity = if (style.horizontal) Gravity.CENTER_VERTICAL else Gravity.CENTER
        contentDescription = label
        addView(image, LayoutParams(sizePx, sizePx))
        text?.let { addView(it) }
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
