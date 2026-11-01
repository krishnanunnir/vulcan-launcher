package dev.vulcanlauncher

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView

/** Real framework drags: adb shell am instrument -w -e check pages dev.vulcanlauncher.test/dev.vulcanlauncher.DeviceCheck */
class OverviewCheck(private val test: Instrumentation) {
    private lateinit var a: MainActivity
    private val log = StringBuilder()
    private var failures = 0
    private var drags = 0

    private fun ui(block: () -> Unit) = test.runOnMainSync(block)
    private fun settle() { test.waitForIdleSync(); SystemClock.sleep(400) }
    private fun expect(name: String, ok: Boolean) {
        log.append("${if (ok) "PASS" else "FAIL"} $name\n")
        if (!ok) failures++
    }
    private fun views(v: View): List<View> = listOf(v) +
        if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun text(value: String): View = views(a.overlay ?: error("Overview closed")).first {
        it is TextView && it.text.toString() == value
    }
    private fun addTile(): View? = a.overlay?.let { root -> views(root).firstOrNull { it.contentDescription == a.getString(R.string.page_add) } }
    private fun tile(page: Int): View = text(a.getString(R.string.page_n, page + 1)).parent as View
    private fun center(v: View): Pair<Float, Float> {
        val r = Rect()
        ui { check(v.getGlobalVisibleRect(r)) { "View is off screen" } }
        return r.exactCenterX() to r.exactCenterY()
    }
    private fun input(down: Long, action: Int, p: Pair<Float, Float>) {
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, p.first, p.second, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        check(test.uiAutomation.injectInputEvent(e, true))
        e.recycle()
    }
    private fun tap(v: View) {
        val p = center(v)
        val down = SystemClock.uptimeMillis()
        input(down, MotionEvent.ACTION_DOWN, p)
        input(down, MotionEvent.ACTION_UP, p)
        settle()
    }
    private fun drag(page: Int, target: () -> View) {
        val start = center(tile(page))
        val down = SystemClock.uptimeMillis()
        input(down, MotionEvent.ACTION_DOWN, start)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 200L)
        val end = center(target())
        for (i in 1..20) {
            val f = i / 20f
            input(down, MotionEvent.ACTION_MOVE, start.first + (end.first - start.first) * f to start.second + (end.second - start.second) * f)
            SystemClock.sleep(20)
        }
        SystemClock.sleep(150)
        if (drags++ == 0) screenshot("pages-remove-hover.png")
        input(down, MotionEvent.ACTION_UP, end)
        settle()
    }
    private fun remove(page: Int) = drag(page) { text(a.getString(R.string.page_remove)) }
    private fun screenshot(name: String) {
        test.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(test.targetContext.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private val sample get() = listOf(
        Item(0, 0, 0, action = "drawer", label = "First"),
        Item(2, 0, 0, action = "drawer", label = "Third"),
        Item(DOCK, 0, 0, action = "drawer", label = "Dock"))

    private fun setup(count: Int = 3, current: Int = 0) {
        ui {
            a.closeOverlay()
            a.looks["pages"] = count.toString()
            a.looks["defaultPage"] = "0"
            a.looks["lockLayout"] = "off"
            a.items = sample.filter { it.page < count }.toMutableList()
            a.save(); a.rebuild()
            a.workspace.snapTo(current, animate = false)
        }
        settle()
        ui { a.showOverview() }
        settle()
    }
    private fun dialogButton(name: String): Boolean {
        val nodes = test.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("android:id/$name").orEmpty()
        val node = nodes.firstOrNull() ?: return false
        val r = Rect().also(node::getBoundsInScreen)
        val down = SystemClock.uptimeMillis()
        val p = r.exactCenterX() to r.exactCenterY()
        input(down, MotionEvent.ACTION_DOWN, p)
        input(down, MotionEvent.ACTION_UP, p)
        settle()
        return true
    }

    fun run() {
        test.targetContext.startActivity(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        repeat(100) {
            if (MainActivity.running?.get()?.hasWindowFocus() != true) SystemClock.sleep(100)
        }
        a = MainActivity.running?.get() ?: error("Launcher did not start")
        val prefs = test.targetContext.getSharedPreferences("looks", Context.MODE_PRIVATE)
        val savedLooks = prefs.all.toMap()
        val savedItems = a.items.toList()
        try {
            check(savedItems.none { it.isWidget }) { "Run this check on a disposable emulator without widgets" }
            setup()
            remove(1)
            screenshot("pages-after-remove.png")
            expect("empty page drop removes it", a.looks.pages == 2)
            expect("later page contents shift left", a.items.any { it.label == "Third" && it.page == 1 })
            expect("dock survives removal", a.items.any { it.label == "Dock" && it.page == DOCK })
            ui { a.recreate() }
            settle()
            a = MainActivity.running?.get() ?: error("Launcher did not restart")
            expect("removal survives activity restart", a.looks.pages == 2 && a.items.any { it.label == "Third" && it.page == 1 })

            setup()
            remove(0)
            expect("populated page asks for confirmation", dialogButton("button2"))
            expect("cancel preserves pages and items", a.looks.pages == 3 && a.items == sample)
            remove(0)
            expect("confirm button appears", dialogButton("button1"))
            expect("confirm deletes only the chosen page", a.looks.pages == 2 && a.items.none { it.label == "First" } && a.items.any { it.label == "Third" && it.page == 1 })

            setup(1)
            remove(0)
            expect("last page cannot be removed", a.looks.pages == 1 && a.items.any { it.label == "First" })

            setup()
            tap(text(a.getString(R.string.page_n, 3)))
            expect("page label selects default", a.looks.int("defaultPage") == 2 && a.overlay != null)
            drag(2) { tile(0) }
            expect("drag reorders pages", a.items.any { it.label == "Third" && it.page == 0 } && a.items.any { it.label == "First" && it.page == 1 })
            expect("default follows reordered contents", a.looks.int("defaultPage") == 0)
            remove(2)
            expect("default remains valid after removal", a.looks.pages == 2 && a.looks.int("defaultPage") in 0 until a.looks.pages)

            setup()
            // Dropping on the title must leave both the overview and the home layout alone.
            drag(1) { text(a.getString(R.string.pages)) }
            expect("stray drop leaves pages intact", a.looks.pages == 3 && a.items == sample && a.overlay != null)
            tap(tile(2))
            expect("thumbnail opens selected page", a.overlay == null && a.workspace.current == 2)

            setup(1)
            tap(addTile() ?: error("Add page has no accessible label"))
            expect("add creates exactly one page", a.looks.pages == 2 && a.pages.size == 2)
            ui { a.showOverview() }
            settle()
            expect("added page can be selected", views(a.overlay!!).any { it is TextView && it.text.toString() == a.getString(R.string.page_n, 2) })

            setup(9)
            expect("nine-page limit hides Add", addTile() == null && (1..9).all { n -> views(a.overlay!!).any { it is TextView && it.text.toString() == a.getString(R.string.page_n, n) } })

            setup()
            ui { a.looks["lockLayout"] = "on"; a.showOverview() }
            settle()
            expect("locked layout hides Add", addTile() == null)
            drag(1) { tile(0) }
            expect("locked layout prevents page dragging", !tile(1).isLongClickable && a.items == sample && a.looks.pages == 3)
            tap(tile(2))
            expect("locked layout still allows page navigation", a.overlay == null && a.workspace.current == 2)
        } catch (e: Throwable) {
            failures++
            log.append("ERROR ${e.stackTraceToString()}\n")
        } finally {
            dialogButton("button2") // Cancel any removal prompt left by an interrupted check before restoring.
            ui {
                a.closeOverlay()
                prefs.edit().clear().apply { savedLooks.forEach { (key, value) -> putString(key, value.toString()) } }.commit()
                a.items = savedItems.toMutableList(); a.save(); a.rebuild()
            }
        }
        log.append(if (failures == 0) "ALL PAGES CHECKS PASSED\n" else "$failures FAILED\n")
        test.finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", log.toString()) })
    }
}
