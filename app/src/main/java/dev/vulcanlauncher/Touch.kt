package dev.vulcanlauncher

import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot

/**
 * One touch event as home's root sees it: [x], [y] the finger being followed, in the root's coordinates and kept
 * continuous when that finger lifts ([PointerTrack]); [x2], [y2] the second finger while there is one.
 */
data class Touch(val action: Int, val x: Float, val y: Float, val time: Long = 0, val pointers: Int = 1, val x2: Float = 0f, val y2: Float = 0f)

/**
 * A gesture that may own a touch on home. [may] decides once, at DOWN, whether it may claim this touch at all. Until
 * something owns the touch, [claims] sees every event (in list order) and returns true to take it; from then on it
 * alone sees the rest of the touch, and what it returns no longer matters.
 */
class Claimant(val name: String, val may: (Touch) -> Boolean, val claims: (Touch) -> Boolean)

/**
 * Who owns a touch on home: the first claim, fixed until the touch ends (docs/ARCHITECTURE.md, Touch ownership).
 * A claimant here claims by the root intercepting, so Android sends every child that had the touch a real CANCEL.
 * A child view claims the other way, with requestDisallowInterceptTouchEvent ([childClaimed]): then nothing here
 * gets the touch. [claimants] is read at each DOWN.
 *
 * A touch is also over when something outside it changes what home shows: HOME, BACK, a rebuild ([cut]). Without
 * that the owner went on acting on a screen that had moved on (a drawer pulled back open after HOME closed it).
 */
class TouchArbiter(private val claimants: () -> List<Claimant>) {
    var owner: Claimant? = null
        private set
    /** A child view owns this touch. */
    var childOwns = false
        private set
    /** Cut short ([cut]): what is left of this touch reaches no one. */
    var cut = false
        private set
    private var active = false
    private var last: Touch? = null
    private var live = emptyList<Claimant>()
    private val ends = ArrayList<(Boolean) -> Unit>()

    /** One event. [blocked]: a DOWN no claimant may have (the system's gesture strip). Returns the owner, if any. */
    fun feed(t: Touch, blocked: Boolean = false): Claimant? {
        if (t.action == MotionEvent.ACTION_DOWN) {
            ended(false) // a touch whose end never came this way
            owner = null; childOwns = false; cut = false; active = true
            live = if (blocked) emptyList() else claimants().filter { it.may(t) }
        }
        if (cut) return owner
        last = t
        val o = owner
        if (o != null) o.claims(t) else if (!childOwns) owner = live.firstOrNull { it.claims(t) }
        return owner
    }

    fun childClaimed() { if (owner == null) childOwns = true }

    /**
     * [f] runs once when this touch ends, with true for an UP: whoever owns it, and whether or not the end reached the
     * view that asked. For a long-press or hold timer, whose view may never see the UP (#60, #105).
     */
    fun whenEnds(f: (up: Boolean) -> Unit) { ends += f }

    /**
     * Something outside the touch changed home under it: the owner gets a CANCEL where the finger is, the [whenEnds]
     * callbacks run, and the rest of the touch is dropped until the next DOWN. Call it before making the change, so
     * the change is what stays. Returns false when no touch was in flight.
     */
    fun cut(): Boolean {
        if (!active || cut) return false
        val l = last
        if (l != null) owner?.claims(l.copy(action = MotionEvent.ACTION_CANCEL))
        cut = true
        ended(false)
        return true
    }

    /** The touch ended: an UP ([up]) or a CANCEL. */
    fun ended(up: Boolean) {
        active = false
        live = emptyList()
        if (ends.isEmpty()) return
        val e = ends.toList(); ends.clear(); e.forEach { it(up) }
    }
}

/**
 * Follows one finger through a touch. When it lifts and others stay, the next takes over, offset so the position goes
 * on from where the lifted one was instead of jumping to the new finger (as Launcher3's swipe detector does).
 */
class PointerTrack {
    private var id = -1
    private var ox = 0f
    private var oy = 0f
    var x = 0f
        private set
    var y = 0f
        private set

