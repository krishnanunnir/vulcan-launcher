package dev.vulcanlauncher

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView

/**
 * Home's state has one owner: each overlay kind, opened and then rebuilt under (a setting change, a restart), leaves
 * nothing open and no stale callback, and Back still works; an open folder follows its data.
 * Run: adb shell am instrument -w -e check homestate dev.vulcanlauncher.test/dev.vulcanlauncher.DeviceCheck
 * Disposable emulator without widgets only: it replaces home's layout for the run and puts it back after.
 */
class HomeStateCheck(private val test: Instrumentation) {
    private lateinit var a: MainActivity
    private val log = StringBuilder()
    private var failures = 0

    private fun ui(block: () -> Unit) = test.runOnMainSync(block)
    private fun settle() { test.waitForIdleSync(); SystemClock.sleep(500) }
    private fun expect(name: String, ok: Boolean) {
        log.append("${if (ok) "PASS" else "FAIL"} $name\n")
        if (!ok) failures++
    }
    private fun relaunch() {
        repeat(100) { if (MainActivity.running?.get()?.let { it !== a && it.hasWindowFocus() } != true) SystemClock.sleep(100) }
        a = MainActivity.running?.get() ?: error("Launcher did not restart")
        settle()
    }
    private fun back() { test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle() }
    private fun input(down: Long, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        check(test.uiAutomation.injectInputEvent(e, true))
        e.recycle()
    }
    private fun open() = listOfNotNull(a.overlay, a.homeMenus.menu)

    private lateinit var apps: List<App>
    private val folder get() = a.items.first { it.folder }
    private val action get() = a.items.first { it.action == "drawer" }

    private fun layout(children: Int = 3) = PageLayout(listOf(
        Item(0, 0, 0, folder = true, label = "Folder", children = apps.take(children).map { Item(0, 0, 0, pkg = it.pkg, cls = it.cls, user = it.serial) }),
        Item(0, 1, 0, action = "drawer", label = "Drawer")), 2, 0)

    private fun setup() {
        ui {
            a.overlays.closeAll()
            a.looks["lockLayout"] = "off"; a.looks["nowPage"] = "on"
            a.editLayout(redraw = false) { layout() }
            a.rebuild()
        }
        settle()
    }

    /** How each kind is opened, by hand as its gesture would. */
    private val openers: List<Pair<OverlayKind, () -> Unit>> get() = listOf(
        OverlayKind.FOLDER to { a.openFolder(folder) },
        OverlayKind.OVERVIEW to { a.showOverview() },
        OverlayKind.RESIZE to { val v = a.views.getValue(action); a.showResize(v.parent as CellLayout, action, v, 1, 1, true, true) },
        OverlayKind.NOW to { a.showNow(slide = false) },
        OverlayKind.MENU to { a.homeMenus.showPopup(a.views.getValue(action), action) },
    )

    private fun openKind(kind: OverlayKind) {
        ui { openers.first { it.first == kind }.second() }
        settle()
    }

    private fun rebuiltUnder(kind: OverlayKind, how: String, rebuild: () -> Unit) {
        setup()
        openKind(kind)
        val shown = open().firstOrNull { it.kind == kind }
        expect("$kind opens", shown != null)
        val view = shown?.view
        rebuild()
        expect("$kind/$how: nothing left open (${open().map { it.kind }})", open().isEmpty())
        expect("$kind/$how: its view is gone", view?.isAttachedToWindow != true)
        expect("$kind/$how: home is unblurred", a.depth == 0f)
        // Back on a bare home does nothing harmful, then the same overlay opens again and Back closes it.
        back()
        expect("$kind/$how: Back on home leaves the launcher running", MainActivity.running?.get() === a && !a.isFinishing)
        openKind(kind)
        expect("$kind/$how: opens again", open().any { it.kind == kind })
        back()
        expect("$kind/$how: Back closes it", open().none { it.kind == kind })
    }

