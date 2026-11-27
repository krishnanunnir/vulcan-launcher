package dev.vulcanlauncher

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout

/**
 * Home's root: the pages, the drawer, the drop bar and whatever is open over them. It decides who owns each touch
 * ([HomeTouch]): a claimant here takes it by intercepting, a child by requestDisallowInterceptTouchEvent.
 */
@SuppressLint("ViewConstructor")
class HomeRoot(private val a: MainActivity) : FrameLayout(a) {
    private val env = object : HomeTouchEnv {
        override fun shows() = if (a.overlays.panel != null || a.overlays.menu != null) HomeShows.OVERLAY else a.drawer.touchState
        override fun overlayOpen() = a.overlays.panel != null || a.overlays.menu != null
        override fun dragPending() = a.drag.waiting
        override fun lift() = a.drag.lift()
        override fun swipeAt(x: Float, y: Float) = (item(x, y)?.first as? IconView)?.onSwipe
        override fun scrollsAt(x: Float, y: Float, dir: Int) = item(x, y)?.let { (v, p) -> v is WidgetFrame && scrollsAt(v, p[0], p[1], dir) } == true
        override fun tapsHome(x: Float, y: Float) = a.homeTaps.any { hits(it, x, y) } && item(x, y) == null
        override fun pullsDrawer() = a.looks.str("g_swipeUp") == "drawer"
        override fun pullFrom(x: Float, y: Float) = a.drawer.pullFrom(this@HomeRoot, x, y)
        override fun pullToSearch() = a.looks.on("pullToSearch")
        override fun tabsAt(x: Float, y: Float) = a.drawer.tabsAt(this@HomeRoot, x, y)
        override fun velocity() = velocity?.let { it.computeCurrentVelocity(1000); floatArrayOf(it.xVelocity, it.yVelocity) } ?: floatArrayOf(0f, 0f)
        override fun pull(up: Float, vy: Float, end: Boolean) { a.drawer.pull(up, vy, end) }
        override fun close(down: Float, vy: Float, end: Boolean) = a.drawer.followClose(down, vy, end)
        override fun search() = a.drawer.focusSearch()
        override fun tabs(dx: Float, end: Boolean, vx: Float, cancelled: Boolean) = a.drawer.followTabs(dx, end, vx, cancelled)
        override fun gesture(name: String) = a.homeMenus.perform(a.looks.str("g_$name"))
    }

    val touch = HomeTouch(env, ViewConfiguration.get(a).scaledTouchSlop.toFloat(), a.dp,
        ViewConfiguration.getDoubleTapTimeout().toLong(), ViewConfiguration.getLongPressTimeout().toLong())
    private val pointer = PointerTrack()
    private var velocity: VelocityTracker? = null

    /** The home or dock item under the point, and the point in the item's own coordinates. */
    private fun item(x: Float, y: Float) = a.workspace.itemAt(this, x, y) ?: a.dock.itemAt(this, x, y)

    private val at = IntArray(2)
    private val origin = IntArray(2)
    /** [v] is showing and the point (in this view's coordinates) is on it. */
    fun hits(v: View, x: Float, y: Float): Boolean {
        if (!v.isShown) return false
        v.getLocationInWindow(at); getLocationInWindow(origin)
        val l = at[0] - origin[0]; val t = at[1] - origin[1]
        return x >= l && x < l + v.width * v.scaleX && y >= t && y < t + v.height * v.scaleY
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_DOWN) { velocity?.recycle(); velocity = VelocityTracker.obtain() }
        velocity?.addMovement(ev)
        pointer.onEvent(ev)
        val two = ev.pointerCount >= 2
        touch.feed(Touch(action, pointer.x, pointer.y, ev.eventTime, ev.pointerCount, if (two) ev.getX(1) else 0f, if (two) ev.getY(1) else 0f))
        val handled = super.dispatchTouchEvent(ev)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            touch.arbiter.ended(action == MotionEvent.ACTION_UP)
            velocity?.recycle(); velocity = null
        }
        return handled
    }

    // A claimant owns the touch: Android cancels every child that had it.
    override fun onInterceptTouchEvent(ev: MotionEvent) = touch.owner != null

    // Every touch is home's to arbitrate, even where no child takes the DOWN.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent) = true

    override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
        if (disallow) touch.arbiter.childClaimed()
        super.requestDisallowInterceptTouchEvent(disallow)
    }

    // A view gone while the state says open: closed, so nothing acts on it later.
    override fun onViewRemoved(child: View) { super.onViewRemoved(child); a.overlays.removed(child) }
}

/** True when a list under the point ([x], [y] in [v]'s coordinates) can still scroll [dir] (1: its content up). */
internal fun scrollsAt(v: View, x: Float, y: Float, dir: Int): Boolean {
    if (v.canScrollVertically(dir)) return true
    if (v !is android.view.ViewGroup) return false
    val px = x + v.scrollX
    val py = y + v.scrollY
    for (i in v.childCount - 1 downTo 0) {
        val c = v.getChildAt(i)
        if (c.visibility == View.VISIBLE && px >= c.left && px < c.right && py >= c.top && py < c.bottom && scrollsAt(c, px - c.left, py - c.top, dir)) return true
    }
    return false
}

/** Runs [f] once when the touch now on home ends, with true for an UP, whether or not the caller sees that end. */
fun MainActivity.whenTouchEnds(f: (up: Boolean) -> Unit) { (root as? HomeRoot)?.touch?.arbiter?.whenEnds(f) }