    /** [ids], [xs], [ys]: every pointer of the event; [index]: the one its action is about. */
    fun onEvent(action: Int, index: Int, ids: IntArray, xs: FloatArray, ys: FloatArray) {
        if (action == MotionEvent.ACTION_DOWN) { id = ids[0]; ox = 0f; oy = 0f }
        var i = ids.indexOf(id).coerceAtLeast(0)
        if (action == MotionEvent.ACTION_POINTER_UP && index == i && ids.size > 1) {
            val n = if (i == 0) 1 else 0
            ox += xs[i] - xs[n]; oy += ys[i] - ys[n]
            id = ids[n]; i = n
        }
        x = xs[i] + ox; y = ys[i] + oy
    }

    fun onEvent(ev: MotionEvent) {
        val n = ev.pointerCount
        onEvent(ev.actionMasked, ev.actionIndex, IntArray(n, ev::getPointerId), FloatArray(n, ev::getX), FloatArray(n, ev::getY))
    }
}

/** What home shows as a touch goes down, which decides who may claim it. A drawer still closing counts as [HOME]. */
enum class HomeShows { HOME, DRAWER, DRAWER_MOVING, OVERLAY }

/** Where a pull down closes the open drawer from: its bar, field or frequent row ([BAR]), or the list at its top. */
enum class PullFrom { NONE, BAR, LIST }

/** What home's claimants ask of its views, and do to them. Coordinates are the root's. */
interface HomeTouchEnv {
    fun shows(): HomeShows
    /** A panel or menu is open now. */
    fun overlayOpen(): Boolean
    /** A long-press is waiting for the finger to move to start its drag. */
    fun dragPending(): Boolean
    fun lift()
    /** The swipe action of the icon under the point, if it has one. */
    fun swipeAt(x: Float, y: Float): (() -> Unit)?
    /** A widget under the point holds a list that can still scroll [dir] (1: its content up, for a finger moving up). */
    fun scrollsAt(x: Float, y: Float, dir: Int): Boolean
    /** On the pages, the dock or the strips under them, and not on an item: where a tap counts toward a double tap. */
    fun tapsHome(x: Float, y: Float): Boolean
    /** Swipe up pulls the drawer with the finger. */
    fun pullsDrawer(): Boolean
    fun pullFrom(x: Float, y: Float): PullFrom
    fun pullToSearch(): Boolean
    /** On the drawer's list, which a sideways swipe moves between tabs. */
    fun tabsAt(x: Float, y: Float): Boolean
    /** The touch's velocity (px/s; x right, y down). */
    fun velocity(): FloatArray
    /** The drawer pulled up from home by [up] px; [end] with the upward release velocity [vy]. */
    fun pull(up: Float, vy: Float, end: Boolean)
    /** The open drawer pulled down by [down] px; [end] with the upward release velocity [vy]. */
    fun close(down: Float, vy: Float, end: Boolean)
    fun search()
    /** The drawer's list moved [dx] px toward another tab; [end] with velocity [vx], [cancelled] if the touch was taken. */
    fun tabs(dx: Float, end: Boolean, vx: Float, cancelled: Boolean)
    /** A home gesture by name ("swipeUp", "doubleTap", "pinchIn", …; see GESTURE_KEYS). */
    fun gesture(name: String)
}

/**
 * Home's claimants, in order: what is open over home, a drag, the drawer's own touches, an icon's swipe, the drawer
 * pull and home's gestures. The pager, scrolling widgets, the letter index and the resize handles are children and
 * claim for themselves. A second finger never makes a new owner: the drawer pulls end where they are, and only
 * home's gestures, already the owner or with no owner yet, read it as a two-finger gesture.
 */
