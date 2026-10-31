package dev.vulcanlauncher

import android.animation.ValueAnimator
import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/** The page a released swipe settles on, from the scroll position [pos] (in pages): flung pages move one step that way, else the nearest. */
fun flingPage(pos: Float, vx: Float, minFling: Float) = when {
    vx < -minFling -> floor(pos).toInt() + 1
    vx > minFling -> ceil(pos).toInt() - 1
    else -> pos.roundToInt()
}

/** Overscroll past an edge by [over] px, damped so it resists more the further it goes and never passes [limit]. */
fun rubberBand(over: Float, limit: Float) = if (limit <= 0f) 0f else Math.copySign(limit * (1 - 1 / (abs(over) * 0.55f / limit + 1)), over)

/**
 * Horizontally paged screens (home pages, dock pages, drawer pages).
 * Reports gestures by name ("swipeUp", "doubleTap", "pinchIn", …; see [GESTURE_KEYS]) through [onGesture].
 */
class Workspace(context: Context) : ViewGroup(context) {
    var onGesture: (String) -> Unit = {}
    /** Scroll position in pages (0 = first page), for the page indicator and wallpaper. */
    var onScroll: (Float) -> Unit = {}
    /**
     * Optional live swipe up: called with px pulled up so far while the finger moves, then once more with [end] and the
     * upward velocity (px/s). Returning true takes the gesture over, so "swipeUp" isn't reported.
     */
    var onPull: ((up: Float, vy: Float, end: Boolean) -> Boolean)? = null
    private var pulling = false
    var effect = "simple"
    var infinite = false

