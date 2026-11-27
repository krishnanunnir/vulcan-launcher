package dev.vulcanlauncher

import android.view.MotionEvent.ACTION_CANCEL
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_POINTER_DOWN
import android.view.MotionEvent.ACTION_POINTER_UP
import android.view.MotionEvent.ACTION_UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays of the touch bugs Vulcan has had, through home's real claim rules ([HomeTouch]) with a stand-in for its
 * views. Each asserts which single action the touch made. A child view's claim (the pager past its slop, a widget's
 * list scrolling, the letter index) is replayed as the [TouchArbiter.childClaimed] call that view makes.
 */
class TouchTest {
    /** Home's views, as far as the claimants ask: each test sets what is under the finger and what is open. */
    private class Home : HomeTouchEnv {
        var shows = HomeShows.HOME
        var overlay = false
        var pending = false
        var iconSwipe: Pair<Float, Float>? = null // an icon with a swipe action, at (x, y) ± 50
        var scrolls: (Int) -> Boolean = { false }
        var pullsDrawer = true
        var pullFrom = PullFrom.NONE
        var pullToSearch = false
        var tabs = false
        var items = emptyList<Pair<Float, Float>>() // icons at (x, y) ± 50, where a tap is the icon's
        val did = mutableListOf<String>()
        val pulls = mutableListOf<Pair<Float, Boolean>>()
        val closes = mutableListOf<Pair<Float, Boolean>>()

        private fun near(p: Pair<Float, Float>?, x: Float, y: Float) = p != null && kotlin.math.abs(p.first - x) < 50 && kotlin.math.abs(p.second - y) < 50
        override fun shows() = shows
        override fun overlayOpen() = overlay
        override fun dragPending() = pending
        override fun lift() { pending = false; did += "lift" }
        override fun swipeAt(x: Float, y: Float): (() -> Unit)? = if (near(iconSwipe, x, y)) { { did += "iconSwipe" } } else null
        override fun scrollsAt(x: Float, y: Float, dir: Int) = scrolls(dir)
        override fun tapsHome(x: Float, y: Float) = items.none { near(it, x, y) } && !near(iconSwipe, x, y)
        override fun pullsDrawer() = pullsDrawer
        override fun pullFrom(x: Float, y: Float) = pullFrom
        override fun pullToSearch() = pullToSearch
        override fun tabsAt(x: Float, y: Float) = tabs
        override fun velocity() = floatArrayOf(0f, 0f)
        override fun pull(up: Float, vy: Float, end: Boolean) { pulls += up to end; if (end) did += "pullEnd" }
        override fun close(down: Float, vy: Float, end: Boolean) { closes += down to end; if (end) did += "closeEnd" }
        override fun search() { did += "search" }
        override fun tabs(dx: Float, end: Boolean, vx: Float, cancelled: Boolean) { if (end) did += if (cancelled) "tabsCancel" else "tabsEnd" }
        override fun gesture(name: String) { did += name }
    }

    private val slop = 10f
    private val home = Home()
    private val touch = HomeTouch(home, slop, dp = 1f)
    private var t = 1000L

    private fun ev(action: Int, x: Float, y: Float, pointers: Int = 1, x2: Float = 0f, y2: Float = 0f) =
        touch.feed(Touch(action, x, y, t.also { t += 16 }, pointers, x2, y2)).also { if (action == ACTION_UP || action == ACTION_CANCEL) touch.arbiter.ended(action == ACTION_UP) }

    /** One finger from ([x], [y]) by ([dx], [dy]) in [steps] moves, then up (or [end]). */
    private fun swipe(x: Float, y: Float, dx: Float, dy: Float, steps: Int = 8, end: Int = ACTION_UP): String? {
        ev(ACTION_DOWN, x, y)
        for (i in 1..steps) ev(ACTION_MOVE, x + dx * i / steps, y + dy * i / steps)
        return ev(end, x + dx, y + dy)?.name
    }