class HomeTouch(
    private val env: HomeTouchEnv,
    private val slop: Float,
    private val dp: Float,
    private val doubleTapTimeout: Long = 300,
    private val longPress: Long = 400,
) {
    val arbiter = TouchArbiter { claimants }
    val owner get() = arbiter.owner

    private var shows = HomeShows.HOME
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastTapUp = Long.MIN_VALUE / 2
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var doubleTapDown = false

    fun feed(t: Touch, blocked: Boolean = false): Claimant? {
        if (t.action == MotionEvent.ACTION_DOWN) start(t)
        return arbiter.feed(t, blocked)
    }

    private fun start(t: Touch) {
        shows = env.shows()
        downX = t.x; downY = t.y; downTime = t.time
        doubleTapDown = shows == HomeShows.HOME && t.time - lastTapUp < doubleTapTimeout &&
            hypot(t.x - lastTapX, t.y - lastTapY) < slop * 6 && env.tapsHome(t.x, t.y)
        lastTapUp = Long.MIN_VALUE / 2 // set again by this touch's UP only if it is a tap
        overlayAtDown = false; swipe = null
        pulling = false; pullDone = false; from = PullFrom.NONE; closing = false; closeDone = false
        tabbing = false; tabsDone = false; mode = NONE
    }

    // Opened by this same touch (a hold's menu, the overview): the rest of the touch is swallowed, not a drawer pull
    // or swipe over it. Not while a drag waits: moving on lifts it.
    private var overlayAtDown = false
    private val overlay = Claimant("overlay", { overlayAtDown = env.overlayOpen(); true }) { env.overlayOpen() && !overlayAtDown && !env.dragPending() }

    private val drag = Claimant("drag", { true }) { t ->
        val go = t.action == MotionEvent.ACTION_MOVE && env.dragPending() && hypot(t.x - downX, t.y - downY) > slop
        if (go) env.lift()
        go
    }

    // Opening or following a finger: dropped, so no app opens or lifts mid-animation.
    private val drawerMoving = Claimant("drawer moving", { shows == HomeShows.DRAWER_MOVING }) { true }

    private var from = PullFrom.NONE
    private var closing = false
    private var closeDone = false
    private var closeStart = 0f
    private val drawerClose = Claimant("drawer close", { from = if (shows == HomeShows.DRAWER) env.pullFrom(it.x, it.y) else PullFrom.NONE; from != PullFrom.NONE }, ::pullDown)

    private fun pullDown(t: Touch): Boolean {
        val down = t.y - downY
        val search = from == PullFrom.LIST && env.pullToSearch()
        if (!closing) {
            // The list claims at the slop: it would start its own scroll there and keep the touch.
            val at = if (from == PullFrom.LIST) slop else 24 * dp
            if (t.action != MotionEvent.ACTION_MOVE || down <= at || down <= abs(t.x - downX)) return false
            closing = true; closeStart = t.y
            if (!search) env.close(0f, 0f, false)
            return true
        }
        if (closeDone) return true
        if (search) {
            if (t.action == MotionEvent.ACTION_MOVE && down > 96 * dp) { closeDone = true; env.search() }
            return true
        }
        val d = t.y - closeStart
        when (t.action) {
            MotionEvent.ACTION_MOVE -> env.close(d, 0f, false)
            // A second finger ends the pull where it is, as a release would (Drawer S1: d8286d0, 339a37a).
            MotionEvent.ACTION_POINTER_DOWN -> { closeDone = true; env.close(d, 0f, true) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { closeDone = true; env.close(d, -env.velocity()[1], true) }
        }
        return true
    }

    private var tabbing = false
    private var tabsDone = false
    private var sideStart = 0f
    private val drawerTabs = Claimant("drawer tabs", { shows == HomeShows.DRAWER && env.tabsAt(it.x, it.y) }, ::tabs)

    private fun tabs(t: Touch): Boolean {
        if (!tabbing) {
            val dx = t.x - downX
            if (t.action != MotionEvent.ACTION_MOVE || abs(dx) <= slop || abs(dx) <= abs(t.y - downY)) return false
            tabbing = true
            sideStart = downX + if (dx > 0) slop else -slop // without jumping the slop it took to decide
        }
        if (tabsDone) return true
        val dx = t.x - sideStart
        when (t.action) {
            MotionEvent.ACTION_MOVE -> env.tabs(dx, false, 0f, false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { tabsDone = true; env.tabs(dx, true, env.velocity()[0], t.action == MotionEvent.ACTION_CANCEL) }
        }
        return true
    }

    // Up from an icon with its own swipe action: that action, ahead of home's swipe up, even on a fast first move (#27).
    private var swipe: (() -> Unit)? = null
    private val iconSwipe = Claimant("icon swipe", { swipe = if (shows == HomeShows.HOME) env.swipeAt(it.x, it.y) else null; swipe != null }) { t ->
        val up = downY - t.y
        val go = t.action == MotionEvent.ACTION_MOVE && up > slop && abs(t.x - downX) < up
        if (go) swipe?.invoke().also { swipe = null }
        go
    }

    private var pulling = false
    private var pullDone = false
    private val drawerPull = Claimant("drawer pull", { shows == HomeShows.HOME && env.pullsDrawer() }, ::pullUp)

    private fun pullUp(t: Touch): Boolean {
        val up = downY - t.y
        if (!pulling) {
            // A double tap's second touch swiping up is its own gesture; a widget list that can still scroll keeps it (#50).
            if (t.action != MotionEvent.ACTION_MOVE || doubleTapDown || up <= slop || up <= abs(t.x - downX) || env.scrollsAt(downX, downY, 1)) return false
            pulling = true
        }
        if (pullDone) return true
        when (t.action) {
            MotionEvent.ACTION_MOVE -> env.pull(up - slop, 0f, false)
            // A second finger ends the pull where it is: left held, the drawer would swallow every touch after.
            MotionEvent.ACTION_POINTER_DOWN -> { pullDone = true; env.pull(up - slop, 0f, true) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { pullDone = true; env.pull(up - slop, -env.velocity()[1], true) }
        }
        return true
    }

    private var mode = NONE
    private val start = FloatArray(4)
    private val end = FloatArray(4)
    private val gestures = Claimant("home gestures", { shows == HomeShows.HOME }, ::gesture)

    private fun gesture(t: Touch): Boolean {
        when (t.action) {
            MotionEvent.ACTION_POINTER_DOWN -> if (t.pointers == 2 && (mode == NONE || mode == VERTICAL)) {
                mode = MULTI
                start[0] = t.x; start[1] = t.y; start[2] = t.x2; start[3] = t.y2
                start.copyInto(end)
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                NONE -> {
                    val dx = abs(t.x - downX); val dy = abs(t.y - downY)
                    if (dy > slop && dy > dx && !env.scrollsAt(downX, downY, if (t.y < downY) 1 else -1)) mode = VERTICAL
                }
                MULTI -> if (t.pointers >= 2) { end[0] = t.x; end[1] = t.y; end[2] = t.x2; end[3] = t.y2 }
            }
            MotionEvent.ACTION_POINTER_UP -> if (mode == MULTI) {
                twoFingerGesture(start[0], start[1], start[2], start[3], end[0], end[1], end[2], end[3], slop * 4f)?.let(env::gesture)
                mode = DONE
            }
            MotionEvent.ACTION_UP -> when (mode) {
                // A tap, which no one else took: the first or second of a double tap.
                NONE -> if (env.tapsHome(t.x, t.y) && t.time - downTime < longPress) {
                    if (doubleTapDown) env.gesture("doubleTap") else { lastTapUp = t.time; lastTapX = t.x; lastTapY = t.y }
                }
                VERTICAL -> {
                    val dy = t.y - downY
                    if (dy < -slop * 3) env.gesture(if (doubleTapDown) "doubleTapUp" else "swipeUp")
                    else if (dy > slop * 3) env.gesture(if (doubleTapDown) "doubleTapDown" else "swipeDown")
                }
            }
        }
        return mode != NONE
    }

    private val claimants = listOf(overlay, drag, drawerMoving, drawerClose, drawerTabs, iconSwipe, drawerPull, gestures)

    private companion object { const val NONE = 0; const val VERTICAL = 1; const val MULTI = 2; const val DONE = 3 }
}
