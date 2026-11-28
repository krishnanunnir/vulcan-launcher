package dev.vulcanlauncher

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Other launchers' backups: reading the archive, telling formats apart, and each format's parsing (fixtures built here). */
class LauncherImportTest {
    private fun zip(vararg entries: Pair<String, ByteArray>) = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()

    private fun archive(bytes: ByteArray) = readArchive(ByteArrayInputStream(bytes), "too large")
    private fun format(vararg names: String) = BACKUP_FORMATS.firstOrNull { it.matches(archive(zip(*names.map { it to ByteArray(1) }.toTypedArray()))) }

    @Test fun archiveKeepsBaseNamesOfWhatFormatsRead() {
        val a = archive(zip("../../evil/launcher.db" to byteArrayOf(1), "x/Screen.txt" to "[]".toByteArray(), "wallpaper.png" to ByteArray(500)))
        assertEquals(setOf("launcher.db", "Screen.txt"), a.files.keys)
        assertEquals("[]", a.text("Screen.txt"))
    }

    @Test fun archiveKeepsOnlyWhatSomeFormatReads() {
        val a = archive(zip("com.android.launcher3.prefs.xml" to byteArrayOf(1), "other.prefs.xml" to byteArrayOf(2), "notes.xml" to byteArrayOf(3)))
        assertEquals(setOf("com.android.launcher3.prefs.xml"), a.files.keys)
    }

    @Test fun archiveCapsHoldAgainstBombsAndJunk() {
        assertThrows(IOException::class.java) { archive(zip("big.db" to ByteArray((MAX_BACKUP_BYTES + 1).toInt()))) }
        assertThrows(IOException::class.java) { archive(zip(*(0..MAX_IMPORT_ROWS).map { "$it.png" to ByteArray(0) }.toTypedArray())) }
        assertTrue(archive("%PDF-1.4 not a zip".toByteArray()).files.isEmpty())
        assertTrue(archive("  {\"format\":\"vulcan-backup-2\"}".toByteArray()).isJson)
    }

    @Test fun formatsAreToldApartByWhatIsInside() {
        assertEquals(InfoPbFormat, format("info.pb", "launcher.db", "screenshot.png"))
        assertEquals(InfoPbFormat, format("info.pb", "wallpaper.png")) // read, then said to hold no layout
        assertEquals(JsonTablesFormat, format("Screen.txt", "HomeScreenIcon.txt", "User.txt"))
        assertEquals(NovaFormat, format("nova.db", "nova.xml", "com.teslacoilsw.launcher_preferences.xml"))
        assertEquals(NovaFormat, format("backup.db", "com.teslacoilsw.launcher_preferences.xml"))
        // Another Launcher3-style launcher's: read, but never called Nova's.
        assertEquals(Launcher3Format, format("2026-11-01.db", "other_preferences.xml"))
        assertEquals(null, format("notes.txt", "photo.png"))
    }

    // A protobuf varint, and a length-delimited field.
    private fun varint(v: Long): ByteArray { val o = ByteArrayOutputStream(); var x = v; while (x >= 0x80) { o.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }; o.write(x.toInt()); return o.toByteArray() }
    private fun int(f: Int, v: Long) = varint((f shl 3).toLong()) + varint(v)
    private fun msg(f: Int, b: ByteArray) = varint(((f shl 3) or 2).toLong()) + varint(b.size.toLong()) + b

    @Test fun backupInfoGridAndDrawerColumns() {
        val info = int(1, 1500020300) + int(2, 1) + msg(3, int(1, 1795828798)) + int(4, 1) + msg(7, msg(1, "4,5".toByteArray()) + int(2, 4))
        val dataStore = msg(1, msg(1, "drawer_columns".toByteArray()) + msg(2, int(3, 5))) + msg(1, msg(1, "dark".toByteArray()) + msg(2, int(1, 1)))
        assertEquals(mapOf("cols" to "4", "rows" to "5", "dockCols" to "4", "drawerCols" to "5"), infoPbSettings(info, "", dataStore))
        // No grid in info.pb: the preferences' remembered one, and the chosen dock size.
        val prefs = """<string name="migration_src_workspace_size">6,7</string><int name="pref_hotseatColumns" value="6" />"""
        assertEquals(mapOf("cols" to "6", "rows" to "7", "dockCols" to "6"), infoPbSettings(int(2, 1), prefs, null))
        // Out of range is held to the setting, a broken DataStore file is passed over.
        assertEquals(mapOf("cols" to "12", "rows" to "3", "dockCols" to "9"), infoPbSettings(msg(7, msg(1, "40,1".toByteArray()) + int(2, 99)), "", byteArrayOf(10, 99)))
    }

