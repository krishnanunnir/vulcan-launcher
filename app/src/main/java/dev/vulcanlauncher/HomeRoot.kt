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
        touch.feed(Touch(action, pointer.x, pointer.y, ev.eventTime, ev.pointerCount, if (two) ev.getX(1) else 0f, if (two) ev.getY(1) else 0f),
            blocked = action == MotionEvent.ACTION_DOWN && inGestureStrip(ev))
        // A cut touch reaches no child either: they were cancelled when it was cut.
        dispatching = true
        val handled = touch.arbiter.cut || super.dispatchTouchEvent(ev)
        dispatching = false
        if (cancelOwed) { cancelOwed = false; if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) cancelChildren() }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            touch.arbiter.ended(action == MotionEvent.ACTION_UP)
            velocity?.recycle(); velocity = null
        }
        return handled
    }

    private var dispatching = false
    private var cancelOwed = false

    /**
     * HOME, BACK or a rebuild while a finger is down: the touch ends here ([TouchArbiter.cut]), for home's claimants and
     * for whichever child had it. Called from inside that touch's own dispatch, the children's CANCEL waits for it to return.
     */
    fun cutTouch() {
        if (!touch.arbiter.cut()) return
        if (dispatching) cancelOwed = true else cancelChildren()
    }

    private fun cancelChildren() {
        val now = android.os.SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        super.dispatchTouchEvent(e)
        e.recycle()
    }

    /** Vulcan is the default home: its exclusion rects are exempt from the system's 200 dp cap. Checked on each resume. */
    var defaultHome = true

    fun checkDefaultHome() {
        if (android.os.Build.VERSION.SDK_INT >= 29) defaultHome = a.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_HOME)
    }

    private val band = android.graphics.Rect()
    private val noRects = emptyList<android.graphics.Rect>()

    // While home shows as it is, an edge swipe is home's (a page turn, the Now page), not Back (#127). Anything open over
    // it clears the rect, so Back works there. Not the default home, the system caps the rect at 200 dp per edge: a band
    // centred on the screen. Set only on change, so no work on most frames.
    private val exclude = android.view.ViewTreeObserver.OnPreDrawListener {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val normal = a.overlays.panel == null && a.overlays.menu == null && !a.drawer.visible
            val band200 = (200 * a.dp).toInt()
            if (defaultHome) band.set(0, 0, width, height) else band.set(0, (height - band200) / 2, width, (height + band200) / 2)
            val want = if (normal && width > 0) listOf(band) else noRects
            if (want != systemGestureExclusionRects) systemGestureExclusionRects = want.map { android.graphics.Rect(it) }
        }
        true
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); viewTreeObserver.addOnPreDrawListener(exclude) }
    override fun onDetachedFromWindow() { viewTreeObserver.removeOnPreDrawListener(exclude); super.onDetachedFromWindow() }

    /** A DOWN in the strips where the system's gestures always win (API 30+): no claimant, the system takes it. */
    private fun inGestureStrip(ev: MotionEvent): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 30) return false
        val m = rootWindowInsets?.getInsets(android.view.WindowInsets.Type.mandatorySystemGestures()) ?: return false
        return ev.x < m.left || ev.x >= width - m.right || ev.y < m.top || ev.y >= height - m.bottom
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