    private fun tap(x: Float, y: Float) { ev(ACTION_DOWN, x, y); ev(ACTION_UP, x, y); t += 80 }

    // --- Home ---

    @Test fun swipeUpPullsTheDrawerOnce() {
        assertEquals("drawer pull", swipe(500f, 1500f, 0f, -600f))
        assertEquals(listOf("pullEnd"), home.did)
    }

    @Test fun swipeDownIsAGesture() {
        assertEquals("home gestures", swipe(500f, 800f, 0f, 400f))
        assertEquals(listOf("swipeDown"), home.did)
    }

    @Test fun issue27_iconSwipeBeatsHomeSwipeUpOnAFastFirstMove() {
        home.iconSwipe = 300f to 1200f
        assertEquals("icon swipe", swipe(300f, 1200f, 0f, -500f, steps = 1))
        assertEquals(listOf("iconSwipe"), home.did)
    }

    @Test fun issue27_iconSwipeBeatsHomeSwipeUpOnALongSwipe() {
        home.iconSwipe = 300f to 1200f
        assertEquals("icon swipe", swipe(300f, 1200f, 5f, -900f, steps = 30))
        assertEquals(listOf("iconSwipe"), home.did)
    }

    @Test fun issue71_aFolderSetToNoneGivesTheSwipeToHome() {
        home.items = listOf(300f to 1200f) // a folder: an item, but no swipe action of its own
        assertEquals("drawer pull", swipe(300f, 1200f, 0f, -500f))
        assertEquals(listOf("pullEnd"), home.did)
    }

    @Test fun issue50_aWidgetListThatCanScrollKeepsTheSwipe() {
        home.scrolls = { it == 1 }
        ev(ACTION_DOWN, 500f, 900f)
        assertNull(ev(ACTION_MOVE, 500f, 880f))
        touch.arbiter.childClaimed() // the widget's list starts scrolling
        for (y in 860 downTo 400 step 40) ev(ACTION_MOVE, 500f, y.toFloat())
        assertNull(ev(ACTION_UP, 500f, 400f))
        assertEquals(emptyList<String>(), home.did)
    }

    @Test fun issue50_aWidgetListAtItsEndGivesTheSwipeToHome() {
        home.scrolls = { it == 1 } // can scroll down only: a swipe down is home's
        assertEquals("home gestures", swipe(500f, 900f, 0f, 400f))
        assertEquals(listOf("swipeDown"), home.did)
    }

    @Test fun issue50_aSidewaysSwipeOnAVerticalWidgetStillTurnsThePage() {
        home.scrolls = { true }
        ev(ACTION_DOWN, 500f, 900f)
        assertNull(ev(ACTION_MOVE, 530f, 902f))
        touch.arbiter.childClaimed() // the pager, past its slop sideways
        for (x in 560..900 step 40) ev(ACTION_MOVE, x.toFloat(), 860f) // drifts up later: still the pager's
        assertNull(ev(ACTION_UP, 900f, 700f))
        assertEquals(emptyList<String>(), home.did)
    }

    @Test fun onceThePagerHasTheTouchNoVerticalSwipeTakesIt() {
        ev(ACTION_DOWN, 500f, 1000f)
        ev(ACTION_MOVE, 530f, 1000f)
        touch.arbiter.childClaimed()
        ev(ACTION_MOVE, 540f, 600f)
        assertNull(ev(ACTION_UP, 540f, 300f))
        assertTrue(home.did.isEmpty() && home.pulls.isEmpty())
    }

    @Test fun issue68_d114a28_theIndicatorAndArrowStripsSwipeAndDoubleTap() {
        // A strip under the pages: no item there, so it is home's like the pages are.
        assertEquals("drawer pull", swipe(500f, 1700f, 0f, -500f))
        tap(500f, 1700f); tap(505f, 1702f)
        assertEquals(listOf("pullEnd", "doubleTap"), home.did)
    }

