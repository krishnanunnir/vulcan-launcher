package dev.vulcanlauncher

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/** Observation audit for open GitHub issues; only run on disposable emulators.
 * Setup uses the app model. Holds, taps and swipes use framework-injected touch input.
 * REPRODUCED describes a finding, rather than an instrumentation assertion failure.
 */
class OpenIssueAuditCheck(private val t: Instrumentation, cases: String = "") {
    private val wanted = cases.split(',').filter(String::isNotBlank)
    private lateinit var a: MainActivity
    private lateinit var out: File
    private var settings: SettingsActivity? = null
    private val log = StringBuilder()
    private fun ui(f: () -> Unit) = t.runOnMainSync(f)
    private fun settle(ms: Long = 400) { t.waitForIdleSync(); SystemClock.sleep(ms) }
    private fun note(id: String, status: String, detail: String) {
        log.append("$id | $status | $detail\n")
        File(out, "results.txt").writeText(log.toString())
    }
    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun field(o: Any, name: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            try { return c.getDeclaredField(name).apply { isAccessible = true }.get(o) }
            catch (_: NoSuchFieldException) { c = c.superclass }
        }
        error("No field $name")
    }
    @Suppress("UNCHECKED_CAST")
    private fun dialogs(v: VulcanActivity = a) = (field(v, "dialogs") as Set<AlertDialog>).filter { it.isShowing }
    private fun bounds(v: View): Rect = Rect().also { r -> ui { require(v.getGlobalVisibleRect(r)) { "Not visible: ${v.contentDescription}" } } }
    private fun point(x: Int, y: Int) = Rect(x, y, x + 1, y + 1)
    private fun event(time: Long, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try {
            val accepted = t.uiAutomation.injectInputEvent(e, true)
            require(accepted || action == MotionEvent.ACTION_UP) { "Input rejected" }
        } finally { e.recycle() }
    }
    private fun tap(v: View) {
        // Dialog bounds are window-relative; injected input needs physical screen coordinates.
        val at = IntArray(2); ui { v.getLocationOnScreen(at) }
        val r = Rect(at[0], at[1], at[0] + v.width, at[1] + v.height)
        val time = SystemClock.uptimeMillis()
        event(time, MotionEvent.ACTION_DOWN, r.exactCenterX(), r.exactCenterY())
        event(time, MotionEvent.ACTION_UP, r.exactCenterX(), r.exactCenterY()); settle()
    }
    private fun drag(from: Rect, to: Rect, hold: Long = 0, duringHold: () -> Unit = {}) {
        val time = SystemClock.uptimeMillis()
        event(time, MotionEvent.ACTION_DOWN, from.exactCenterX(), from.exactCenterY())
        try {
            SystemClock.sleep(hold); duringHold()
            for (i in 1..16) {
                val f = i / 16f
                event(time, MotionEvent.ACTION_MOVE, from.exactCenterX() + (to.exactCenterX() - from.exactCenterX()) * f,
                    from.exactCenterY() + (to.exactCenterY() - from.exactCenterY()) * f)
                SystemClock.sleep(25)
            }
        } finally { event(time, MotionEvent.ACTION_UP, to.exactCenterX(), to.exactCenterY()) }
        settle()
    }
    private fun roots(): List<View> {
        val current = settings?.takeUnless { it.isFinishing } ?: a
        return listOf(current.window.decorView) + dialogs(current).map { it.window!!.decorView }
    }
    private fun shot(name: String) {
        t.uiAutomation.takeScreenshot()?.let { b ->
            File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle()
        }
        val dump = StringBuilder()
        ui { roots().flatMap(::all).filter { it.isShown }.forEach { v ->
            val r = Rect(); v.getGlobalVisibleRect(r)
            dump.append("${v.javaClass.simpleName} $r alpha=${v.alpha} scale=${v.scaleX},${v.scaleY} desc=${v.contentDescription} text=${(v as? TextView)?.text}\n")
        } }
        File(out, "$name.txt").writeText(dump.toString())
    }
    private fun clean() {
        settings?.let { s -> ui { dialogs(s).forEach { it.dismiss() }; s.finish() }; settle() }; settings = null
        ui { dialogs().forEach { it.dismiss() }; a.closeMenu(); a.closeOverlay(); a.drawer.hide(false) }; settle(100)
    }
    private fun setup(vararg prefs: Pair<String, String>) {
        clean()
        ui {
            t.targetContext.getSharedPreferences("looks", 0).edit().clear().commit()
            t.targetContext.getSharedPreferences("data", 0).edit().clear().commit()
            (mapOf("pages" to "3", "nowPage" to "off", "searchBar" to "off", "frequent" to "off", "drawerStyle" to "grid", "nightMode" to "off") + prefs).forEach { (k, v) ->
                require(LOOKS.first { it.key == k }.values.contains(v)) { "Invalid test setting $k=$v" }
                a.looks[k] = v
            }
            a.items = mutableListOf(Item(0, 0, 4, action = "drawer", label = "Audit"))
            a.save(); a.rebuild(); a.workspace.snapTo(0, false)
        }; settle()
    }
    private fun case(id: String, f: () -> Unit) {
        if (wanted.isNotEmpty() && id !in wanted) return
        try { f() } catch (e: Throwable) { note(id, "INCONCLUSIVE", e.stackTraceToString()); runCatching { clean() } }
    }
    private fun text(root: View, value: String) = all(root).filterIsInstance<TextView>().first { it.text.toString() == value }
    private fun tabs() = field(a.drawer, "tabs") as LinearLayout
    private fun selected() = field(a.drawer, "tab").toString()
    private fun grid() = field(a.drawer, "grid") as GridView
    private fun seedTabs() {
        val keys = a.apps.values.filter { !a.isPrivate(it) }.map { it.key }
        require(keys.size >= 4)
        ui { a.saveGroups(listOf(Group("Zulu", "tab", keys.take(4)), Group("Alpha", "tab", keys.takeLast(4)))) }
        ui { a.drawer.show(false) }; settle()
    }
    private fun swipeTabs(forward: Boolean) {
        val r = bounds(grid()); val y = r.top + r.height() / 3
        drag(point(r.left + (r.width() * if (forward) .8f else .2f).toInt(), y),
            point(r.left + (r.width() * if (forward) .2f else .8f).toInt(), y))
    }

    fun run() {
        t.targetContext.startActivity(Intent(t.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        repeat(100) { if (MainActivity.running?.get()?.hasWindowFocus() != true) SystemClock.sleep(100) }
        a = MainActivity.running?.get() ?: error("Launcher did not start")
        out = File(a.filesDir, "open-issue-audit").apply { mkdirs() }
        val original = a.store.export(a.apps.values.toList())
        try {
            note("ENV", "INFO", "API=${android.os.Build.VERSION.SDK_INT}; density=${a.dp}; size=${a.resources.displayMetrics.widthPixels}x${a.resources.displayMetrics.heightPixels}; fontScale=${a.resources.configuration.fontScale}; apps=${a.apps.size}; longPressTimeout=${ViewConfiguration.getLongPressTimeout()}")
            case("#34") {
                for ((name, prefs) in listOf(
                    "no-search" to arrayOf("searchBar" to "off"),
                    "default-search" to arrayOf("searchBar" to "dockBelow"),
                    "no-dock" to arrayOf("dock" to "off"),
                    "system-theme" to arrayOf("accent" to "system", "searchBar" to "dockBelow")
                )) {
                    setup(*prefs)
                    val r = bounds(a.workspace)
                    val p = point(r.centerX(), r.top + r.height() / 3)
                    drag(p, p, ViewConfiguration.getLongPressTimeout() + 250L)
                    require(field(a, "menu") != null) { "Hold did not open menu" }
                    val row = (field(a, "menu") as ViewGroup).getChildAt(0)
                    val card = bounds(a.pages[a.workspace.current]); val buttons = bounds(row)
                    val gap = buttons.top - card.bottom
                    note("#34/$name", if (gap < 0) "REPRODUCED" else "NO_OVERLAP", "card=$card; buttons=$buttons; gapPx=$gap; gapDp=${gap/a.dp}; workspaceScale=${a.workspace.scaleX}")
                    shot("34-$name")
                }
            }
            case("#35") {
                setup(); seedTabs(); shot("35-tabs-before")
                val before = a.drawer.groups().map { it.name }
                val chip = text(tabs(), "Zulu")
                ui { chip.performLongClick() }; settle()
                shot("35-tab-hold")
                note("#35/chip-menu", "OBSERVED", "choices=${dialogs().flatMap { all(it.window!!.decorView) }.filterIsInstance<TextView>().map { it.text.toString() }}")
                val members = a.drawer.groups().associate { it.name to it.apps }
                tap(text(dialogs().last().window!!.decorView, a.getString(R.string.move_right)))
                val moved = a.drawer.groups().map { it.name }
                note("#35/move-right", if (moved == listOf("Alpha", "Zulu")) "PASS" else "FAIL", "before=$before; after=$moved")
                shot("35-tabs-moved")
                clean()
                settings = t.startActivitySync(Intent(t.targetContext, SettingsActivity::class.java).putExtra("page", GROUPS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
                settle(); shot("35-group-settings")
                val s = settings!!
                tap(text(s.root, "Zulu")); shot("35-settings-menu")
                note("#35/settings-menu", "OBSERVED", "choices=${dialogs(s).flatMap { all(it.window!!.decorView) }.filterIsInstance<TextView>().map { it.text.toString() }}")
                tap(text(dialogs(s).last().window!!.decorView, s.getString(R.string.move_left)))
                clean()
                val restored = a.drawer.groups().map { it.name }
                note("#35/move-left", if (restored == before) "PASS" else "FAIL", "settings move restores $restored")
                ui { a.recreate() }; settle(1500)
                a = MainActivity.running?.get() ?: error("No recreated activity")
                ui { a.drawer.show(false) }; settle()
                note("#35/persistence", if (a.drawer.groups().map { it.name } == before && a.drawer.groups().associate { it.name to it.apps } == members) "PASS" else "FAIL", "Activity recreated; order and membership retained: ${a.drawer.groups().map { it.name }}")
                shot("35-persisted")
            }
            case("#36") {
                for (style in listOf("grid", "housing", "glass", "list")) {
                    setup("drawerStyle" to style, "infiniteScroll" to "on"); seedTabs()
                    tap(text(tabs(), "Zulu")); swipeTabs(true)
                    note("#36/$style/control", if (selected() == "Alpha") "EXPECTED" else "CONTROL_FAILED", "Zulu -> ${selected()}")
                    if (selected() != "Alpha") continue
                    shot("36-$style-last-before")
                    swipeTabs(true)
                    note("#36/$style/last", if (selected() == "") "PASS" else "FAIL", "forward boundary swipe: selected=${selected()}; expected All apps")
                    shot("36-$style-last-after")
                    tap(text(tabs(), a.getString(R.string.tab_all))); swipeTabs(false)
                    note("#36/$style/first", if (selected() == "Alpha") "PASS" else "FAIL", "backward boundary swipe: selected=${selected()}; expected Alpha")
                    shot("36-$style-first-after")
                }
            }
            case("#37") {
                for (style in listOf("grid", "housing", "glass", "list", "pages", "grid-dark-bottom", "grid-no-selector")) {
                    val bottom = style.startsWith("grid-")
                    setup("drawerStyle" to if (bottom) "grid" else style,
                        "fillFromBottom" to if (bottom) "on" else "off",
                        "accent" to if (bottom) "system" else "appliance",
                        "nightMode" to if (bottom) "on" else "off")
                    if (bottom) {
                        val keys = a.apps.values.filter { !a.isPrivate(it) }.take(2).map { it.key }
                        ui { a.saveGroups(listOf(Group("Pair", "tab", keys))); a.drawer.show(false) }; settle()
                        tap(text(tabs(), "Pair"))
                    } else { ui { a.drawer.show(false) }; settle() }
                    if (style == "grid-no-selector") ui { grid().selector = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT) }
                    val drawerRoot = a.drawer.root
                    val icon = all(drawerRoot).filterIsInstance<IconView>().first { it.isShown && bounds(it).height() > 0 }
                    val r = bounds(icon.image); shot("37-$style-before")
                    val time = SystemClock.uptimeMillis()
                    event(time, MotionEvent.ACTION_DOWN, r.exactCenterX(), r.exactCenterY())
                    try {
                        SystemClock.sleep(150)
                        shot("37-$style-press-150ms")
                        note("#37/$style/press", "OBSERVED", "elapsedMs=${SystemClock.uptimeMillis()-time}; selector=${grid().selector?.javaClass?.name}")
                        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 150L)
                        shot("37-$style-held")
                        for (i in 1..5) {
                            event(time, MotionEvent.ACTION_MOVE, r.exactCenterX() + i * 30 * a.dp, r.exactCenterY() + i * 14 * a.dp)
                            SystemClock.sleep(35)
                            shot("37-$style-move-$i")
                        }
                        note("#37/$style", "VISUAL_REVIEW_REQUIRED", "icon=${icon.contentDescription}; bounds=$r; dragSource=${field(a,"dragSource")?.javaClass?.simpleName}; dragMoved=${field(a,"dragMoved")}; held and five movement frames saved")
                    } finally {
                        // Cancel the framework drag with Back before releasing, avoiding an accidental drop on home.
                        t.uiAutomation.injectInputEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK), true)
                        t.uiAutomation.injectInputEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), true)
                        event(time, MotionEvent.ACTION_UP, r.exactCenterX() + 150*a.dp, r.exactCenterY() + 70*a.dp)
                    }
                    settle()
                }
            }
            case("#37-control") {
                for (transparent in listOf(false, true)) {
                    setup("drawerStyle" to "grid", "nightMode" to "on", "accent" to "system", "fillFromBottom" to "on")
                    val keys = a.apps.values.filter { !a.isPrivate(it) }.take(2).map { it.key }
                    ui { a.saveGroups(listOf(Group("Pair", "tab", keys))); a.drawer.show(false) }; settle()
                    tap(text(tabs(), "Pair"))
                    if (transparent) ui { grid().selector = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT) }
                    val icon = all(a.drawer.root).filterIsInstance<IconView>().first { it.isShown }
                    val r = bounds(icon.image); val time = SystemClock.uptimeMillis()
                    val name = if (transparent) "transparent" else "stock"
                    event(time, MotionEvent.ACTION_DOWN, r.exactCenterX(), r.exactCenterY())
                    try {
                        for (at in listOf(250L, 500L, 750L)) {
                            SystemClock.sleep(maxOf(0L, time + at - SystemClock.uptimeMillis()))
                            shot("37-control-$name-$at")
                            note("#37-control/$name", "VISUAL_REVIEW_REQUIRED", "requestedAtMs=$at; capturedByMs=${SystemClock.uptimeMillis()-time}; selector=${grid().selector?.javaClass?.name}; paletteDark=${a.m3.dark}")
                        }
                    } finally {
                        t.uiAutomation.injectInputEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK), true)
                        t.uiAutomation.injectInputEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), true)
                        event(time, MotionEvent.ACTION_UP, r.exactCenterX(), r.exactCenterY())
                    }
                    settle()
                }
            }
            case("#39") {
                setup(); ui { a.showOverview() }; settle(700)
                val panel = a.overlay ?: error("No page editor")
                val title = text(panel, a.getString(R.string.pages))
                val tile = all(panel).first { it.contentDescription == a.getString(R.string.page_n, 1) }
                val r = bounds(tile)
                drag(r, r, ViewConfiguration.getLongPressTimeout() + 200L) {
                    val remove = text(panel, a.getString(R.string.page_remove))
                    val tr = bounds(title); val rr = bounds(remove)
                    note("#39", "MEASURED", "title=$tr; remove=$rr; gapPx=${rr.top-tr.bottom}; gapDp=${(rr.top-tr.bottom)/a.dp}; targetHeightDp=${rr.height()/a.dp}")
                    shot("39-remove-page-held")
                }
                shot("39-page-editor-after-release")
            }
            case("#38") {
                setup(); val key = a.apps.values.first { !a.isPrivate(it) }.key
                ui { a.saveGroups(listOf(Group("Audit folder", "folder", listOf(key)))); a.addToGroup(key) }; settle()
                shot("38-folder-picker")
                note("#38", "DESIGN_ONLY", "Folder picker displayed; issue requests icons and visual polish, with no broken add-to-group operation reported")
            }
        } finally {
            runCatching { clean(); ui { a.store.import(original, a::mainActivity); a.items = a.store.load(a.sub); a.rebuild() } }
                .onSuccess { note("RESTORE", "OK", "Original exported configuration restored") }
                .onFailure { note("RESTORE", "ERROR", it.stackTraceToString()) }
        }
        t.finish(Activity.RESULT_OK, Bundle().apply { putString("stream", log.toString()) })
    }
}
