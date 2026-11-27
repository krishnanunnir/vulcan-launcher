package dev.vulcanlauncher

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

/**
 * Who owns each touch on home ([HomeTouch]), replayed as the touches that broke before, plus the edges (#127):
 * adb shell am instrument -w -e check touch dev.vulcanlauncher.test/dev.vulcanlauncher.DeviceCheck
 * Disposable emulator only: it adds an icon to the first page and puts the layout back after.
 */
class TouchCheck(private val test: Instrumentation) {
    private lateinit var a: MainActivity
    private val log = StringBuilder()
    private var failures = 0

    private fun ui(block: () -> Unit) = test.runOnMainSync(block)
    private fun settle(ms: Long = 700) { test.waitForIdleSync(); SystemClock.sleep(ms) }
    private fun expect(name: String, ok: Boolean, detail: String = "") { log.append("${if (ok) "PASS" else "FAIL"} $name${if (detail.isEmpty()) "" else " ($detail)"}\n"); if (!ok) failures++ }
    private fun skip(name: String, why: String) { log.append("SKIP $name ($why)\n") }

    private fun send(down: Long, action: Int, pts: List<Triple<Int, Float, Float>>) {
        val props = Array(pts.size) { MotionEvent.PointerProperties().apply { id = pts[it].first; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(pts.size) { MotionEvent.PointerCoords().apply { x = pts[it].second; y = pts[it].third; pressure = 1f; size = 1f } }
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, pts.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        ui { a.window.decorView.dispatchTouchEvent(e) }
        e.recycle()
        SystemClock.sleep(12)
    }

    /** Through the system's input pipeline, where its back gesture sees the touch too (dispatching into the window skips it). */
    private fun inject(down: Long, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        check(test.uiAutomation.injectInputEvent(e, true))
        e.recycle()
    }

    private fun root() = a.root as HomeRoot
    private fun owner(): String { var o = ""; ui { o = root().touch.owner?.name ?: "none" }; return o }

    /** One finger from (x0, y0) to (x1, y1), after holding [hold] ms; the touch's owner just before it lifts. */
    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, hold: Long = 0): String {
        val t = SystemClock.uptimeMillis()
        send(t, MotionEvent.ACTION_DOWN, listOf(Triple(0, x0, y0)))
        if (hold > 0) SystemClock.sleep(hold)
        for (i in 1..10) send(t, MotionEvent.ACTION_MOVE, listOf(Triple(0, x0 + (x1 - x0) * i / 10, y0 + (y1 - y0) * i / 10)))
        val o = owner()
        send(t, MotionEvent.ACTION_UP, listOf(Triple(0, x1, y1)))
        return o
    }

    private fun centre(v: View): Pair<Float, Float> { val p = IntArray(2); ui { v.getLocationOnScreen(p) }; return p[0] + v.width / 2f to p[1] + v.height / 2f }
    private fun closeAll() { ui { a.closeOverlay(); a.homeMenus.menu?.let { a.homeMenus.closeMenu() }; a.drawer.hide(animate = false) }; settle() }
    private fun drawerHeld(): Boolean {
        val m = a.drawer.javaClass.getDeclaredField("motion").apply { isAccessible = true }.get(a.drawer)
        return m.javaClass.getDeclaredField("held").apply { isAccessible = true }.get(m) as Boolean
    }

    fun run() {
        test.targetContext.startActivity(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var tries = 0
        while (MainActivity.running?.get()?.let { !it.isDestroyed && it.hasWindowFocus() } != true && tries++ < 100) SystemClock.sleep(100)
        a = MainActivity.running?.get() ?: error("Launcher activity didn't start")
        val before = a.items.toList()
        val pagesBefore = a.pageLayout.pages
        try {
            ui { a.workspace.snapTo(0, animate = false) }
            // An icon with its own swipe up (the drawer), in a free cell of the first page.
            val free = (0 until a.looks.rows).flatMap { y -> (0 until a.looks.cols).map { it to y } }.first { (x, y) -> a.items.none { it.page == 0 && x in it.x until it.x + it.w && y in it.y until it.y + it.h } }
            ui { a.editItems { it += Item(page = 0, x = free.first, y = free.second, action = "lock", label = "TouchCheck", swipe = "drawer") } }
            settle(1200)
            val icon = all(a.pages[0]).first { it is IconView && it.contentDescription == "TouchCheck" }
            val (ix, iy) = centre(icon)
            val w = a.root.width.toFloat(); val h = a.root.height.toFloat()
            val dp = a.dp
            // An empty spot: the middle of the first page, off any item.
            val (ex, ey) = (1..9).flatMap { fx -> (2..7).map { fy -> w * fx / 10 to h * fy / 10 } }.first { (x, y) ->
                var empty = false; ui { empty = a.workspace.itemAt(a.root, x, y) == null && a.dock.itemAt(a.root, x, y) == null }; empty }

            // #27: up from an icon with its own swipe is that icon's, even on a fast first move.
            expect("#27 swipe up on the icon is the icon's", stroke(ix, iy, ix, iy - 120 * dp) == "icon swipe")
            settle(); closeAll()

            // Home's own swipe up from an empty spot: the drawer pull (when Swipe up opens the drawer) or a home gesture.
            val pulls = a.looks.str("g_swipeUp") == "drawer"
            val o = stroke(ex, ey + 100 * dp, ex, ey - 100 * dp)
            expect("control: swipe up on home goes to ${if (pulls) "the drawer pull" else "home's gestures"}", o == if (pulls) "drawer pull" else "home gestures", o)
            settle(); closeAll()

            // #129: hold an icon, then move: the drag owns the touch, no drawer opens under it.
            val d = stroke(ix, iy, ix, iy - 150 * dp, hold = 700)
            settle()
            expect("#129 hold then move is the drag's", d == "drag" && !a.drawer.visible, "owner=$d drawer=${a.drawer.visible}")
            ui { a.drag.reset() }; closeAll()

            // #114: up from the search bar pulls the drawer like the rest of home, with one handler.
            val label = all(a.root).firstOrNull { it is TextView && it.text == a.getString(R.string.search) && it.isShown && !all(a.drawer.root).contains(it) }
            if (label == null || !pulls) skip("#114 swipe up on the search bar", "no home search bar or Swipe up isn't the drawer")
            else {
                val (sx, sy) = centre(label)
                val s = stroke(sx, sy, sx, sy - 200 * dp)
                expect("#114 swipe up on the search bar is the drawer pull", s == "drawer pull", s)
                settle(); closeAll()
            }

            // d8286d0 / 339a37a: a second finger mid-pull ends the pull; the drawer is not left held, and a pinch doesn't start.
            if (!pulls) skip("second finger during home's pull", "Swipe up isn't the drawer")
            else for (first in 0..1) {
                val t = SystemClock.uptimeMillis()
                send(t, MotionEvent.ACTION_DOWN, listOf(Triple(0, ex, ey + 100 * dp)))
                for (i in 1..6) send(t, MotionEvent.ACTION_MOVE, listOf(Triple(0, ex, ey + 100 * dp - 80 * dp * i / 6)))
                val both = listOf(Triple(0, ex, ey + 20 * dp), Triple(1, ex + 80 * dp, ey + 60 * dp))
                send(t, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), both)
                send(t, MotionEvent.ACTION_MOVE, both.map { Triple(it.first, it.second, it.third - 40 * dp) })
                send(t, MotionEvent.ACTION_POINTER_UP or (first shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), both)
                send(t, MotionEvent.ACTION_UP, listOf(both[1 - first]))
                settle()
                var held = true; ui { held = drawerHeld() }
                expect("second finger mid-pull (finger $first lifts first): the drawer isn't left held", !held, "visible=${a.drawer.visible}")
                closeAll()
            }

            // S23 stress run, 0.33.1: HOME or BACK with the finger still down ends that touch; what they closed stays closed.
            if (!pulls) skip("HOME / BACK mid-pull", "Swipe up isn't the drawer")
            else for (key in listOf("HOME", "BACK")) {
                val t = SystemClock.uptimeMillis()
                send(t, MotionEvent.ACTION_DOWN, listOf(Triple(0, ex, ey + 100 * dp)))
                for (i in 1..6) send(t, MotionEvent.ACTION_MOVE, listOf(Triple(0, ex, ey + 100 * dp - 200 * dp * i / 6)))
                if (key == "HOME") ui { test.callActivityOnNewIntent(a, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) } else ui { a.onBackPressed() }
                for (i in 1..4) send(t, MotionEvent.ACTION_MOVE, listOf(Triple(0, ex, ey - 100 * dp - 40 * dp * i)))
                send(t, MotionEvent.ACTION_UP, listOf(Triple(0, ex, ey - 260 * dp)))
                settle(1200)
                var held = true; ui { held = drawerHeld() }
                expect("$key mid-pull: the drawer stays closed", !a.drawer.visible && !held, "visible=${a.drawer.visible} held=$held")
                closeAll()
            }
            run {
                val t = SystemClock.uptimeMillis()
                send(t, MotionEvent.ACTION_DOWN, listOf(Triple(0, ex, ey + 100 * dp)))
                SystemClock.sleep(900)
                val opened = a.overlays.panel != null || a.homeMenus.menu != null
                ui { a.onBackPressed() }
                for (i in 1..8) send(t, MotionEvent.ACTION_MOVE, listOf(Triple(0, ex, ey + 100 * dp - 40 * dp * i)))
                send(t, MotionEvent.ACTION_UP, listOf(Triple(0, ex, ey - 220 * dp)))
                settle(1200)
                if (!opened) skip("hold, BACK, move up", "holding home opened nothing")
                else expect("hold, BACK, move up: no drawer", !a.drawer.visible && a.overlays.panel == null, "drawer=${a.drawer.visible} panel=${a.overlays.panel != null}")
                closeAll()
            }

            // #60: a tap that lands on home's root alone (no child takes it) leaves no long-press behind.
            run {
                val t = SystemClock.uptimeMillis()
                send(t, MotionEvent.ACTION_DOWN, listOf(Triple(0, ex, ey)))
                send(t, MotionEvent.ACTION_UP, listOf(Triple(0, ex, ey)))
                SystemClock.sleep(900)
                expect("#60 a tap leaves no hold menu or overview behind", a.overlays.panel == null && a.homeMenus.menu == null)
                closeAll()
            }

            // #127: the strips where the system's gestures always win get no claimant.
            if (Build.VERSION.SDK_INT >= 30) {
                val s = stroke(w / 2, h - 2, w / 2, h - 200 * dp)
                expect("#127 a swipe from the gesture strip has no claimant", s == "none", s)
                settle(); closeAll()
            } else skip("#127 gesture strip", "API < 30")

            if (Build.VERSION.SDK_INT >= 29) {
                var rects = emptyList<android.graphics.Rect>()
                ui { rects = root().systemGestureExclusionRects }; settle(300); ui { rects = root().systemGestureExclusionRects }
                val full = root().defaultHome
                expect("#127 home excludes ${if (full) "all of itself" else "a 200 dp band"} from Back", rects.size == 1 && rects[0].left == 0 && rects[0].right == a.root.width &&
                    (if (full) rects[0].height() == a.root.height else rects[0].height() <= (200 * dp).toInt() + 1), rects.toString())
                ui { a.drawer.show(false) }; settle()
                ui { rects = root().systemGestureExclusionRects }
                expect("#127 the drawer open clears it", rects.isEmpty(), rects.toString())
                closeAll()

                // The real edge swipe, through the system: from page 2, a swipe in from the left edge turns the page back
                // instead of being taken as Back.
                // A second page to swipe back from; the page count is put back after.
                if (a.pages.size < 2) { ui { a.editLayout { it.addPage() }; a.rebuild() }; settle(1500) }
                if (a.pages.size >= 2) {
                    ui { a.workspace.snapTo(1, animate = false) }; settle()
                    val t = SystemClock.uptimeMillis(); val y = h / 2
                    inject(t, MotionEvent.ACTION_DOWN, 2f, y)
                    for (i in 1..12) { inject(t, MotionEvent.ACTION_MOVE, 2f + w * 0.6f * i / 12, y); SystemClock.sleep(8) }
                    inject(t, MotionEvent.ACTION_UP, 2f + w * 0.6f, y)
                    settle()
                    expect("#127 a swipe in from the left edge turns the page, not Back", a.workspace.current == 0, "page=${a.workspace.current}")
                    ui { a.workspace.snapTo(0, animate = false) }; settle()
                } else skip("#127 edge swipe through the system", "only one page")

                // The resize frame's handles, on an item at the screen's edge: excluded where they are drawn.
                val edgeItem = a.items.first { it.label == "TouchCheck" }
                ui { a.editItems { l -> l[l.indexOf(edgeItem)] = edgeItem.copy(x = 0) } }; settle(1200)
                val moved = a.items.first { it.label == "TouchCheck" }
                val v = all(a.pages[0]).first { it is IconView && it.contentDescription == "TouchCheck" }
                ui { a.showResize(a.pages[0], moved, v, 1, 1, true, true) }; settle()
                val frame = all(a.root).firstOrNull { it is ResizeFrame }
                var frameRects = emptyList<android.graphics.Rect>()
                var rootRects = emptyList<android.graphics.Rect>()
                ui { frameRects = frame?.systemGestureExclusionRects.orEmpty(); rootRects = root().systemGestureExclusionRects }
                val p = IntArray(2); ui { frame?.getLocationOnScreen(p) }
                expect("#127 the resize frame excludes its 4 handles, one at the left edge", frameRects.size == 4 && frameRects.any { it.left + p[0] <= 32 * dp }, frameRects.toString())
                expect("#127 the root's rect is cleared while the frame is open", rootRects.isEmpty(), rootRects.toString())
                closeAll()
            } else skip("#127 exclusion rects", "API < 29")
        } catch (e: Throwable) {
            failures++; log.append("ERROR ${e.stackTraceToString()}\n")
        } finally {
            runCatching {
                closeAll(); ui { a.editLayout { it.copy(pages = pagesBefore) }; a.editItems { it.clear(); it += before }; a.rebuild() }; settle()
            }.onFailure { failures++; log.append("RESTORE ERROR $it\n") }
        }
        log.append(if (failures == 0) "TOUCH CHECK PASSED\n" else "$failures FAILED\n")
        test.finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", log.toString()) })
    }
}