    @Test fun doubleTapThenSwipeUpIsItsOwnGestureNotAPull() {
        tap(500f, 1000f)
        assertEquals("home gestures", swipe(500f, 1000f, 0f, -400f))
        assertEquals(listOf("doubleTapUp"), home.did)
    }

    @Test fun tapsOnAnIconDontMakeADoubleTap() {
        home.items = listOf(500f to 1000f)
        tap(500f, 1000f); tap(500f, 1000f)
        assertTrue(home.did.isEmpty())
    }

    @Test fun issue114_aSwipeUpFromTheSearchBarIsOnePull() {
        // The bar is not an item: one claimant pulls, once, and ends once (bdd19a1 had the bar pull a second time).
        assertEquals("drawer pull", swipe(500f, 1800f, 0f, -700f))
        assertEquals(1, home.pulls.count { it.second })
        assertEquals(listOf("pullEnd"), home.did)
    }

    @Test fun aDownInTheSystemGestureStripHasNoOwner() {
        touch.feed(Touch(ACTION_DOWN, 500f, 2390f, t), blocked = true)
        for (y in 2370 downTo 1700 step 50) ev(ACTION_MOVE, 500f, y.toFloat())
        assertNull(ev(ACTION_UP, 500f, 1700f))
        assertTrue(home.did.isEmpty() && home.pulls.isEmpty())
    }

    // --- Long-press, drag and what's open ---

    @Test fun issue129_aDragStartsAndItsCancelIsNoSwipe() {
        ev(ACTION_DOWN, 300f, 1200f)
        home.pending = true // the long-press fired; the drag waits for the finger to move
        assertEquals("drag", ev(ACTION_MOVE, 300f, 1150f)?.name)
        for (y in 1100 downTo 500 step 100) ev(ACTION_MOVE, 300f, y.toFloat())
        assertEquals("drag", ev(ACTION_CANCEL, 300f, 500f)?.name) // the system drag takes the touch
        assertEquals(listOf("lift"), home.did)
        assertTrue(home.pulls.isEmpty())
    }

    @Test fun aHoldsMenuOpenedByThisTouchSwallowsTheRest() {
        ev(ACTION_DOWN, 300f, 1200f)
        home.overlay = true // a hold on a locked layout: its menu, no drag
        assertEquals("overlay", ev(ACTION_MOVE, 300f, 1100f)?.name)
        ev(ACTION_MOVE, 300f, 600f); ev(ACTION_UP, 300f, 600f)
        assertTrue(home.did.isEmpty() && home.pulls.isEmpty())
    }

    @Test fun aHoldsMenuWithADragWaitingLetsTheDragGo() {
        ev(ACTION_DOWN, 300f, 1200f)
        home.overlay = true; home.pending = true
        assertEquals("drag", ev(ACTION_MOVE, 300f, 1100f)?.name)
        assertEquals(listOf("lift"), home.did)
    }

    @Test fun withAFolderOpenHomeTakesNoSwipe() {
        home.overlay = true; home.shows = HomeShows.OVERLAY
        assertNull(swipe(500f, 1500f, 0f, -600f))
        assertNull(swipe(500f, 800f, 0f, 400f))
        assertTrue(home.did.isEmpty() && home.pulls.isEmpty())
    }

    @Test fun issue60_105_aHoldTimerEndsWhenTheTouchEndsEvenUnseen() {
        val ends = mutableListOf<Boolean>()
        ev(ACTION_DOWN, 500f, 900f)
        touch.arbiter.whenEnds { ends += it } // a widget's hold timer, started on DOWN
        touch.arbiter.childClaimed() // and the widget itself took the touch: the frame never sees its end
        ev(ACTION_UP, 500f, 900f)
        ev(ACTION_DOWN, 500f, 900f)
        touch.arbiter.whenEnds { ends += it }
        ev(ACTION_MOVE, 500f, 700f) // home's swipe takes this one
        ev(ACTION_CANCEL, 500f, 700f)
        assertEquals(listOf(true, false), ends)
    }