    private fun staleCallbacks() {
        setup()
        var fired = false
        ui { a.later(300) { fired = true }; a.rebuild() }
        SystemClock.sleep(700)
        expect("work posted before a rebuild doesn't run after it", !fired)
        var after = false
        ui { a.later(100) { after = true } }
        SystemClock.sleep(400)
        expect("work posted after a rebuild runs", after)

        // fd484c8: a hold in the hold-home overview, then a rebuild before its timer: no page of the new home lifts.
        setup()
        ui { a.homeMenus.homeOverview(animate = false) }
        settle()
        val r = Rect()
        ui { a.workspace.getGlobalVisibleRect(r) }
        val down = SystemClock.uptimeMillis()
        input(down, MotionEvent.ACTION_DOWN, r.exactCenterX(), r.exactCenterY())
        ui { a.rebuild() }
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 400L)
        val lifted = a.drag.dropBar.visibility == View.VISIBLE
        input(down, MotionEvent.ACTION_CANCEL, r.exactCenterX(), r.exactCenterY())
        settle()
        expect("an overview hold from before a rebuild lifts no page of the new home", !lifted)
    }

    private fun folderFollowsData() {
        setup()
        ui { a.openFolder(folder) }
        settle()
        fun icons() = a.overlay?.takeIf { it.kind == OverlayKind.FOLDER }?.view?.let { all(it).filterIsInstance<IconView>() }
        expect("folder shows its 3 apps", icons()?.size == 3)

        // An app in it renamed, as the drawer's Edit does.
        val renamed = apps[0]
        val before = a.repo.customs.optJSONObject(renamed.key)?.toString()
        try {
            ui {
                a.repo.customs.put(renamed.key, org.json.JSONObject().put("label", "Renamed here").put("icon", ""))
                a.store.putObj("custom", a.repo.customs)
                a.repo.loadApps(listOf(renamed.pkg)); a.drawer.refresh(); a.render()
            }
            settle()
            expect("an edit shows in the open folder", a.overlay?.kind == OverlayKind.FOLDER &&
                icons().orEmpty().any { all(it).any { t -> t is TextView && t.text.toString() == "Renamed here" } })
        } finally {
            ui {
                if (before == null) a.repo.customs.remove(renamed.key) else a.repo.customs.put(renamed.key, org.json.JSONObject(before))
                a.store.putObj("custom", a.repo.customs); a.repo.loadApps(listOf(renamed.pkg)); a.render()
            }
        }

        // Uninstalled: the same edit the package callback makes.
        ui { a.editLayout { l -> l.copy(items = removePackage(l.items, apps[1].pkg, apps[1].serial)) } }
        settle()
        expect("an app removed leaves the folder open with 2", a.overlay?.kind == OverlayKind.FOLDER && icons()?.size == 2)
        ui { a.editLayout { l -> l.copy(items = removePackage(l.items, apps[2].pkg, apps[2].serial)) } }
        settle()
        expect("a folder that's gone (one app left) closes", a.overlay == null && a.items.none { it.folder })
    }

    private fun removedViewCloses() {
        setup()
        ui { a.openFolder(folder) }
        settle()
        ui { a.root.removeView(a.overlay!!.view) }
        settle()
        expect("an overlay whose view leaves the root is closed", a.overlay == null)
        back()
        expect("Back after it leaves the launcher running", !a.isFinishing)
    }

    fun run() {
        test.targetContext.startActivity(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        repeat(100) { if (MainActivity.running?.get()?.hasWindowFocus() != true) SystemClock.sleep(100) }
        a = MainActivity.running?.get() ?: error("Launcher did not start")
        val prefs = test.targetContext.getSharedPreferences("looks", Context.MODE_PRIVATE)
        val savedLooks = prefs.all.toMap()
        val savedLayout = a.pageLayout
        try {
            check(savedLayout.items.none { it.isWidget }) { "Run this check on a disposable emulator without widgets" }
            apps = a.repo.apps.values.filter { it.serial == a.repo.apps.values.first().serial }.distinctBy { it.pkg }.take(3)
            check(apps.size == 3) { "Needs three apps" }
            for ((kind, _) in openers) {
                rebuiltUnder(kind, "setting") { ui { a.looks["labels"] = if (a.looks.on("labels")) "off" else "on"; a.rebuild() }; settle() }
                rebuiltUnder(kind, "restart") { val was = a; ui { a.recreate() }; relaunch(); expect("$kind/restart: a new launcher", a !== was) }
            }
            staleCallbacks()
            folderFollowsData()
            removedViewCloses()
        } catch (e: Throwable) {
            failures++
            log.append("ERROR ${e.stackTraceToString()}\n")
        } finally {
            ui {
                a.overlays.closeAll()
                prefs.edit().clear().apply { savedLooks.forEach { (key, value) -> putString(key, value.toString()) } }.commit()
                a.editLayout(redraw = false) { savedLayout }; a.rebuild()
            }
            test.targetContext.getSharedPreferences("layout", Context.MODE_PRIVATE).edit().commit()
            prefs.edit().commit()
        }
        log.append(if (failures == 0) "ALL HOME STATE CHECKS PASSED\n" else "$failures FAILED\n")
        test.finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", log.toString()) })
    }
}
