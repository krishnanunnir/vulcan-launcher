package dev.vulcanlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StoreTest {
    @Test
    fun badItemIsDroppedNotTheLayout() {
        val json = """[{"page":0,"x":0,"y":0,"pkg":"a","cls":"b"}, {"page":"oops"}, 7,
            {"page":1,"x":0,"y":0,"folder":true,"children":[{"x":1}, {"page":0,"x":0,"y":0,"pkg":"c"}]}]"""
        assertEquals(listOf(Item(0, 0, 0, pkg = "a", cls = "b"), Item(1, 0, 0, folder = true, children = listOf(Item(0, 0, 0, pkg = "c")))),
            itemsFromJson(json))
        assertThrows(Exception::class.java) { itemsFromJson("{not json") }
    }

    @Test
    fun uninstalledPackageLeavesHome() {
        val gone = Item(0, 0, 0, pkg = "x", cls = "X")
        val workTwin = Item(0, 1, 0, pkg = "x", cls = "X", user = 10)
        val shortcut = Item(0, 2, 0, pkg = "x", shortcutId = "s")
        val widget = Item(1, 0, 0, 2, 2, pkg = "x", widgetId = 5)
        val other = Item(0, 0, 0, pkg = "y", cls = "Y")
        val pair = Item(2, 3, 4, 2, 2, folder = true, children = listOf(gone, other))
        val trio = pair.copy(children = listOf(gone, other, workTwin))
        val solo = Item(3, 0, 0, folder = true, children = listOf(gone, shortcut))
        assertEquals(
            listOf(workTwin, widget, other.copy(page = 2, x = 3, y = 4, w = 2, h = 2), trio.copy(children = listOf(other, workTwin))),
            removePackage(listOf(gone, workTwin, shortcut, widget, pair, trio, solo), "x", 0))
    }

    @Test
    fun layoutReadsInTheSubgridItWasSavedIn() {
        val whole = listOf(Item(0, 1, 2, 2, 1, pkg = "a"), Item(DOCK, 3, 0, pkg = "d"))
        val half = listOf(Item(0, 2, 4, 4, 2, pkg = "a"), Item(DOCK, 3, 0, pkg = "d")) // the dock has no subgrid
        assertEquals(half, layoutIn(whole, 1, 2))
        assertEquals(whole, layoutIn(half, 2, 1))
        assertEquals(half, layoutIn(half, 2, 2))
        // Version 1 saved no factor: it was always in the current one.
        assertEquals(whole, layoutIn(whole, null, 2))
    }

    @Test
    fun appsGoneWhileTheLauncherWasDeadLeaveHome() {
        val gone = Item(0, 0, 0, pkg = "x")
        val kept = Item(0, 1, 0, pkg = "y")
        val action = Item(0, 2, 0, action = "drawer")
        val widget = Item(1, 0, 0, 2, 2, pkg = "x", widgetId = 3)
        val folder = Item(2, 1, 1, folder = true, label = "F", children = listOf(gone, kept))
        val asked = mutableListOf<Item>()
        assertEquals(listOf(kept, action, widget, kept.copy(page = 2, x = 1, y = 1)),
            removeApps(listOf(gone, kept, action, widget, folder)) { asked += it; it.pkg == "x" })
        // Never asked about widgets, folders or items without a package.
        assertEquals(listOf(gone, kept, gone, kept), asked)
    }

    @Test
    fun widgetSizesStayInTheGridAndHonourResizeMode() {
        assertEquals(listOf(2 to 1, 3 to 1, 2 to 2, 3 to 2), widgetSizes(1, 4, 2, 1, 2, 1, 4, 6, canW = true, canH = true))
        assertEquals(listOf(2 to 1, 2 to 2), widgetSizes(1, 4, 2, 1, 2, 1, 4, 6, canW = false, canH = true))
        assertEquals(listOf(4 to 6), widgetSizes(0, 0, 4, 6, 1, 1, 4, 6, canW = false, canH = false))
    }

    @Test
    fun readableBackupRoundTrips() {
        val items = listOf(Item(0, 1, 2, pkg = "a", cls = "A", label = "Mail"),
            Item(-1, 0, 0, folder = true, label = "F", children = listOf(Item(0, 0, 0, pkg = "b", cls = "B", user = 10))))
        val doc = readableBackup(mapOf("cols" to "5", "c_surface" to "#101828"), items, 1, mapOf("hidden" to "[]"), listOf(Triple("A app", "a", "A") to 0L)) { "s$it" }
        // Fields left at their defaults aren't written.
        assertEquals(setOf("page", "x", "y", "pkg", "cls", "label"), doc.getJSONObject("home").getJSONArray("items").getJSONObject(0).keys().asSequence().toSet())
        val (files, rejected) = readableBackupFiles(org.json.JSONObject(doc.toString()), { _, _ -> null })
        assertEquals(items, itemsFromJson(files["layout"]!!["items"]!!))
        assertEquals("5", files["looks"]!!["cols"])
        assertEquals("5", files["looks"]!!["dockCols"]) // every setting is written out, defaults too
        assertEquals("#101828", files["looks"]!!["c_surface"]) // custom colours travel with the settings
        assertEquals("[]", files["data"]!!["hidden"])
        assertEquals(emptyList<String>(), rejected)
    }

    @Test
    fun handWrittenBackupIsChecked() {
        val json = """{"format":"vulcan-backup-2","settings":{"cols":"100","labels":false,"labelColor":"#80FF8800","g_doubleTap":"lock",
            "g_swipeDown":"page:2","accent":"#12","iconPack":"x.pack"},"home":{"items":[{"page":0,"x":0,"y":0,"pkg":"chrome"},
            {"page":0,"x":1,"y":0,"pkg":"maps","shortcutId":"s"}]}}"""
        val (files, rejected) = readableBackupFiles(org.json.JSONObject(json)) { pkg, _ -> if (pkg == "chrome") "Main" else null }
        assertEquals(setOf("cols=100", "accent=#12"), rejected.toSet())
        assertEquals(mapOf("labels" to "off", "labelColor" to "#80FF8800", "g_doubleTap" to "lock", "g_swipeDown" to "page:2", "iconPack" to "x.pack"),
            files["looks"])
        assertEquals(listOf(Item(0, 0, 0, pkg = "chrome", cls = "Main"), Item(0, 1, 0, pkg = "maps", shortcutId = "s")),
            itemsFromJson(files["layout"]!!["items"]!!))
        assertEquals("1", files["layout"]!!["sub"])
    }

    @Test
    fun backupDataIsReadableJson() {
        val groups = """[{"name":"Social","type":"tab","apps":["a/A/0"]}]"""
        val doc = readableBackup(emptyMap(), emptyList(), 1, mapOf("groups" to groups, "pendingWidget" to ""), emptyList()) { "s$it" }
        // Stored JSON text is written as JSON, so people and assistants can edit it, and read back as the same text.
        assertEquals("Social", doc.getJSONObject("data").getJSONArray("groups").getJSONObject(0).getString("name"))
        val (files, _) = readableBackupFiles(org.json.JSONObject(doc.toString())) { _, _ -> null }
        assertEquals(groups, files["data"]!!["groups"])
        assertEquals("", files["data"]!!["pendingWidget"])
    }

    @Test
    fun groupsFromAnything() {
        // A restored or hand-edited file can hold anything here; home must still start (it read these on every refresh).
        for (bad in listOf("oops", "{\"a\":1}", "5", "")) assertEquals(emptyList<Group>(), groupsFromJson(bad))
        val s = """[{"type":"folder"}, 5, null, {"name":"X","type":"folder","apps":"no"},
            {"name":"Y","type":"tab","apps":["a",1,null,{"b":2},"c"]}, {"name":7,"type":"tab","apps":[]}]"""
        assertEquals(listOf(Group("X", "folder", emptyList()), Group("Y", "tab", listOf("a", "c"))), groupsFromJson(s))
        assertEquals(listOf(Group("Z", "folder", listOf("k"))), groupsFromJson(groupsToJson(listOf(Group("Z", "folder", listOf("k"))))))
    }
}
