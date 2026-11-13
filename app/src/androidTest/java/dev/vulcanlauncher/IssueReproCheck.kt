package dev.vulcanlauncher

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs

/** Disposable-emulator issue audit. Setup uses the app model; target gestures use real input events.
 * adb shell am instrument -w -e check issues dev.vulcanlauncher.test/dev.vulcanlauncher.DeviceCheck
 * Writes screenshots, view geometry, observations and restores the original backup in finally.
 */
class IssueReproCheck(private val t: Instrumentation, filter: String = "") {
    private val wanted = filter.split(',').filter { it.isNotEmpty() }
    private lateinit var a: MainActivity
    private val log = StringBuilder()
    private lateinit var out: File
    private var settings: SettingsActivity? = null
    private fun ui(f: () -> Unit) = t.runOnMainSync(f)
    private fun settle(ms: Long = 450) { t.waitForIdleSync(); SystemClock.sleep(ms) }
    private fun note(id: String, result: String, detail: String) { log.append("$id | $result | $detail\n") }
    private fun check(id: String, ok: Boolean, detail: String) = note(id, if (ok) "EXPECTED" else "REPRODUCED", detail)
    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun field(o: Any, name: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            try { return c.getDeclaredField(name).apply { isAccessible = true }.get(o) } catch (_: NoSuchFieldException) { c = c.superclass }
        }
        error("Missing field $name")
    }
    @Suppress("UNCHECKED_CAST")
    private fun dialogs(v: VulcanActivity = a) = (field(v, "dialogs") as Set<AlertDialog>).filter { it.isShowing }
    private fun rect(v: View): Rect = Rect().also { r ->
        var visible=false
        ui { visible=v.getGlobalVisibleRect(r); val p=IntArray(2); v.getLocationOnScreen(p); r.set(p[0],p[1],p[0]+v.width,p[1]+v.height) }
        require(visible) { "Offscreen ${v.contentDescription}" }
    }
    private fun event(down: Long, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        check(t.uiAutomation.injectInputEvent(e, true))
        e.recycle()
    }
    private fun tap(v: View) {
        val r = rect(v); val time = SystemClock.uptimeMillis()
        event(time, MotionEvent.ACTION_DOWN, r.exactCenterX(), r.exactCenterY())
        event(time, MotionEvent.ACTION_UP, r.exactCenterX(), r.exactCenterY()); settle()
    }
    private fun text(root: View, s: String) = all(root).first { it is TextView && it.text.toString() == s }
    private fun dialogText(s: String, v: VulcanActivity = a) = text(dialogs(v).last().window!!.decorView, s)
    private fun back() { t.sendKeyDownUpSync(4); settle() }
    private fun drag(from: Rect, to: Rect, duration: Long = 250, hold: Long = 0, afterHold: () -> Unit = {}) {
        val time = SystemClock.uptimeMillis()
        event(time, MotionEvent.ACTION_DOWN, from.exactCenterX(), from.exactCenterY())
        SystemClock.sleep(hold)
        afterHold()
        for (i in 1..16) {
            val f = i / 16f
            event(time, MotionEvent.ACTION_MOVE, from.exactCenterX() + (to.exactCenterX() - from.exactCenterX()) * f,
                from.exactCenterY() + (to.exactCenterY() - from.exactCenterY()) * f)
            SystemClock.sleep(maxOf(1, duration / 16))
        }
        event(time, MotionEvent.ACTION_UP, to.exactCenterX(), to.exactCenterY()); settle()
    }
    private fun shot(name: String) {
        t.uiAutomation.takeScreenshot()?.let { b -> File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
        val roots = mutableListOf<View>()
        ui { roots += (settings?.takeUnless { it.isFinishing } ?: a).window.decorView; dialogs(settings?.takeUnless { it.isFinishing } ?: a).forEach { roots += it.window!!.decorView } }
        val dump = StringBuilder()
        ui { roots.flatMap(::all).filter { it.isShown }.forEach { v ->
            val r = Rect(); v.getGlobalVisibleRect(r)
            dump.append("${v.javaClass.simpleName} $r desc=${v.contentDescription} text=${(v as? TextView)?.text} size=${(v as? TextView)?.textSize}\n")
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
            mapOf("pages" to "3", "nowPage" to "off", "searchBar" to "off", "drawerStyle" to "grid").plus(prefs).forEach { (k,v) -> a.looks[k] = v }
            a.items = mutableListOf(Item(0, 1, 2, action = "drawer", label = "Repro swipe", swipe = "page:2"),
                Item(0, 0, 4, folder = true, label = "Repro folder", children = children()),
                Item(DOCK, 0, 0, action = "drawer", label = "Dock"))
            a.save(); a.rebuild(); a.workspace.snapTo(0, false)
        }; settle()
    }
    private fun children() = a.apps.values.filter { !a.isPrivate(it) }.take(6).map { Item(0,0,0,pkg=it.pkg,cls=it.cls,user=it.serial) }
    private fun drawer() { ui { a.drawer.show(false) }; settle() }
    private fun grid() = field(a.drawer, "grid") as GridView
    private fun settings(page: String): SettingsActivity {
        clean()
        val s = t.startActivitySync(Intent(t.targetContext, SettingsActivity::class.java).putExtra("page",page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        settings = s; settle(); return s
    }
    private fun case(name: String, f: () -> Unit) {
        if (wanted.isNotEmpty() && name.split(' ').none { it in wanted }) return
        try { f() } catch (e: Throwable) { note(name,"INCONCLUSIVE",e.stackTraceToString()); runCatching { clean() } }
        File(out,"results.txt").writeText(log.toString())
    }

    fun run() {
        t.targetContext.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(t.targetContext.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(800)
        repeat(100) { if (MainActivity.running?.get()?.hasWindowFocus() != true) SystemClock.sleep(100) }
        a = MainActivity.running?.get() ?: error("Launcher did not start")
        out = File(a.filesDir,"issue-repro").apply { mkdirs() }
        val original = a.store.export(a.apps.values.toList())
        try {
            note("ENV","INFO","API ${android.os.Build.VERSION.SDK_INT}; apps=${a.apps.size}; privateUser=${a.privateUser}; privateLocked=${a.privateLocked}")
            case("#33") {
                setup("fillFromBottom" to "on", "frequent" to "off", "drawerCols" to "4", "drawerRows" to "6")
                val selected = a.apps.values.filter { !a.isPrivate(it) }.take(18).let { if (it.size % 4 == 0) it.dropLast(2) else it }
                ui { a.store.putStrings("hidden", a.apps.keys - selected.map { it.key }.toSet()) }; drawer()
                shot("33-bottom-grid")
                val g = grid(); val real = all(g).filterIsInstance<IconView>().filter { it.isShown }
                require(real.size == selected.size) { "Expected ${selected.size} real apps, found ${real.size}" }
                val rows = real.groupBy { rect(it).top }.toSortedMap().map { (y,icons) -> "$y:${icons.size}" }
                val last = real.groupBy { rect(it).top }.maxByOrNull { it.key }?.value?.size ?: 0
                check("#33", last == 4, "${selected.size} apps / 4 cols; rows(top:count)=$rows; bottom real icons=$last; expected bottom row=4")
                note("#26","INFO","fillFromBottom setting available; grid.stackFromBottom=${g.isStackFromBottom}; tab placement captured with #21")
                for (style in listOf("housing","glass","list","pages")) {
                    setup("fillFromBottom" to "on","frequent" to "off","drawerStyle" to style)
                    ui { a.store.putStrings("hidden", a.apps.keys - selected.map { it.key }.toSet()) }; drawer(); shot("33-bottom-$style")
                    note("#33/$style","OBSERVED","stackFromBottom=${grid().isStackFromBottom}; visible icons=${all(grid()).filterIsInstance<IconView>().count { it.isShown }}; pages intentionally excludes this option")
                }
            }
            case("#9 #12 #13") {
                for (style in listOf("housing","glass","grid","list","pages")) {
                    setup("drawerStyle" to style,"drawerSearch" to "off","frequent" to "on")
                    ui { a.store.putObj("launches", JSONObject().apply { a.apps.values.filter { !a.isPrivate(it) }.take(4).forEachIndexed { i,app -> put(app.key,20-i) } }) }; drawer()
                    val row = field(a.drawer,"frequentRow") as LinearLayout
                    check("#9/$style",row.isShown && all(row).filterIsInstance<IconView>().size == 4,"dedicated frequent row icons=${all(row).filterIsInstance<IconView>().size}")
                    check("#12/$style", !(field(a.drawer,"searchBar") as View).isShown,"drawer search off visibility=${(field(a.drawer,"searchBar") as View).visibility}")
                    shot("09-12-$style")
                    if (style != "list") {
                        val heights = listOf(3,9).map { n ->
                            setup("drawerStyle" to style,"drawerRows" to n.toString(),"frequent" to "off"); drawer(); shot("13-$style-$n-rows")
                            val root = if (style == "pages") field(a.drawer,"pager") as View else grid()
                            all(root).filterIsInstance<IconView>().first().height
                        }
                        check("#13/$style",heights[0] > heights[1],"3/9 rows measured icon-cell heights=$heights px")
                    }
                }
            }
            case("#21 #22 #23 #25") {
                setup("frequent" to "off")
                val keys = a.apps.values.filter { !a.isPrivate(it) }.take(6).map { it.key }
                ui { a.store.data.edit().putString("groups",groupsToJson(listOf(Group("Repro group","folder",keys),Group("Tab one","tab",keys.take(2)),Group("Tab two","tab",keys.takeLast(2))))).commit() }; drawer()
                tap(all(grid()).filterIsInstance<IconView>().first { it.contentDescription == "Repro group" }); shot("22-folder-open")
                check("#22/open",a.overlay != null && a.drawer.visible,"folder and drawer both open")
                back(); shot("22-back-to-drawer")
                check("#22/back",a.overlay == null && a.drawer.visible,"Back leaves drawer visible=${a.drawer.visible}")
                val r=rect(grid()); drag(Rect(r.centerX()+250,r.centerY(),r.centerX()+250,r.centerY()),Rect(r.centerX()-250,r.centerY(),r.centerX()-250,r.centerY()))
                val next=(field(a.drawer,"tabKeys") as List<*>)[1]
                check("#21",field(a.drawer,"tab") == next,"left swipe selected=${field(a.drawer,"tab")}, expected=$next"); shot("21-tab-swipe")
                setup("folderWindow" to "fullscreen")
                val folder=a.items.first { it.folder }; tap(a.views[folder]!!); shot("23-fullscreen-folder")
                val cells=all(a.overlay!!).filterIsInstance<CellLayout>().single(); val cr=rect(cells)
                check("#23",abs(cr.centerY()-a.root.height/2) < a.root.height/4,"folder grid=$cr screenHeight=${a.root.height}")
                setup("folderWindow" to "popup", "folderCols" to "3", "folderRows" to "1")
                val f25=a.items.first { it.folder }; tap(a.views[f25]!!); shot("25-folder-3x1")
                val g25=all(a.overlay!!).filterIsInstance<CellLayout>().single(); val sv25=g25.parent as View
                check("#25/grid",g25.cols==3 && g25.rows==2 && sv25.height*2 <= g25.height+2,"6 apps, Columns 3, Rows 1: cols=${g25.cols} rows=${g25.rows} shown=${sv25.height}px of ${g25.height}px")
                clean(); val s=settings(FOLDERS); shot("25-folder-settings")
                check("#25",LOOKS.any { it.group==FOLDERS && it.key.contains("Cols") } && LOOKS.any { it.group==FOLDERS && it.key.contains("Rows") },"folder settings keys=${LOOKS.filter { it.group==FOLDERS }.map { it.key }}")
            }
            case("#1") {
                setup(); val folder=a.items.first { it.folder }; tap(a.views[folder]!!)
                val c=all(a.overlay!!).filterIsInstance<CellLayout>().single(); val icons=all(c).filterIsInstance<IconView>()
                val before=folder.children.map { it.appKey }; drag(rect(icons[0]),rect(icons[2]),500,ViewConfiguration.getLongPressTimeout()+200L)
                val after=a.items.first { it.folder }.children.map { it.appKey }
                check("#1/reorder",before!=after && after[2]==before[0],"folder reorder before=$before after=$after"); shot("01-folder-reordered")
                setup(); val controlFolder=a.items.first { it.folder }; tap(a.views[controlFolder]!!)
                val controlIcons=all(a.overlay!!).filterIsInstance<IconView>()
                drag(rect(controlIcons[0]),rect(controlIcons[2]),500,ViewConfiguration.getLongPressTimeout()+200L) { ui { a.closeMenu() } }
                val controlled=a.items.first { it.folder }.children.map { it.appKey }
                note("#1/reorder-control","DIAGNOSTIC","same drag with long-press menu suppressed: children=$controlled; expected target=${before[0]} at index2")
                clean(); ui { a.workspace.snapTo(0,false) }; settle()
                val r=rect(a.workspace); drag(Rect(150,r.centerY(),150,r.centerY()),Rect(900,r.centerY(),900,r.centerY()))
                check("#1/now",a.overlay==null && a.workspace.current==0,"Now page disabled: overlay=${a.overlay!=null}, page=${a.workspace.current}")
                setup(); val f=a.items.first { it.folder }; tap(a.views[f]!!)
                val card=(a.overlay as ViewGroup).getChildAt(0); val cardR=rect(card); val dockR=rect(a.dockBox)
                check("#1/dock",cardR.bottom<=dockR.top,"popup=$cardR dock=$dockR"); shot("01-popup-dock")
                note("#1/smoothness","LIMITED","Swipe executed; subjective smoothness and reporter device frame rate not established by emulator")
            }
            case("#20 #27") {
                setup(); val icon=a.views[a.items.first { it.label=="Repro swipe" }]!!; val r=rect(icon)
                val time=SystemClock.uptimeMillis(); event(time,MotionEvent.ACTION_DOWN,r.exactCenterX(),r.exactCenterY())
                SystemClock.sleep(ViewConfiguration.getLongPressTimeout()+250L)
                check("#20",a.menu!=null,"context menu visible while finger still down=${a.menu!=null}"); shot("20-menu-during-hold")
                event(time,MotionEvent.ACTION_UP,r.exactCenterX(),r.exactCenterY()); clean()
                setup()
                val swipeIcon=a.views[a.items.first { it.label=="Repro swipe" }]!!
                var passes=0
                for ((distance,duration) in listOf(35 to 100L,70 to 180L,150 to 150L,300 to 180L,450 to 300L,35 to 350L,70 to 450L,150 to 300L,300 to 450L,450 to 450L)) {
                    ui { a.workspace.snapTo(0,false); a.drawer.hide(false) }; settle(500)
                    val rr=rect(swipeIcon); val cy=rr.exactCenterY().toInt(); val x=rr.centerX()
                    drag(Rect(x,cy,x,cy),Rect(x,cy-distance,x,cy-distance),duration)
                    val ok=a.workspace.current==2 && !a.drawer.visible; if(ok) passes++
                    note("#27/swipe","OBSERVED","distance=${distance}px duration=${duration}ms page=${a.workspace.current} drawer=${a.drawer.visible}")
                }
                check("#27",passes==10,"$passes/10 icon swipe actions reached page 3"); shot("27-swipe-result")
            }
            case("#18 #1/widgets") {
                setup(); ui { a.pickWidget() }; settle(); shot("18-widget-picker")
                // One row per app (title, "N widgets"), A to Z; opening one shows its widgets under it, A to Z.
                val d=dialogs().last(); val list=all(d.window!!.decorView).filterIsInstance<android.widget.ScrollView>().mapNotNull { it.getChildAt(0) as? LinearLayout }.maxBy { it.childCount }
                fun texts(v: View)=all(v).filterIsInstance<TextView>().map { it.text.toString() }
                val headers=(0 until list.childCount).map { list.getChildAt(it) }
                val apps=headers.map { texts(it).first() }; val order=labelOrder()
                check("#18",apps==apps.sortedWith(compareBy(order){it}) && apps.size==apps.distinct().size,"app rows=$apps")
                val multi=headers.first { texts(it).getOrNull(1)?.startsWith("1 ")==false }
                ui { multi.performClick() }; settle(); shot("18-widget-picker-open")
                val at=list.indexOfChild(multi); val widgets=(at+1 until list.childCount).map { list.getChildAt(it) }.takeWhile { it.contentDescription!=null }.map { it.contentDescription.toString().substringBefore("  (") }
                check("#18/open",widgets.size>1 && widgets==widgets.sortedWith(compareBy(order){it}),"${texts(multi)} opens $widgets")
                check("#1/widgets",all(list).filterIsInstance<ImageView>().any { it.drawable!=null },"visible widget preview/icon images=${all(list).filterIsInstance<ImageView>().size}")
            }
            case("#10 #30") {
                setup(); val item=a.items.first(); ui { a.editItem(item) }; settle(); shot("10-edit")
                val d=dialogs().last(); check("#10/edit",all(d.window!!.decorView).filterIsInstance<EditText>().isNotEmpty(),"edit has label field and icon; inspect screenshot")
                back(); ui { a.pickIcon { } }; settle(); shot("10-icon-picker")
                val packs=iconPacks(a)
                if(packs.isNotEmpty()) {
                    val picker=dialogs().last()
                    ui { picker.listView.performItemClick(picker.listView.getChildAt(1),1,1) }; settle(900); shot("10-pack-picker")
                    val window=t.uiAutomation.rootInActiveWindow
                    fun nodes(n: android.view.accessibility.AccessibilityNodeInfo): List<android.view.accessibility.AccessibilityNodeInfo> = listOf(n)+(0 until n.childCount).mapNotNull { n.getChild(it) }.flatMap(::nodes)
                    val active=window?.let(::nodes).orEmpty()
                    check("#10/pack",active.any { it.className=="android.widget.EditText" } && active.any { it.className=="android.widget.GridView" },"full-screen icon pack grid with search; pack=${packs.first()}")
                    back()
                } else back()
                clean()
                a.javaClass.getDeclaredMethod("addShortcutMenu").apply { isAccessible=true }.let { m -> ui { m.invoke(a) } }; settle()
                val choose=dialogs().last()
                ui { choose.listView.performItemClick(choose.listView.getChildAt(1),1,1) }; settle(); shot("30-app-shortcuts")
                val rows=all(dialogs().last().window!!.decorView)
                check("#30",rows.filterIsInstance<ImageView>().any { it.drawable!=null },"shortcut list visible images=${rows.filterIsInstance<ImageView>().size}")
                clean(); ui { a.hideAppsMenu() }; settle(); shot("10-hide-apps")
                val switches=all(dialogs().last().window!!.decorView).filterIsInstance<Toggle>()
                check("#10/hide",switches.isNotEmpty(),"Hide apps uses switches, visible=${switches.size}")
            }
            case("#6 #11 #15 #16 #24") {
                setup(); val s=settings(LOOK); shot("06-look-settings")
                ui { s.showPage(COLORS) }; settle(); shot("06-custom-colors")
                val role=COLOR_ROLES.first(); tap(text(s.root,s.getString(role.second))); shot("06-color-editor")
                val d=dialogs(s).last(); val nodes=all(d.window!!.decorView)
                check("#6/editor",nodes.filterIsInstance<SeekBar>().size==3 && nodes.filterIsInstance<EditText>().isNotEmpty(),"HSV sliders=${nodes.filterIsInstance<SeekBar>().size}; HEX fields=${nodes.filterIsInstance<EditText>().size}")
                val hexField=nodes.filterIsInstance<EditText>().single()
                ui { hexField.setText("#FF5500") }; settle(150)
                tap(d.getButton(AlertDialog.BUTTON_POSITIVE)); settle()
                if(dialogs(s).isNotEmpty()) tap(dialogs(s).last().getButton(AlertDialog.BUTTON_POSITIVE))
                check("#6/save",a.looks.raw("c_${role.first}")=="#FF5500","saved override=${a.looks.raw("c_${role.first}")}")
                tap(text(s.root,s.getString(role.second))); settle()
                tap(dialogs(s).last().getButton(AlertDialog.BUTTON_NEUTRAL))
                check("#6/reset",a.looks.raw("c_${role.first}")==null,"per-role Reset removes override")
                val textRole=COLOR_ROLES.first { it.first=="onSurface" }
                tap(text(s.root,s.getString(textRole.second))); settle()
                val bad=dialogs(s).last()
                ui { all(bad.window!!.decorView).filterIsInstance<EditText>().single().setText(hex(s.m3.surface)) }; settle(150)
                tap(bad.getButton(AlertDialog.BUTTON_POSITIVE)); shot("06-low-contrast")
                check("#6/contrast",dialogs(s).isNotEmpty() && all(dialogs(s).last().window!!.decorView).filterIsInstance<TextView>().any { it.text.toString()==s.getString(R.string.low_contrast) },"low contrast asks before saving")
                back(); ui { s.showPage(LOOK) }; settle()
                ui { s.showPage("") }; settle()
                ui { s.showPage(LOOK) }; settle(); shot("16-settings"); back()
                check("#16",s.page=="","Back returns to settings categories; page=${s.page}")
                check("#15",ACTIONS.contains("torch"),"Toggle flashlight selectable in gesture action list; camera hardware check separate")
                ui { s.showPage(GESTURES) }; settle(); tap(text(s.root,s.getString(R.string.gesture_swipeup)))
                shot("15-gesture-actions"); back()
                clean(); setup("drawerLabelSize" to "24","folderLabelSize" to "20","labelSize" to "8"); drawer()
                val label=all(grid()).filterIsInstance<IconView>().first().let { all(it).filterIsInstance<TextView>().first() }
                check("#11",abs(label.textSize/a.resources.displayMetrics.scaledDensity-24)<0.1,"drawer label measured ${label.textSize/a.resources.displayMetrics.scaledDensity}sp; home=8 folder=20"); shot("11-label-size")
                clean()
                val homeLabel=all(a.views[a.items.first()]!!).filterIsInstance<TextView>().first()
                check("#11/home",abs(homeLabel.textSize/a.resources.displayMetrics.scaledDensity-8)<0.1,"home label measured ${homeLabel.textSize/a.resources.displayMetrics.scaledDensity}sp")
                tap(a.views[a.items.first { it.folder }]!!)
                val folderLabel=all(a.overlay!!).filterIsInstance<IconView>().first().let { all(it).filterIsInstance<TextView>().first() }
                check("#11/folder",abs(folderLabel.textSize/a.resources.displayMetrics.scaledDensity-20)<0.1,"folder label measured ${folderLabel.textSize/a.resources.displayMetrics.scaledDensity}sp"); shot("11-folder-label-size")
                clean()
                val cm=a.getSystemService(android.hardware.camera2.CameraManager::class.java)
                val flash=cm.cameraIdList.filter { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE)==true }
                ui { a.perform("torch") }; settle()
                val first=field(a,"torchOn")
                ui { a.perform("torch") }; settle()
                note("#15/hardware",if(flash.isEmpty()) "LIMITED" else "OBSERVED","flash cameras=$flash; two toggles=$first -> ${field(a,"torchOn")}; no physical light verification")
                note("#24","LIMITED","Settings navigated. GrapheneOS Android 17 unavailable")
            }
            case("#24") {
                setup()
                val group=Group("Animation group","folder",a.apps.keys.take(4))
                ui { a.editGroup(group) }; settle(); shot("24-group-menu")
                val menu=dialogs().last(); val samples=mutableListOf<Float>()
                ui { menu.listView.performItemClick(menu.listView.getChildAt(1),1,1) }
                repeat(20) { ui { val scrim=field(a,"scrim") as? View; samples+=if(scrim?.parent===a.root) scrim.alpha else -1f }; SystemClock.sleep(16) }
                settle(); shot("24-choose-apps")
                check("#24/scrim",samples.all { it>=0.99f },"dialog replacement dim alpha samples=$samples; None selected")
                note("#24/OS","LIMITED","Exercised original Choose apps path. Reporter follow-up requires GrapheneOS Android 17")
            }
            case("#2 #5 #8") {
                setup(); val old=Configuration(a.resources.configuration); val locale=Locale.getDefault()
                try {
                    val ru=Locale("ru"); Locale.setDefault(ru)
                    val c=Configuration(old).apply { setLocale(ru) }
                    @Suppress("DEPRECATION")
                    a.resources.updateConfiguration(c,a.resources.displayMetrics)
                    val s=settings(NOW)
                    @Suppress("DEPRECATION")
                    s.resources.updateConfiguration(c,s.resources.displayMetrics)
                    ui { s.showPage(NOW,restoring=true) }; settle(); shot("08-russian-now")
                    ui { s.showPage(HOME,focus="hideClock") }; settle(); shot("08-russian-home")
                    check("#2",s.getString(R.string.settings)!="Settings","Russian resource title=${s.getString(R.string.settings)}")
                    val title=all(s.root).filterIsInstance<TextView>().first { it.text.toString()==s.getString(R.string.set_hideClock) }
                    check("#8",title.ellipsize==android.text.TextUtils.TruncateAt.END,"clock title maxLines=${title.maxLines} ellipsize=${title.ellipsize}; explanation separated; screenshot checks clipping")
                    val names=listOf("Yahoo","Электричка","Catima","Контакт","Organic","Банк")
                    val ordered=names.sortedWith(labelOrder(ru))
                    check("#5",ordered==listOf("Банк","Контакт","Электричка","Catima","Organic","Yahoo"),"Android ICU Russian order=$ordered; German Ä/A=${labelOrder(Locale.GERMAN).compare("Ä","A")}")
                    clean()
                    val apps=a.apps.values.filter { !a.isPrivate(it) }.take(names.size)
                    ui {
                        // Starting SettingsActivity can reapply the device locale to Locale.getDefault().
                        // Reapply the case's app locale after closing it, before exercising the real drawer.
                        Locale.setDefault(ru)
                        @Suppress("DEPRECATION")
                        a.resources.updateConfiguration(c,a.resources.displayMetrics)
                        a.store.putObj("custom",JSONObject().apply { apps.zip(names).forEach { (app,name) -> put(app.key,JSONObject().put("label",name)) } })
                        a.store.putStrings("hidden",a.apps.keys-apps.map { it.key }.toSet()); a.loadApps(); a.drawer.invalidate()
                    }; drawer(); shot("05-russian-drawer")
                    val labels=all(grid()).filterIsInstance<IconView>().filter { it.isShown }.map { it.contentDescription.toString() }
                    check("#5/drawer",labels==ordered,"actual renamed app order=$labels; locale=${Locale.getDefault()}")
                } finally {
                    clean(); Locale.setDefault(locale)
                    @Suppress("DEPRECATION")
                    a.resources.updateConfiguration(old,a.resources.displayMetrics)
                }
            }
            case("#3 #31") {
                setup(); drawer(); shot("03-private-tab")
                note("#3",if(android.os.Build.VERSION.SDK_INT<35) "NOT_APPLICABLE" else "OBSERVED","privateUser=${a.privateUser}; locked=${a.privateLocked}; private apps=${a.apps.values.count { a.isPrivate(it) }}; tabKeys=${field(a.drawer,"tabKeys")}")
                if(a.privateUser!=null) {
                    tap(text(field(a.drawer,"tabs") as View,a.getString(R.string.tab_private))); shot("03-private-locked")
                    check("#3/locked",a.privateLocked && grid().adapter.count==0,"Private tab selected; locked profile has ${grid().adapter.count} exposed app entries")
                }
                val lawn=iconPacks(a).firstOrNull { it.first.contains("lawnicons") }
                if(lawn==null) note("#31","PREREQUISITE_MISSING","Lawnicons is not installed")
                else {
                    val pack=loadIconPack(a,lawn.first) ?: error("Cannot load Lawnicons")
                    val component="dev.vulcanlauncher/dev.vulcanlauncher.MainActivity"
                    check("#31",pack.map.containsKey(component),"${lawn.first}; Vulcan component mapping=${pack.map[component]}; total mappings=${pack.map.size}")
                    ui { a.looks["iconPack"]=lawn.first; a.rebuild() }; settle(); drawer(); shot("31-lawnicons-fallback")
                }
                note("#19","NOT_A_BUG","Thank-you message; no reproduction steps")
            }
        } finally {
            runCatching { clean(); ui { a.store.import(original,a::mainActivity); a.items=a.store.load(a.sub); a.rebuild() } }
                .onFailure { note("RESTORE","ERROR",it.stackTraceToString()) }
            for(name in listOf("layout","looks","data")) t.targetContext.getSharedPreferences(name,0).edit().commit()
            File(out,"results.txt").writeText(log.toString())
        }
        t.finish(Activity.RESULT_OK,Bundle().apply { putString("stream",log.toString()) })
    }
}