    var current = 0
        private set
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout()
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var startScroll = 0
    private var mode = NONE
    private var velocity: VelocityTracker? = null
    private var lastTapUp = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var doubleTapDown = false
    private var handledDown = -1L
    private val start = FloatArray(4)
    private val end = FloatArray(4)
    private var anim: ValueAnimator? = null

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        for (i in 0 until childCount) getChildAt(i).measure(widthSpec, heightSpec)
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec))
    }

    // ponytail: pages run left to right in right-to-left languages too (each page's grid is mirrored, see CellLayout);
    // Launcher3 also reverses the page order, which means mirroring scrollX, the transition effects and the edge gestures.
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) getChildAt(i).layout(i * width, 0, (i + 1) * width, height)
        scrollTo(current * width, 0)
    }

    /** [velocity]: the finger's speed (px/s) on release; the page leaves at that speed and slows to a stop. */
    fun snapTo(page: Int, animate: Boolean = true, velocity: Float = 0f) {
        val target = when {
            infinite && childCount > 1 && page >= childCount -> 0
            infinite && childCount > 1 && page < 0 -> childCount - 1
            else -> page.coerceIn(0, maxOf(0, childCount - 1))
        }
        current = target
        anim?.cancel()
        if (!animate || width == 0 || scrollX == current * width) { scrollTo(current * width, 0); return }
        anim = ValueAnimator.ofInt(scrollX, current * width).apply {
            // Flung: a decelerate curve starts at twice its average speed, so this duration matches the finger.
            // Released slowly (or no finger): ease out from rest instead, so the page doesn't lurch off at speed.
            val dist = abs(current * width - scrollX)
            val flung = abs(velocity) >= minFling
            duration = if (flung) (2000 * dist / abs(velocity)).toLong().coerceIn(120, 300) else (220 * maxOf(0.5f, dist / width.toFloat())).toLong()
            interpolator = if (flung) android.view.animation.DecelerateInterpolator() else EASE
            addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
            start()
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (width == 0) return
        // As Launcher3: pages on screen mid-swipe draw from a hardware layer, so the transition effect moves a cached
        // texture instead of redrawing every icon each frame. At rest no page holds one.
        val moving = l % width != 0
        for (i in 0 until childCount) getChildAt(i).apply {
            val o = (i * width - l) / width.toFloat()
            applyTransform(pageTransform(effect, o))
            val type = if (moving && abs(o) < 1f) LAYER_TYPE_HARDWARE else LAYER_TYPE_NONE
            if (layerType != type) setLayerType(type, null)
        }
        onScroll(l / width.toFloat())
    }

    /** True when the point (in this view's coordinates) is on an icon or widget of the current page. */
    private fun onItem(x: Float, y: Float): Boolean {
        val page = getChildAt(current) as? ViewGroup ?: return false
        for (i in 0 until page.childCount) {
            val c = page.getChildAt(i)
            if (c.visibility == View.VISIBLE && x >= c.left && x < c.right && y >= c.top && y < c.bottom) return true
        }
        return false
    }

    /** True when the point is on an icon of the current page that takes its own swipe up (icon swipe actions). */
    private fun swipesItself(x: Float, y: Float): Boolean {
        val page = getChildAt(current) as? ViewGroup ?: return false
        for (i in 0 until page.childCount) {
            val c = page.getChildAt(i)
            if (c is IconView && c.onSwipe != null && c.visibility == View.VISIBLE && x >= c.left && x < c.right && y >= c.top && y < c.bottom) return true
        }
        return false
    }

    /** Shared by intercept and touch handling; returns true once the gesture belongs to the pager. */
    private fun track(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            if (handledDown == ev.downTime) return mode != NONE
            handledDown = ev.downTime
        }
        velocity?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anim?.cancel()
                downX = ev.x; downY = ev.y; downTime = ev.eventTime; startScroll = scrollX; mode = NONE
                velocity?.recycle(); velocity = VelocityTracker.obtain().apply { addMovement(ev) }
                doubleTapDown = ev.eventTime - lastTapUp < doubleTapTimeout && hypot(ev.x - lastTapX, ev.y - lastTapY) < slop * 6 && !onItem(ev.x, ev.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (ev.pointerCount == 2 && mode != HORIZONTAL) {
                mode = MULTI
                for (i in 0..1) { start[i * 2] = ev.getX(i); start[i * 2 + 1] = ev.getY(i) }
                start.copyInto(end)
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                NONE -> {
                    val dx = abs(ev.x - downX)
                    val dy = abs(ev.y - downY)
                    // The page starts moving from where the finger is now, not jumping the slop it took to decide.
                    if (dx > slop && dx > dy) { mode = HORIZONTAL; startScroll += (ev.x - downX).toInt() }
                    // Up from an icon with its own swipe action: left to the icon, even when the first move is a long one.
                    else if (dy > slop && dy > dx) mode = if (ev.y < downY && swipesItself(downX, downY)) CHILD else VERTICAL
                }
                MULTI -> if (ev.pointerCount >= 2) for (i in 0..1) { end[i * 2] = ev.getX(i); end[i * 2 + 1] = ev.getY(i) }
                VERTICAL -> if (!doubleTapDown) pulling = onPull?.invoke(downY - ev.y - slop, 0f, false) == true || pulling
            }
            MotionEvent.ACTION_POINTER_UP -> if (mode == MULTI) {
                twoFingerGesture(start[0], start[1], start[2], start[3], end[0], end[1], end[2], end[3], slop * 4f)?.let(onGesture)
                mode = DONE
            }
            MotionEvent.ACTION_UP -> {
                if (mode == NONE && !onItem(ev.x, ev.y) && ev.eventTime - downTime < ViewConfiguration.getLongPressTimeout()) {
                    if (doubleTapDown) { onGesture("doubleTap"); lastTapUp = 0 }
                    else { lastTapUp = ev.eventTime; lastTapX = ev.x; lastTapY = ev.y }
                }
                finish(ev)
            }
            MotionEvent.ACTION_CANCEL -> finish(ev)
        }
        return mode != NONE && mode != CHILD
    }

    private fun finish(ev: MotionEvent) {
        val tracker = velocity
        tracker?.computeCurrentVelocity(1000)
        val vx = tracker?.xVelocity ?: 0f
        val vy = tracker?.yVelocity ?: 0f
        val dx = ev.x - downX
        val dy = ev.y - downY
        val last = childCount - 1
        when {
            mode == HORIZONTAL && current == 0 && dx > slop * 6 && !infinite && scrollX <= 0 -> { snapTo(0); onGesture("swipeRight") }
            mode == HORIZONTAL && current == last && dx < -slop * 6 && !infinite && scrollX >= last * width -> { snapTo(last); onGesture("swipeLeft") }
            mode == HORIZONTAL -> snapTo(flingPage(scrollX / maxOf(1, width).toFloat(), vx, minFling.toFloat()), velocity = vx)
            mode == VERTICAL && pulling -> onPull?.invoke(-dy - slop, -vy, true)
            mode == VERTICAL && dy < -slop * 3 -> onGesture(if (doubleTapDown) "doubleTapUp" else "swipeUp")
            mode == VERTICAL && dy > slop * 3 -> onGesture(if (doubleTapDown) "doubleTapDown" else "swipeDown")
        }
        if (mode != NONE) lastTapUp = 0
        velocity?.recycle(); velocity = null; mode = NONE; doubleTapDown = false; pulling = false
    }

    override fun onInterceptTouchEvent(ev: MotionEvent) = track(ev)

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        if (mode == HORIZONTAL && ev.actionMasked == MotionEvent.ACTION_MOVE) {
            val max = (childCount - 1) * width
            val x = (startScroll - (ev.x - downX)).toInt()
            val inside = x.coerceIn(0, max)
            scrollTo(if (infinite) inside else inside + rubberBand((x - inside).toFloat(), width / 8f).toInt(), 0)
        }
        return true
    }

    // TalkBack's touch exploration swallows the paging swipe; its scroll actions (two-finger swipe) turn pages instead.
    override fun getAccessibilityClassName(): CharSequence = android.widget.HorizontalScrollView::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = childCount > 1
        if (childCount > 1 && (infinite || current > 0)) info.addAction(AccessibilityAction.ACTION_SCROLL_BACKWARD)
        if (childCount > 1 && (infinite || current < childCount - 1)) info.addAction(AccessibilityAction.ACTION_SCROLL_FORWARD)
    }

    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean {
        val to = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> current + 1
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> current - 1
            else -> return super.performAccessibilityAction(action, args)
        }
        if (childCount < 2) return false
        snapTo(to)
        announceForAccessibility(context.getString(R.string.page_of, current + 1, childCount))
        return true
    }

    private companion object { const val NONE = 0; const val HORIZONTAL = 1; const val VERTICAL = 2; const val MULTI = 3; const val DONE = 4; const val CHILD = 5 }
}