    @Test fun nova8GridIsRowsByColumns() {
        // As in a real Nova 8.9.4 nova.xml: 5 columns (its apps reach cellX 4), 6 rows.
        val xml = """<int name="dock_grid_cols" value="5" /><string name="desktop_grid">6x5 subgrid</string>
            <int name="workspace_screen_count" value="2" /><string name="drawer_grid">6x4</string><string name="drawer_grid_land">4x6</string>"""
        assertEquals(mapOf("cols" to "5", "rows" to "6", "pages" to "2", "dockCols" to "5", "drawerCols" to "4", "drawerRows" to "6"), importedPrefs(xml))
        // An older Nova's own keys win over nothing; junk in the grid is passed over.
        assertEquals(mapOf("cols" to "7"), importedPrefs("""<int name="desktop_grid_cols" value="7" /><string name="desktop_grid">big</string>"""))
    }

    @Test fun everySettingAFormatSetsIsASetting() {
        // setting() looks its key up strictly, before the value; running each format's settings code once reaches every
        // call, so a mistyped key fails here, not at import. A new format's settings function goes here too.
        importedPrefs("")
        novaLooks(emptyMap())
        gestureSettings(emptyMap(), NOVA_GESTURES) { null }
        infoPbSettings(ByteArray(0), "", null)
        lawnchairOwn(null)
        jsonTablesToRows(tables) { 0 }
    }

    @Test fun componentKeysAreAppKeys() {
        assertEquals("com.a/com.a.Main/0", componentKey("com.a/com.a.Main#-1"))
        assertEquals("com.a/com.a.Main/0", componentKey("com.a/.Main"))
        assertEquals("com.a/com.a.Main/12", componentKey("com.a/com.a.Main#12"))
        assertEquals(null, componentKey("com.a/com.a.Main#10") { null }) // a user that isn't on this phone
        for (bad in listOf("", "com.a", "/x", "com.a/", "com.a/b/c", "com.a/b#x")) assertEquals(bad, null, componentKey(bad))
    }

    // As in a real Nova 8.9.4 nova.xml.
    private fun nova(action: String) = "Label\$#Intent;action=com.teslacoilsw.launcher.ACTION;launchFlags=0x10000000;component=com.teslacoilsw.launcher/.NovaShortcutHandler;S.LAUNCHER_ACTION=$action;end"

    @Test fun novaGesturesBecomeActions() {
        val values = mapOf("gesture_swipe_up" to nova("APP_DRAWER"), "gesture_pinch" to nova("SHOW_PREVIEWS"), "home_button_intent" to nova("APP_SEARCH"),
            "gesture_two_finger_scroll_up" to "Nova Settings\$#Intent;action=android.intent.action.MAIN;launchFlags=0x30008000;component=com.teslacoilsw.launcher/.preferences.SettingsActivity;end",
            "gesture_swipe_right" to "Maps\$#Intent;action=android.intent.action.MAIN;component=com.google.android.apps.maps/com.google.android.maps.MapsActivity;end",
            "gesture_double_tap" to nova("GOOGLE_LENS"), "gesture_swipe_left" to "", "desktop_grid" to "6x5")
        val (set, left) = gestureSettings(values, NOVA_GESTURES, ::novaAction)
        assertEquals(mapOf("g_swipeUp" to "drawer", "g_pinchIn" to "previews", "homeButton" to "search", "g_swipeUp2" to "settings",
            "g_swipeRight" to "app:com.google.android.apps.maps/com.google.android.maps.MapsActivity/0"), set)
        assertEquals(1, left) // Lens; the empty one is Nova's "none", left as Vulcan has it
    }

