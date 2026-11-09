package dev.vulcanlauncher

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * On-device check of the home gestures with real multi-touch MotionEvents (which `adb input` can't send), dispatched into the launcher window.
 * Each gesture is mapped to "jump to page N" and the resulting page is checked. Settings are restored afterwards.
 * Run: adb shell am instrument -w dev.vulcanlauncher.test/dev.vulcanlauncher.DeviceCheck  (add -e check import for the import check,
 * -e check readable for the readable backup, -e check pages for the page overview)
 */
class DeviceCheck : Instrumentation() {
    private var mode = "gestures"
    private var issueCases = ""

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        mode = arguments?.getString("check") ?: "gestures"
        issueCases = arguments?.getString("cases").orEmpty()
        start()
    }

    /** -e check import: reads files/test.novabackup (copied in with run-as) through the real import code. */
    private fun importCheck() {
        val f = java.io.File(targetContext.filesDir, "test.novabackup")
        val (layout, prefs) = readLauncherBackup(targetContext, android.net.Uri.fromFile(f))
        log.append("prefs=$prefs subgrid=${layout.subgrid} skipped=${layout.skipped}\n")
        layout.items.forEach { log.append("item page=${it.page} x=${it.x} y=${it.y} ${it.w}x${it.h} pkg=${it.pkg} cls=${it.cls} sc=${it.shortcutId} folder=${it.label.takeIf { _ -> it.folder }} children=${it.children.map { c -> c.pkg + (if (c.isShortcut) ":" + c.shortcutId else "") }}\n") }
        finish(Activity.RESULT_OK, Bundle().apply { putString("stream", log.toString()) })
    }

    /**
     * -e check readable: backs up the current setup, restores a hand-written (AI-style) backup through [Store.import] with
     * the launcher's own app lookup, checks what the recreated launcher shows, then restores the original setup.
     */
    private fun readableCheck() {
        a = launch()
        val original = a.store.export(a.apps.values.toList())
        try {
            val doc = org.json.JSONObject(original)
            expect("format 2", doc.getString("format") == "vulcan-backup-2")
            val listed = doc.getJSONObject("reference").getJSONArray("installedApps")
            expect("installed apps listed (${listed.length()})", (0 until listed.length()).any { listed.getJSONObject(it).getString("pkg") == "com.android.chrome" })
            val ai = """{"format":"vulcan-backup-2","settings":{"cols":5,"rows":"100","labels":false,"labelColor":"#FFB300","dockBg":"housing","accent":"blue"},
                "home":{"items":[{"page":0,"x":0,"y":0,"pkg":"com.android.chrome"},
                {"page":0,"x":1,"y":0,"folder":true,"label":"Google","children":[{"page":0,"x":0,"y":0,"pkg":"com.google.android.gm"},{"page":0,"x":0,"y":0,"pkg":"com.google.android.apps.maps"}]},
                {"page":-1,"x":0,"y":0,"pkg":"com.google.android.dialer"},{"page":0,"x":4,"y":5,"action":"lock","label":"Lock"}]}}"""
            val rejected = a.store.import(ai, a::mainActivity)
            expect("rejected $rejected", rejected.toSet() == setOf("rows=100", "accent=blue"))
            runOnMainSync { a.recreate() }
            SystemClock.sleep(2500)
            a = MainActivity.running?.get() ?: error("Launcher didn't come back")
            val items = a.items.toList()
            val chrome = items.firstOrNull { it.pkg == "com.android.chrome" }
            expect("chrome resolved to ${chrome?.cls}", chrome?.cls == "com.google.android.apps.chrome.Main" && chrome.page == 0 && chrome.x == 0)
            val folder = items.firstOrNull { it.folder }
            expect("folder ${folder?.children?.map { it.cls }}", folder?.children?.size == 2 && folder.children.all { it.cls.isNotEmpty() })
            expect("dock dialer", items.any { it.page == DOCK && it.pkg == "com.google.android.dialer" && it.cls.isNotEmpty() })
            expect("lock action", items.any { it.action == "lock" && it.x == 4 && it.y == 5 })
            expect("settings cols=${a.looks.cols} rows=${a.looks.rows} labels=${a.looks.str("labels")} color=${a.looks.str("labelColor")}",
                a.looks.cols == 5 && a.looks.rows == 6 && !a.looks.on("labels") && a.looks.str("labelColor") == "#FFB300")
            log.append("items: ${items.size}\n")
        } catch (e: Throwable) {
            failures++
            log.append("ERROR ${e.stackTraceToString()}\n")
        } finally {
            runCatching { a.store.import(original, a::mainActivity); runOnMainSync { a.recreate() } }
        }
        log.append(if (failures == 0) "READABLE BACKUP PASSED\n" else "$failures FAILED\n")
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", log.toString()) })
    }

    private fun expect(what: String, ok: Boolean) {
        if (!ok) failures++
        log.append("${if (ok) "PASS" else "FAIL"} $what\n")
    }

    /** Starts the launcher and returns the live instance. */
    private fun launch(): MainActivity {
        // When Vulcan is the default home app Android relaunches it by itself, so startActivitySync would wait for an
        // instance that never comes; start it and pick up whichever instance is live.
        targetContext.startActivity(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var tries = 0
        while (MainActivity.running?.get()?.let { !it.isDestroyed && it.hasWindowFocus() } != true && tries++ < 100) SystemClock.sleep(100)
        return MainActivity.running?.get() ?: error("Launcher activity didn't start")
    }

    private lateinit var a: MainActivity
    private val log = StringBuilder()
    private var failures = 0

    override fun onStart() {
        if (mode == "openissues") return OpenIssueAuditCheck(this, issueCases).run()
        if (mode == "persona") return PersonaCheck(this).run()
        if (mode == "issues") return IssueReproCheck(this, issueCases).run()
        if (mode == "import") return importCheck()
        if (mode == "readable") return readableCheck()
        if (mode == "pages") return OverviewCheck(this).run()
        val looks = targetContext.getSharedPreferences("looks", Context.MODE_PRIVATE)
        val saved = looks.all.toMap()
        var savedItems: List<Item>? = null
        try {
            looks.edit().putString("pages", "3").putString("infiniteScroll", "off").putString("scrollEffect", "simple").commit()
            a = launch()
            // Gesture coordinates must not land on the first-run Launcher settings shortcut.
            savedItems = a.items.toList()
            runOnMainSync { a.items.clear(); a.save() }
            SystemClock.sleep(1500)
            val w = a.workspace.width.toFloat()
            val loc = IntArray(2).also { runOnMainSync { a.workspace.getLocationOnScreen(it) } }
            val top = loc[1].toFloat()
            val h = a.workspace.height.toFloat()
            val cx = w / 2
            val cy = top + h * 0.45f
            fun check(g: String, startPage: Int, target: Int, gesture: () -> Unit) {
                GESTURE_KEYS.forEach { looks.edit().putString(it, "none").commit() }
                looks.edit().putString("g_$g", "page:$target").commit()
                // Rebuild installs live drawer/Now pull callbacks from the gesture settings, just as Settings does.
                runOnMainSync { a.closeOverlay(); a.drawer.hide(); a.rebuild() }
                waitForIdleSync()
                runOnMainSync { a.workspace.snapTo(startPage, animate = false) }
                waitForIdleSync(); SystemClock.sleep(300)
                gesture()
                waitForIdleSync(); SystemClock.sleep(600)
                var page = -1
                runOnMainSync { page = a.workspace.current }
                val ok = page == target
                if (!ok) failures++
                log.append("${if (ok) "PASS" else "FAIL"} $g (page $startPage → $page, expected $target)\n")
            }
            val ex = w * 0.9f       // right column: empty on most layouts, so taps don't hit icons
            val ey = top + h * 0.12f
            check("swipeUp", 0, 2) { drag(ex, top + h * 0.8f, ex, top + h * 0.3f) }
            check("swipeDown", 0, 2) { drag(ex, top + h * 0.3f, ex, top + h * 0.8f) }
            check("swipeUp2", 0, 2) { two(cx - 150, cy + 300, cx + 150, cy + 300, cx - 150, cy - 300, cx + 150, cy - 300) }
            check("swipeDown2", 0, 1) { two(cx - 150, cy - 300, cx + 150, cy - 300, cx - 150, cy + 300, cx + 150, cy + 300) }
            check("pinchIn", 0, 2) { two(cx - 400, cy, cx + 400, cy, cx - 80, cy, cx + 80, cy) }
            check("pinchOut", 0, 1) { two(cx - 80, cy, cx + 80, cy, cx - 400, cy, cx + 400, cy) }
            check("rotateCw", 0, 2) { rotate(cx, cy, 300f, 60.0) }
            check("rotateCcw", 0, 1) { rotate(cx, cy, 300f, -60.0) }
            check("doubleTap", 0, 2) { tap(ex, ey); SystemClock.sleep(80); tap(ex, ey) }
            check("doubleTapUp", 0, 1) { tap(ex, ey + 600); SystemClock.sleep(80); drag(ex, ey + 600, ex, ey, hold = 0) }
            check("doubleTapDown", 0, 2) { tap(ex, ey); SystemClock.sleep(80); drag(ex, ey, ex, ey + 600, hold = 0) }
            check("swipeRight", 0, 2) { drag(w * 0.15f, cy, w * 0.85f, cy) }
            check("swipeLeft", 2, 0) { drag(w * 0.85f, cy, w * 0.15f, cy) }
            log.append(if (failures == 0) "ALL GESTURES PASSED\n" else "$failures FAILED\n")
        } catch (e: Throwable) {
            failures++
            log.append("ERROR ${e.stackTraceToString()}\n")
        } finally {
            looks.edit().clear().apply { saved.forEach { (k, v) -> putString(k, v.toString()) } }.commit()
            savedItems?.let { items -> runOnMainSync { a.items = items.toMutableList(); a.save() } }
            if (::a.isInitialized) runOnMainSync { a.recreate() }
        }
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", log.toString()) })
    }

    /** Delivers the event to the launcher window's view tree (the window fills the screen, so coordinates match). */
    private fun send(e: MotionEvent) {
        runOnMainSync { a.window.decorView.dispatchTouchEvent(e) }
        e.recycle()
    }

    private fun event(down: Long, action: Int, pts: List<Pair<Float, Float>>): MotionEvent {
        val props = Array(pts.size) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(pts.size) { i -> MotionEvent.PointerCoords().apply { x = pts[i].first; y = pts[i].second; pressure = 1f; size = 1f } }
        return MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, pts.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun tap(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        send(event(t, MotionEvent.ACTION_DOWN, listOf(x to y)))
        send(event(t, MotionEvent.ACTION_UP, listOf(x to y)))
    }

    private fun drag(x0: Float, y0: Float, x1: Float, y1: Float, hold: Long = 0) {
        val t = SystemClock.uptimeMillis()
        send(event(t, MotionEvent.ACTION_DOWN, listOf(x0 to y0)))
        SystemClock.sleep(hold)
        for (i in 1..10) {
            val f = i / 10f
            send(event(t, MotionEvent.ACTION_MOVE, listOf(x0 + (x1 - x0) * f to y0 + (y1 - y0) * f)))
            SystemClock.sleep(12)
        }
        send(event(t, MotionEvent.ACTION_UP, listOf(x1 to y1)))
    }

    /** Two fingers moving from (a0, b0) to (a1, b1) along straight lines. */
    private fun two(ax0: Float, ay0: Float, bx0: Float, by0: Float, ax1: Float, ay1: Float, bx1: Float, by1: Float) =
        twoPath { f -> listOf(ax0 + (ax1 - ax0) * f to ay0 + (ay1 - ay0) * f, bx0 + (bx1 - bx0) * f to by0 + (by1 - by0) * f) }

    /** Two fingers on opposite ends of a diameter, turning by [degrees] (positive = clockwise on screen). */
    private fun rotate(cx: Float, cy: Float, r: Float, degrees: Double) = twoPath { f ->
        val t = degrees * f * PI / 180
        listOf((cx + r * cos(PI + t)).toFloat() to (cy + r * sin(PI + t)).toFloat(), (cx + r * cos(t)).toFloat() to (cy + r * sin(t)).toFloat())
    }

    private fun twoPath(at: (Float) -> List<Pair<Float, Float>>) {
        val t = SystemClock.uptimeMillis()
        val start = at(0f)
        send(event(t, MotionEvent.ACTION_DOWN, start.take(1)))
        send(event(t, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), start))
        for (i in 1..12) { send(event(t, MotionEvent.ACTION_MOVE, at(i / 12f))); SystemClock.sleep(12) }
        val end = at(1f)
        send(event(t, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), end))
        send(event(t, MotionEvent.ACTION_UP, end.take(1)))
    }
}
