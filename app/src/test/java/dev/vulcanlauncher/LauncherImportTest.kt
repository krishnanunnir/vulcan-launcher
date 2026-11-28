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
        infoPbSettings(ByteArray(0), "", null)
        jsonTablesToRows(tables) { 0 }
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
}