    @Test fun novaLooksAndDrawer() {
        val xml = """<string name="adaptive_icon_shape">FLOWER</string><string name="adaptive_icon_reshape">OFF</string>
            <string name="drawer_searchbar_position">TOP</string><string name="drawer_style">VERTICAL</string>
            <string name="searchbar_placement">DOCK_BELOW_ICONS</string><int name="drawer_transparency" value="128" />
            <boolean name="drawer_show_keyboard_by_default" value="true" />"""
        assertEquals(mapOf("shape" to "flower", "reshape" to "off", "drawerSearch" to "top", "drawerStyle" to "grid", "searchBar" to "dockBelow",
            "drawerAlpha" to "50", "showKeyboard" to "on"), novaLooks(prefValues(xml)))
        assertEquals(mapOf("shape" to "rounded"), novaLooks(mapOf("adaptive_icon_shape" to "ROUNDED_SQUARE", "drawer_style" to "SIDEWAYS")))
        val groups = listOf(NovaGroup(1, "Apps", "TAB_APPS", 0, 262914), NovaGroup(4, "Games", "FOLDER_APP_GROUP", 101, 262914),
            NovaGroup(3, "Work stuff", "TAB_APP_GROUP", 2, 0xFF1E88E5.toInt()), NovaGroup(5, "Empty", "TAB_APP_GROUP", 3, 262914))
        val members = listOf(4L to "com.g/com.g.G#-1", 3L to "com.w/.W#-1", -1L to "com.h/com.h.H#-1", -100L to "com.g/com.g.G#-1", 4L to "junk")
        val drawer = novaDrawer(groups, members, listOf(Triple("com.r/com.r.R", -1L, "Mine"), Triple("com.s/com.s.S", -1L, "")))
        assertEquals(listOf(Group("Work stuff", "tab", listOf("com.w/com.w.W/0"), "#1E88E5"), Group("Games", "folder", listOf("com.g/com.g.G/0"))), drawer.groups)
        assertEquals(setOf("com.h/com.h.H/0"), drawer.hidden)
        assertEquals(mapOf("com.r/com.r.R/0" to "Mine"), drawer.labels)
    }

    @Test fun lawnchairDataStoreText() {
        fun entry(k: String, value: ByteArray) = msg(1, msg(1, k.toByteArray()) + msg(2, value))
        fun text(v: String) = msg(5, v.toByteArray())
        val ds = entry("hidden_apps", msg(6, msg(1, "com.c/com.c.Cal#0".toByteArray()) + msg(1, "com.w/com.w.W#10".toByteArray()))) +
            entry("icon_shape", text("hexagon")) + entry("swipe_down_gesture_handler", text("""{"type":"recents"}""")) +
            entry("home_press_gesture_handler", text("""{"type":"openApp","appName":"Camera","target":{"type":"app","key":"com.k/com.k.Cam#0"}}""")) +
            entry("double_tap_gesture_handler", text("""{"type":"openShortcut"}""")) + entry("drawer_columns", msg(3, varint(5)))
        val (settings, left, drawer) = lawnchairOwn(ds)
        assertEquals(mapOf("shape" to "hexagon", "g_swipeDown" to "recents", "homeButton" to "app:com.k/com.k.Cam/0"), settings)
        assertEquals(1, left)
        assertEquals(setOf("com.c/com.c.Cal/0"), drawer.hidden) // the work app is dropped (see lawnchairKey)
        assertEquals("roundedSquare is ours", "rounded", lawnchairOwn(entry("icon_shape", text("roundedSquare"))).first["shape"])
        assertEquals(mapOf("drawerAlpha" to "60"), infoPbSettings(ByteArray(0), """<float name="pref_drawerOpacity" value="0.6" />""", null))
        assertEquals("notifications", lawnchairAction("""{"type":"openNotificationdata"}"""))
        assertTrue(lawnchairOwn(byteArrayOf(10, 99)).first.isEmpty())
        // An app hidden and then shown again leaves an empty set: nothing hidden, and the rest still reads.
        val emptied = entry("hidden_apps", msg(6, ByteArray(0))) + entry("icon_shape", text("hexagon"))
        assertEquals(listOf<Byte>(0x0A, 11) + "hidden_apps".toByteArray().toList() + listOf<Byte>(0x12, 2, 0x32, 0), msg(1, "hidden_apps".toByteArray()).toList() + msg(2, msg(6, ByteArray(0))).toList())
        lawnchairOwn(emptied).let { (own, _, d) -> assertEquals(mapOf("shape" to "hexagon"), own); assertTrue(d.hidden.isEmpty()) }
    }