    @Test fun aHoldTimerFromATouchThatNeverEndedStopsAtTheNextDown() {
        val ends = mutableListOf<Boolean>()
        ev(ACTION_DOWN, 500f, 900f)
        touch.arbiter.whenEnds { ends += it }
        ev(ACTION_DOWN, 500f, 900f) // no UP or CANCEL came: the window lost the touch
        assertEquals(listOf(false), ends)
    }

    // --- The drawer ---

    @Test fun commit47d3e23_aTouchWhileTheDrawerMovesIsDropped() {
        home.shows = HomeShows.DRAWER_MOVING
        assertEquals("drawer moving", ev(ACTION_DOWN, 500f, 900f)?.name)
        ev(ACTION_MOVE, 500f, 1300f); ev(ACTION_UP, 500f, 1300f)
        assertTrue(home.did.isEmpty() && home.closes.isEmpty())
    }

    @Test fun aPullDownFromTheBarClosesTheDrawer() {
        home.shows = HomeShows.DRAWER; home.pullFrom = PullFrom.BAR
        assertNull(swipe(500f, 300f, 0f, 20f, steps = 2).also { home.did.clear() }) // 20 dp: still a tap
        assertEquals("drawer close", swipe(500f, 300f, 0f, 500f))
        assertEquals(listOf("closeEnd"), home.did)
    }

    @Test fun theListPullsAtTheSlopBeforeItsOwnScrollTakesIt() {
        home.shows = HomeShows.DRAWER; home.pullFrom = PullFrom.LIST
        ev(ACTION_DOWN, 500f, 900f)
        assertEquals("drawer close", ev(ACTION_MOVE, 500f, 912f)?.name)
    }

    @Test fun pullToSearchSearchesOnce() {
        home.shows = HomeShows.DRAWER; home.pullFrom = PullFrom.LIST; home.pullToSearch = true
        assertEquals("drawer close", swipe(500f, 900f, 0f, 400f, steps = 20))
        assertEquals(listOf("search"), home.did)
        assertTrue(home.closes.isEmpty())
    }

    @Test fun commit9c97a51_theLetterIndexKeepsItsTouch() {
        home.shows = HomeShows.DRAWER; home.pullFrom = PullFrom.LIST
        ev(ACTION_DOWN, 1060f, 900f)
        touch.arbiter.childClaimed() // the index claims on DOWN
        for (y in 920..1500 step 40) ev(ACTION_MOVE, 1060f, y.toFloat())
        assertNull(ev(ACTION_UP, 1060f, 1500f))
        assertTrue(home.did.isEmpty() && home.closes.isEmpty())
    }

    @Test fun issue97_aTabSwipeLandsOnTheRelease() {
        home.shows = HomeShows.DRAWER; home.tabs = true
        assertEquals("drawer tabs", swipe(500f, 900f, -400f, 10f))
        assertEquals(listOf("tabsEnd"), home.did)
    }

    @Test fun aTabSwipeTakenAwayIsCancelled() {
        home.shows = HomeShows.DRAWER; home.tabs = true
        assertEquals("drawer tabs", swipe(500f, 900f, -400f, 10f, end = ACTION_CANCEL))
        assertEquals(listOf("tabsCancel"), home.did)
    }

    // --- A second finger (d8286d0 and 339a37a, both reverted) ---

    @Test fun commit_d8286d0_aSecondFingerOnAnotherViewEndsTheDrawerPullOnce() {
        home.shows = HomeShows.DRAWER; home.pullFrom = PullFrom.BAR
        ev(ACTION_DOWN, 500f, 400f)
        for (y in 420..560 step 20) ev(ACTION_MOVE, 500f, y.toFloat())
        assertEquals("drawer close", ev(ACTION_POINTER_DOWN, 500f, 560f, 2, 300f, 250f)?.name) // the field, beside the row
        ev(ACTION_MOVE, 500f, 700f, 2, 300f, 400f)
        ev(ACTION_POINTER_UP, 500f, 700f, 2, 300f, 400f)
        ev(ACTION_UP, 500f, 700f)
        assertEquals(listOf("closeEnd"), home.did)
        assertEquals("nothing moves the drawer after its end", 1, home.closes.dropWhile { !it.second }.size)
    }

