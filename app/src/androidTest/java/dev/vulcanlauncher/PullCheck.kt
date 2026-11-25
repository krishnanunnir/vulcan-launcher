package dev.vulcanlauncher

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText

/**
 * A second finger during a drawer pull must not leave the drawer frozen half-open (audit Drawer S1):
 * adb shell am instrument -w -e check pull dev.vulcanlauncher.test/dev.vulcanlauncher.DeviceCheck
 */
class PullCheck(private val test: Instrumentation) {
    private lateinit var a: MainActivity
    private val log = StringBuilder()
    private var failures = 0

    private fun ui(block: () -> Unit) = test.runOnMainSync(block)
    private fun settle() { test.waitForIdleSync(); SystemClock.sleep(700) }
    private fun expect(name: String, ok: Boolean) { log.append("${if (ok) "PASS" else "FAIL"} $name\n"); if (!ok) failures++ }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()

    private fun send(down: Long, action: Int, pts: List<Triple<Int, Float, Float>>) {
        val props = Array(pts.size) { MotionEvent.PointerProperties().apply { id = pts[it].first; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(pts.size) { MotionEvent.PointerCoords().apply { x = pts[it].second; y = pts[it].third; pressure = 1f; size = 1f } }
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, pts.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        ui { a.window.decorView.dispatchTouchEvent(e) }
        e.recycle()
        SystemClock.sleep(12)
    }

    private fun field() = views(a.drawer.root).first { it is EditText }
    private fun at(v: View, fx: Float): Pair<Float, Float> = IntArray(2).also(v::getLocationOnScreen).let { it[0] + v.width * fx to it[1] + v.height / 2f }

    private fun state(): String {
        val m = a.drawer.javaClass.getDeclaredField("motion").apply { isAccessible = true }.get(a.drawer)
        fun f(n: String) = m.javaClass.getDeclaredField(n).apply { isAccessible = true }.get(m)
        return "held=${f("held")} progress=${f("progress")}"
    }

    private fun open() { ui { a.drawer.show(false) }; settle() }

    /** One finger pulls the search field down by [dy] and lets go. */
    private fun pull(dy: Float) {
        val (x, y) = at(field(), 0.5f)
        val t = SystemClock.uptimeMillis()
        send(t, MotionEvent.ACTION_DOWN, listOf(Triple(0, x, y)))
        for (i in 1..10) send(t, MotionEvent.ACTION_MOVE, listOf(Triple(0, x, y + dy * i / 10)))
        send(t, MotionEvent.ACTION_UP, listOf(Triple(0, x, y + dy)))
    }

    private fun row() = a.drawer.javaClass.getDeclaredField("frequentRow").apply { isAccessible = true }.get(a.drawer) as View

    /** Finger 0 starts a pull on the frequent row; finger 1 lands on the search field (another view sharing the pull); [first] lifts first. */
    private fun twoFingers(first: Int) {
        val (ax, ay) = at(row(), 0.5f)
        val (bx, by) = at(field(), 0.5f)
        val d = 60 * a.dp
        val t = SystemClock.uptimeMillis()
        send(t, MotionEvent.ACTION_DOWN, listOf(Triple(0, ax, ay)))
        for (i in 1..6) send(t, MotionEvent.ACTION_MOVE, listOf(Triple(0, ax, ay + d * i / 6)))
        val both = listOf(Triple(0, ax, ay + d), Triple(1, bx, by + d))
        send(t, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), both)
        send(t, MotionEvent.ACTION_MOVE, both)
        send(t, MotionEvent.ACTION_POINTER_UP or (first shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), both)
        send(t, MotionEvent.ACTION_UP, listOf(both[1 - first]))
    }

    fun run() {
        test.targetContext.startActivity(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var tries = 0
        while (MainActivity.running?.get()?.let { !it.isDestroyed && it.hasWindowFocus() } != true && tries++ < 100) SystemClock.sleep(100)
        a = MainActivity.running?.get() ?: error("Launcher activity didn't start")
        // The frequent row shows only once apps have been launched; the counts are put back after.
        val usage = test.targetContext.getSharedPreferences("usage", 0)
        val counts = usage.getString("launches", null)
        try {
            ui { a.repo.apps.keys.take(3).forEach(a.store::countLaunch) }
            open()
            expect("setup: the frequent row is showing", row().visibility == View.VISIBLE && row().height > 0)
            pull(a.root.height * 0.6f); settle()
            expect("control: one finger pulls the drawer closed", !a.drawer.visible)
            for (first in 0..1) {
                open()
                twoFingers(first); settle()
                // Wherever that left it, the drawer must still answer: if it is still up, one finger closes it. Not opened
                // again first: show() clears the stuck state this looks for.
                log.append("  left: visible=${a.drawer.visible} ${state()}\n")
                if (a.drawer.visible) { pull(a.root.height * 0.6f); settle() }
                expect("after two fingers (finger $first lifts first) the drawer still closes", !a.drawer.visible)
                ui { a.drawer.hide(animate = false) }; settle()
            }
        } catch (e: Throwable) {
            failures++; log.append("ERROR ${e.stackTraceToString()}\n")
        } finally {
            usage.edit().apply { if (counts == null) remove("launches") else putString("launches", counts) }.commit()
        }
        test.finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", log.toString()) })
    }
}