    @Test fun octopiDrawerAndGestures() {
        val items = """[{"componentName":"com.c/com.c.Cal","shortcutID":"","profile":"UserHandle{0}","hidden":true,"nickname":""},
            {"componentName":"com.k/com.k.Cam","shortcutID":"","profile":"UserHandle{0}","hidden":false,"nickname":" Snap "},
            {"componentName":"com.k/com.k.Cam","shortcutID":"s1","profile":"UserHandle{0}","hidden":true,"nickname":"x"}]"""
        val drawer = jsonTablesDrawer(mapOf("DrawerItem" to items)) { 0L }
        assertEquals(setOf("com.c/com.c.Cal/0"), drawer.hidden)
        assertEquals(mapOf("com.k/com.k.Cam/0" to "Snap"), drawer.labels)
        val screen = org.json.JSONObject("""{"actionTypeDoubleTap":"SCREEN_OFF","actionTypeSwipeUp":"","actionTypePinchIn":"GOOGLE",
            "actionTypeSwipeDown":"","componentNameSwipeDown":"com.m/com.m.M","drawerBackgroundAlpha":0.5}""")
        assertEquals(mapOf("g_doubleTap" to "lock", "g_swipeDown" to "app:com.m/com.m.M/0") to 1, octopiGestures(screen))
        assertEquals("50", jsonTablesToRows(tables + ("Screen" to "[" + screen.toString().replace("{", """{"screenId":1,"homeIconsX":5,""") + "]")) { 0 }.settings["drawerAlpha"])
    }

    @Test fun importedGroupsJoinOursByName() {
        val ours = listOf(Group("Games", "folder", listOf("a")), Group("Work stuff", "tab", listOf("b")))
        val theirs = listOf(Group("Games", "folder", listOf("c", "a")), Group("Games", "tab", listOf("d")), Group("New", "tab", listOf("e")))
        assertEquals(listOf(Group("Games", "folder", listOf("a", "c")), Group("Work stuff", "tab", listOf("b")), Group("Games", "tab", listOf("d")),
            Group("New", "tab", listOf("e"))), mergedGroups(ours, theirs))
    }

    @Test fun protobufReaderRefusesWhatIsNotOne() {
        assertEquals(listOf<Any>(150L), protoFields(byteArrayOf(8, 0x96.toByte(), 1))[1])
        for (bad in listOf(byteArrayOf(8), byteArrayOf(10, 50, 1), byteArrayOf(15), byteArrayOf(8) + ByteArray(11) { 0xff.toByte() }))
            assertThrows(IllegalArgumentException::class.java) { protoFields(bad) }
        assertThrows(IllegalArgumentException::class.java) { infoPbSettings(byteArrayOf(10, 50), "", null) }
    }