    @Test fun commit_339a37a_thePullsFingerLiftingFirstStillEndsItOnce() {
        home.shows = HomeShows.DRAWER; home.pullFrom = PullFrom.BAR
        ev(ACTION_DOWN, 500f, 400f)
        for (y in 420..560 step 20) ev(ACTION_MOVE, 500f, y.toFloat())
        ev(ACTION_POINTER_DOWN, 500f, 560f, 2, 520f, 600f) // the same view
        ev(ACTION_POINTER_UP, 500f, 560f, 2, 520f, 600f) // the pull's finger lifts first
        ev(ACTION_MOVE, 520f, 700f)
        assertEquals("drawer close", ev(ACTION_UP, 520f, 700f)?.name)
        assertEquals(listOf("closeEnd"), home.did)
        assertEquals(1, home.closes.count { it.second })
    }

    @Test fun aSecondFingerEndsHomesDrawerPullAndMakesNoPinch() {
        ev(ACTION_DOWN, 500f, 1500f)
        for (y in 1480 downTo 1200 step 40) ev(ACTION_MOVE, 500f, y.toFloat())
        ev(ACTION_POINTER_DOWN, 500f, 1200f, 2, 800f, 1200f)
        for (i in 1..6) ev(ACTION_MOVE, 500f - 40 * i, 1200f, 2, 800f + 40 * i, 1200f) // spreads: a pinch out, if it counted
        assertEquals("drawer pull", ev(ACTION_POINTER_UP, 260f, 1200f, 2, 1040f, 1200f)?.name)
        ev(ACTION_UP, 1040f, 1200f)
        assertEquals(listOf("pullEnd"), home.did)
    }

    @Test fun twoFingersWithNoOwnerYetPinch() {
        ev(ACTION_DOWN, 400f, 1000f)
        assertEquals("home gestures", ev(ACTION_POINTER_DOWN, 400f, 1000f, 2, 700f, 1000f)?.name)
        for (i in 1..6) ev(ACTION_MOVE, 400f + 20 * i, 1000f, 2, 700f - 20 * i, 1000f)
        ev(ACTION_POINTER_UP, 520f, 1000f, 2, 580f, 1000f)
        ev(ACTION_UP, 580f, 1000f)
        assertEquals(listOf("pinchIn"), home.did)
    }

    @Test fun aSecondFingerAfterThePagerHasTheTouchMakesNoGesture() {
        ev(ACTION_DOWN, 400f, 1000f)
        ev(ACTION_MOVE, 440f, 1000f)
        touch.arbiter.childClaimed()
        ev(ACTION_POINTER_DOWN, 440f, 1000f, 2, 700f, 1000f)
        for (i in 1..6) ev(ACTION_MOVE, 440f - 30 * i, 1000f, 2, 700f + 30 * i, 1000f)
        assertNull(ev(ACTION_POINTER_UP, 260f, 1000f, 2, 880f, 1000f))
        assertTrue(home.did.isEmpty())
    }

    @Test fun theFollowedFingerLiftingHandsOverWithoutAJump() {
        val p = PointerTrack()
        p.onEvent(ACTION_DOWN, 0, intArrayOf(0), floatArrayOf(100f), floatArrayOf(1000f))
        p.onEvent(ACTION_POINTER_DOWN, 1, intArrayOf(0, 1), floatArrayOf(100f, 600f), floatArrayOf(1000f, 400f))
        p.onEvent(ACTION_MOVE, 0, intArrayOf(0, 1), floatArrayOf(110f, 600f), floatArrayOf(990f, 400f))
        p.onEvent(ACTION_POINTER_UP, 0, intArrayOf(0, 1), floatArrayOf(110f, 600f), floatArrayOf(990f, 400f))
        assertEquals(110f, p.x); assertEquals(990f, p.y) // where the lifted finger was
        p.onEvent(ACTION_MOVE, 0, intArrayOf(1), floatArrayOf(620f), floatArrayOf(380f))
        assertEquals(130f, p.x); assertEquals(970f, p.y) // moves with the other finger from there
    }