    private val screen = """[
        {"screenId":1,"screenWidth":1080,"screenHeight":2400,"screenOrientation":"PORTRAIT","screenConfigCompleted":true,
         "homeIconsX":5,"homeIconsY":6,"drawerIconsX":4,"defaultHomeScreenIndex":1,"leftPadding":30,"rightPadding":30},
        {"screenId":2,"screenWidth":2400,"screenHeight":1080,"screenOrientation":"LANDSCAPE","screenConfigCompleted":true,"homeIconsX":8,"homeIconsY":4}]"""
    private fun icon(page: Int, x: Double, y: Double, folder: Int? = 1, index: Int = 0, cmp: String = "com.a/com.a.Main", sc: String = "", user: Int = 0) =
        """{"homeScreenOwnerId":$page,"homeScreenFolderOwnerId":${folder ?: "null"},"homeScreenFolderIndex":$index,"componentName":"$cmp",
            "packageName":"${cmp.substringBefore('/')}","label":"L","shortcutID":"$sc","profile":"UserHandle{$user}","x":$x,"y":$y,"width":204.0,"height":333.5}"""
    private val tables = mapOf(
        "Screen" to screen,
        "HomeScreen" to """[{"homeScreenId":7,"screenOwnerId":1,"homeScreenIndex":1},{"homeScreenId":3,"screenOwnerId":1,"homeScreenIndex":0},
            {"homeScreenId":9,"screenOwnerId":2,"homeScreenIndex":0}]""",
        "HomeScreenFolder" to """[{"homeScreenFolderId":1,"homeScreenOwnerId":3,"folderInternalName":"blank","label":"","x":0,"y":0,"width":180,"height":180},
            {"homeScreenFolderId":2,"homeScreenOwnerId":3,"folderInternalName":"123","label":"Tools","x":204.0,"y":466.0,"width":204.0,"height":333.5}]""",
        "HomeScreenIcon" to "[" + listOf(
            icon(3, 0.0, 1799.5), icon(3, 204.0, 1799.5, folder = null), icon(3, 102.0, 821.4),
            icon(3, 612.0, 466.0, folder = 2, index = 1, cmp = "com.c/.C"), icon(3, 204.0, 466.0, folder = 2, index = 0, cmp = "com.b/com.b.B"),
            icon(7, 0.0, 132.5, sc = "id one", user = 10), icon(9, 0.0, 0.0, cmp = "com.landscape/.L"),
        ).joinToString(",") + "]",
        "HomeScreenWidget" to """[{"provider":"com.w/com.w.Clock","profile":"UserHandle{0}","homeScreenOwnerId":3,"homeScreenFolderOwnerId":null,
            "x":306.0,"y":1134.0,"width":816.0,"height":816.0}]""",
        "HomePanels" to """[{"homePanelId":4,"screenOwnerId":1,"panelType":"DOCK_CLASSIC","width":1020.0,"maxGridItemsX":5}]""",
        "PanelIcons" to """[{"homePanelOwnerId":4,"homePanelFolderOwnerId":null,"componentName":"com.d/com.d.Dial","packageName":"com.d","profile":"UserHandle{0}","x":0.0,"panelIndex":0},
            {"homePanelOwnerId":4,"homePanelFolderOwnerId":null,"componentName":"com.m/com.m.Msg","packageName":"com.m","profile":"UserHandle{0}","x":204.0,"panelIndex":0},
            {"homePanelOwnerId":4,"homePanelFolderOwnerId":null,"componentName":"openAppDrawer","packageName":"","profile":"UserHandle{0}","x":816.0,"panelIndex":0}]""",
    )

    @Test fun jsonTablesInPixelsBecomeCells() {
        val (rows, ranks, settings) = jsonTablesToRows(tables) { if (it == 10) 7L else 0L }
        assertEquals(mapOf(3L to 0, 7L to 1), ranks) // the portrait screen's pages, in order
        assertEquals(mapOf("cols" to "5", "rows" to "6", "dockCols" to "5", "drawerCols" to "4", "pages" to "2", "defaultPage" to "1"), settings)
        val loose = rows.filter { it.container == -100L && it.itemType == 0 }
        assertEquals(listOf(0.0 to 5.0, 1.0 to 5.0, 0.5 to 2.0), loose.map { it.cellX to it.cellY })
        val folder = rows.single { it.itemType == 2 }
        assertEquals(Triple("Tools", 1.0, 1.0), Triple(folder.title, folder.cellX, folder.cellY))
        assertEquals(listOf(1, 0), rows.filter { it.container == 2L }.map { it.rank })
        val shortcut = rows.single { it.itemType == 6 }
        assertEquals(Triple(7L, 7L, 0.0), Triple(shortcut.screen, shortcut.profileId, shortcut.cellY))
        assertEquals("com.a" to "id one", intentShortcut(shortcut.intent))
        val widget = rows.single { it.itemType == 4 }
        assertEquals(listOf(1.0, 3.0, 4.0, 2.0), listOf(widget.cellX, widget.cellY, widget.spanX, widget.spanY)) // ran past the edge: pulled in
        assertEquals(listOf(0.0, 1.0, 4.0), rows.filter { it.container == -101L }.map { it.cellX })
        assertTrue(rows.none { "landscape" in it.intent })

        val layout = importToItems(rows, ranks, 5)
        assertTrue(layout.subgrid)
        assertEquals(1, layout.skipped) // the "open drawer" icon
        assertEquals(listOf("com.b", "com.c"), layout.items.single { it.folder }.children.map { it.pkg })
        assertEquals(2, layout.items.count { isDock(it.page) })
        assertEquals(1, layout.widgets)
    }

    @Test fun brokenJsonTablesFailOrDropRows() {
        assertThrows(Exception::class.java) { jsonTablesToRows(tables + ("Screen" to "[]")) { 0 } }
        assertThrows(Exception::class.java) { jsonTablesToRows(tables + ("HomeScreenIcon" to "{not json")) { 0 } }
        // Missing and absurd numbers stay rows; importToItems keeps them on the grid or leaves them out.
        val odd = tables + ("HomeScreenIcon" to """[{"homeScreenOwnerId":3,"componentName":"com.a/.A","x":1e300},{"homeScreenOwnerId":3,"componentName":"com.b/.B"}]""")
        val (rows, ranks, _) = jsonTablesToRows(odd) { 0 }
        importToItems(rows, ranks, 5).items.forEach { assertTrue(it.x in 0..MAX_CELL && it.y in 0..MAX_CELL) }
        assertFalse(rows.isEmpty())
    }

    @Test fun importedDrawerKeepsOnlyInstalledApps() {
        val d = ImportedDrawer(listOf(Group("Tab", "tab", listOf("a/A#0", "gone/G#0")), Group("Gone", "folder", listOf("gone/G#0"))),
            setOf("a/A#0", "gone/G#0"), mapOf("a/A#0" to "Mail", "gone/G#0" to "Old")).installedOnly(setOf("a/A#0"))
        assertEquals(listOf(Group("Tab", "tab", listOf("a/A#0"))), d.groups)
        assertEquals(setOf("a/A#0"), d.hidden)
        assertEquals(mapOf("a/A#0" to "Mail"), d.labels)
        assertTrue("a name given in Vulcan stays", ImportedDrawer(labels = mapOf("a/A#0" to "Mail")).installedOnly(setOf("a/A#0"), setOf("a/A#0")).labels.isEmpty())
    }

    @Test fun novaDbBeatsOtherDatabases() {
        val nova = byteArrayOf(1); val other = byteArrayOf(2)
        assertEquals(nova, favoritesDb(BackupArchive(linkedMapOf("widgets.db" to other, "nova.db" to nova))))
        assertEquals(other, favoritesDb(BackupArchive(linkedMapOf("widgets.db" to other))))
    }

    @Test fun aMissingPlaceIsNotAHalfCell() {
        fun row(x: Double, y: Double) = ImportRow(1, "t", "#Intent;component=p/.A;end", -100, 0, x, y, 1.0, 1.0, 0, 0, 0)
        val l = importToItems(listOf(row(1.0, 1.0), row(2.0, 3.0), row(Double.NaN, Double.NaN)), emptyMap(), 5)
        assertFalse(l.subgrid)
        assertEquals(listOf(1 to 1, 2 to 3), l.items.take(2).map { it.x to it.y }) // not doubled
        assertEquals(MAX_CELL, l.items[2].x) // off the grid, for home to move to the first free cell
        assertTrue("a real half cell still turns them on", importToItems(listOf(row(0.5, 1.0)), emptyMap(), 5).subgrid)
        // The same from Octopi: an icon with no x and y.
        val icons = "[" + icon(3, 0.0, 1799.5) + "," + icon(3, 204.0, 1799.5) + "," +
            icon(3, 0.0, 0.0).replace(Regex(""","x":[^,]*,"y":[^,]*"""), "") + "]"
        val (rows, ranks, _) = jsonTablesToRows(tables + ("HomeScreenIcon" to icons) + ("HomeScreenWidget" to "[]")) { 0 } // its widget sits on a half cell
        assertTrue(rows.any { it.container == -100L && it.cellX.isNaN() })
        assertFalse(importToItems(rows, ranks, 5).subgrid)
    }

    @Test fun aGestureForAnAppNotHereStaysBehind() {
        val (kept, gone) = withoutMissingApps(mapOf("g_swipeUp" to "app:a/A/0", "g_doubleTap" to "app:gone/G/0", "homeButton" to "drawer", "cols" to "5"), setOf("a/A/0"))
        assertEquals(mapOf("g_swipeUp" to "app:a/A/0", "homeButton" to "drawer", "cols" to "5"), kept)
        assertEquals(1, gone)
    }
}