    @Test fun theFollowedFingerLiftingBeforeAnyClaimIsNoSwipe() {
        // Two fingers 600 px apart, the first lifts: read raw, the next move would be a 600 px swipe up.
        val p = PointerTrack()
        fun feed(action: Int, index: Int, ids: IntArray, ys: FloatArray) {
            p.onEvent(action, index, ids, FloatArray(ids.size) { 500f }, ys)
            ev(action, p.x, p.y, ids.size, 500f, ys.getOrElse(1) { 0f })
        }
        home.shows = HomeShows.DRAWER; home.pullFrom = PullFrom.BAR // no pinch here: only pulls could claim
        feed(ACTION_DOWN, 0, intArrayOf(0), floatArrayOf(400f))
        feed(ACTION_POINTER_DOWN, 1, intArrayOf(0, 1), floatArrayOf(400f, 1000f))
        feed(ACTION_POINTER_UP, 0, intArrayOf(0, 1), floatArrayOf(400f, 1000f))
        feed(ACTION_MOVE, 0, intArrayOf(1), floatArrayOf(1004f))
        feed(ACTION_UP, 0, intArrayOf(1), floatArrayOf(1004f))
        assertNull(touch.owner)
        assertTrue(home.closes.isEmpty())
    }

    // --- Cut short from outside the touch: HOME, BACK, a rebuild (the S23 stress run on 0.33.1) ---

    @Test fun aCutPullEndsOnceAndTheRestOfTheTouchDoesNothing() {
        // HOME while a finger held the drawer part way: the next MOVE pulled it back open.
        ev(ACTION_DOWN, 500f, 1500f)
        for (i in 1..4) ev(ACTION_MOVE, 500f, 1500f - 60f * i)
        assertEquals("drawer pull", touch.owner?.name)
        assertTrue(touch.arbiter.cut())
        assertTrue(home.pulls.last().second) // ended where the finger was
        val n = home.pulls.size
        ev(ACTION_MOVE, 500f, 1000f); ev(ACTION_UP, 500f, 900f)
        assertEquals(n, home.pulls.size)
        assertEquals(listOf("pullEnd"), home.did)
    }

    @Test fun aHoldThatOpenedTheOverviewThenBackPullsNoDrawer() {
        // Hold, the overview opens, BACK closes it with the finger still down, then the finger moves up: the drawer opened.
        ev(ACTION_DOWN, 500f, 1500f)
        home.overlay = true
        touch.arbiter.cut(); home.overlay = false
        for (i in 1..6) ev(ACTION_MOVE, 500f, 1500f - 60f * i)
        ev(ACTION_UP, 500f, 1140f)
        assertTrue(home.pulls.isEmpty()); assertTrue(home.did.isEmpty())
    }

    @Test fun aCutRunsTheEndCallbacksOnceAndTheNextTouchIsNormal() {
        var ends = 0; var wasUp = true
        ev(ACTION_DOWN, 500f, 1500f)
        touch.arbiter.whenEnds { ends++; wasUp = it }
        assertTrue(touch.arbiter.cut())
        assertFalse(touch.arbiter.cut()) // already cut
        ev(ACTION_UP, 500f, 1500f)
        assertEquals(1, ends); assertFalse(wasUp)
        assertTrue(home.did.isEmpty()) // not a tap toward a double tap either
        assertEquals("drawer pull", swipe(500f, 1500f, 0f, -400f))
        assertEquals(listOf("pullEnd"), home.did)
    }

    @Test fun noTouchInFlightIsNothingToCut() {
        assertFalse(touch.arbiter.cut())
        tap(500f, 1500f)
        assertFalse(touch.arbiter.cut())
    }
}
