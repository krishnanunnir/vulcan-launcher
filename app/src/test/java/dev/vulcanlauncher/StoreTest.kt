package dev.vulcanlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StoreTest {
    @Test
    fun widgetStacks() {
        val a = Item(1, 2, 0, 4, 2, widgetId = 7)
        val stack = a.withWidgets(a.widgetIds + listOf(8, 9))!!
        assertEquals(listOf(7, 8, 9), stack.widgetIds)
        assertEquals(Triple(1, 2, 4), Triple(stack.page, stack.x, stack.w))
        // Survives the store, so the stack is still whole after a restart or a backup.
        assertEquals(listOf(7, 8, 9), itemsFromJson(itemsToJson(listOf(stack)))[0].widgetIds)
        // Unstacking the front widget leaves the rest; the last one left is a plain widget.
        assertEquals(listOf(8, 9), stack.withWidgets(stack.widgetIds - 7)!!.widgetIds)
        assertEquals(emptyList<Item>(), stack.withWidgets(listOf(9))!!.children)
        assertEquals(null, stack.withWidgets(emptyList()))
        assertEquals(emptyList<Int>(), Item(0, 0, 0, pkg = "p", cls = "c").widgetIds)
        // An uninstalled provider takes its widget out of the stack; the stack goes when nothing is left.
        val app = Item(0, 0, 0, pkg = "p", cls = "c")
        assertEquals(listOf(listOf(7, 9), emptyList()), keepWidgets(listOf(stack, app)) { it != 8 }.map { it.widgetIds })
        assertEquals(listOf(app), keepWidgets(listOf(stack, app)) { false })
        assertEquals(listOf(9), keepWidgets(listOf(stack)) { it == 9 }[0].widgetIds)
        // An app's removal never touches a stack (its children have no package).
        assertEquals(listOf(stack), removePackage(listOf(stack), "", 0))
    }

    @Test
    fun restoredFlagsAreBooleansWhateverTheFileSays() {
        // getBoolean throws on text, and these are read on every start.
        for (k in listOf("welcome", "welcomeImport", "restoringWidgets", "repinShortcuts", "hideAllTab", "hideWorkTab"))
            assertEquals(k, true, dataValue(k, "true"))
        // Any other key stays text, even "false": a hand-edited "groups": false stored as a boolean made getString throw.
        assertEquals("false", dataValue("groups", "false"))
        // The Work tab's place is an int, as GroupEditor stores it; a backup has it as text.
        assertEquals(listOf(3, null, 3), listOf("3", "x", 3).map { dataValue(WORK_AT, it) })
        // What such a restore left behind is put right on the next start; text and real flags are left alone.
        assertEquals(mapOf("groups" to "false", "welcome" to true, "hideWorkTab" to false),
            dataTypeFixes(mapOf("groups" to false, "welcome" to "true", "hideWorkTab" to "false", "repinShortcuts" to true, "hidden" to "[]", "custom" to "true")))
    }

    @Test
    fun restoreSkipsPassingStateAndLocation() {
        assertEquals(false, restoredKey("data", "pendingWidget"))
        assertEquals(false, restoredKey("looks", "lat"))
        assertEquals(false, restoredKey("looks", "lon"))
        assertEquals(false, restoredKey("looks", "clockHiddenByUs"))
        assertEquals(true, restoredKey("data", "groups"))
        assertEquals(true, restoredKey("looks", "cols"))
    }

    @Test
    fun autoBackupsKeepDifferentSetups() {
        // Restoring back and forth between kept backups moves one up instead of pushing the last different one out.
        assertEquals(listOf("b", "a", "c"), rotatedBackups("b", listOf("a", "b", "c"), 3))
        assertEquals(listOf("d", "a", "b"), rotatedBackups("d", listOf("a", "b", "c"), 3))
        assertEquals(listOf("a"), rotatedBackups("a", emptyList(), 3))
        assertEquals(listOf("a", "b"), rotatedBackups("a", listOf("a", "b", "b"), 3))
    }

    @Test
    fun restoredWidgetsComeBackAsSpots() {
        val providers = mapOf(7 to "p/W", 8 to "q/V")
        fun restore(items: List<Item>, canAdd: Boolean = true) = restoredWidgets(items, providers::get) { canAdd }
        // A live widget stays, without the provider its backup noted.
        assertEquals(listOf(Item(0, 1, 1, 2, 2, widgetId = 7)), restore(listOf(Item(0, 1, 1, 2, 2, pkg = "p", cls = "W", widgetId = 7))))
        // Its ID freed by an import or reset: a spot that adds the same widget again, in the same place and profile.
        assertEquals(listOf(Item(0, 1, 1, 2, 2, pkg = "p", cls = "W", user = 10, action = WIDGET_SLOT)),
            restore(listOf(Item(0, 1, 1, 2, 2, pkg = "p", cls = "W", user = 10, widgetId = 5))))
        // The same ID naming another widget here (another phone's backup) is a spot too.
        assertEquals(WIDGET_SLOT, restore(listOf(Item(0, 0, 0, pkg = "p", cls = "W", widgetId = 8)))[0].action)
        // Gone with no provider noted (a backup from before 0.35), or one that isn't installed: dropped, as before.
        assertEquals(emptyList<Item>(), restore(listOf(Item(0, 0, 0, widgetId = 5))))
        assertEquals(emptyList<Item>(), restore(listOf(Item(0, 0, 0, pkg = "p", cls = "W", widgetId = 5)), canAdd = false))
        // A stack keeps what's left of it.
        val stack = Item(0, 0, 0, pkg = "p", cls = "W", widgetId = 5).withWidgets(listOf(5, 8))!!
        assertEquals(listOf(8), restore(listOf(stack))[0].widgetIds)
    }

    @Test
    fun pickedFilesHaveASizeCap() {
        assertEquals(3, java.io.ByteArrayInputStream(ByteArray(3)).readAtMost(3, "big").size)
        val e = assertThrows(java.io.IOException::class.java) { java.io.ByteArrayInputStream(ByteArray(20000)).readAtMost(10000, "big") }
        assertEquals("big", e.message)
    }

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
    fun aDraggedFolderChildIsTakenFromTheFolderAsItIsNow() {
        val a = Item(0, 0, 0, pkg = "a")
        val b = Item(0, 0, 0, pkg = "b")
        val c = Item(0, 0, 0, pkg = "c")
        val other = Item(0, 3, 3, pkg = "z")
        // Renamed and re-sorted since the drag began: found by its cell, and the child by itself, not by its old place.
        val folder = Item(1, 2, 2, folder = true, label = "New name", children = listOf(c, b, a))
        assertEquals(listOf(other, folder.copy(children = listOf(c, b))), takeFromFolder(listOf(other, folder), 1, 2, 2, a))
        // Two left: the folder turns back into the one that stays, on the folder's cell.
        val pair = folder.copy(children = listOf(a, b))
        assertEquals(listOf(b.copy(page = 1, x = 2, y = 2)), takeFromFolder(listOf(pair), 1, 2, 2, a))
        // The child or the folder is gone: nothing to do, so the drop places nothing.
        assertEquals(null, takeFromFolder(listOf(other, folder), 1, 2, 2, Item(0, 0, 0, pkg = "gone")))
        assertEquals(null, takeFromFolder(listOf(other), 1, 2, 2, a))
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
        // A restore reports the same icons: the one on home and the one in the folder.
        assertEquals(2, missingApps(listOf(gone, kept, action, widget, folder)) { it.pkg == "x" })
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
        val doc = readableBackup(mapOf("cols" to "5", "c_surface" to "#101828", "lat" to "51.5", "lon" to "-0.1"), items, 1, mapOf("hidden" to "[]"), listOf(Triple("A app", "a", "A") to 0L)) { "s$it" }
        // The phone's location (sunset night mode) never leaves it in a backup.
        assertEquals(false, doc.getJSONObject("settings").has("lat") || doc.getJSONObject("settings").has("lon"))
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
    fun unreadableLayoutFallsBackToABackupOfEitherFormat() {
        val items = listOf(Item(0, 2, 4, pkg = "a", cls = "A"), Item(-1, 0, 0, pkg = "b", cls = "B"))
        // What export() writes to files/auto-backup.json today.
        val now = readableBackup(emptyMap(), items, 2, emptyMap(), emptyList()) { "s$it" }
        val l = backupLayout(now.toString())!!
        assertEquals(items, l.items)
        assertEquals(2, l.sub)
        // The format before it: the preference files as they were, the subgrid factor in the settings.
        val old = org.json.JSONObject().put("format", "vulcan-backup-1")
            .put("layout", org.json.JSONObject().put("items", itemsToJson(items))).put("looks", org.json.JSONObject().put("subgrid", "on"))
        assertEquals(items to 2, backupLayout(old.toString())!!.let { it.items to it.sub })
        // A settings-only style file holds no layout: the next backup is tried, home isn't taken as empty.
        assertEquals(null, backupLayout("""{"format":"vulcan-backup-2","settings":{}}"""))
    }

    @Test
    fun handWrittenBackupIsChecked() {
        val json = """{"format":"vulcan-backup-2","settings":{"cols":"100","labels":false,"labelColor":"#80FF8800","g_doubleTap":"lock",
            "g_swipeDown":"page:2","accent":"#12","iconPack":"x.pack"},"home":{"items":[{"page":0,"x":0,"y":0,"pkg":"chrome"},
            {"page":0,"x":1,"y":0,"pkg":"maps","shortcutId":"s"}]}}"""
        val (files, rejected) = readableBackupFiles(org.json.JSONObject(json)) { pkg, _ -> if (pkg == "chrome") "Main" else null }
        assertEquals(setOf("cols=100", "accent=#12"), rejected.toSet())
        assertEquals(mapOf("labels" to "off", "labelColor" to "#80FF8800", "g_doubleTap" to "lock", "g_swipeDown" to "page:2", "iconPack" to "x.pack",
            "defaults" to "nova", "defaults032" to "on"),
            files["looks"])
        assertEquals(listOf(Item(0, 0, 0, pkg = "chrome", cls = "Main"), Item(0, 1, 0, pkg = "maps", shortcutId = "s")),
            itemsFromJson(files["layout"]!!["items"]!!))
        assertEquals("1", files["layout"]!!["sub"])
    }

    @Test
    fun aBackupFromBeforeTheTabBarStillRestores() {
        // Tab bar replaced the Work tab setting; an older file still loads, and the retired key does no harm.
        val json = """{"format":"vulcan-backup-2","settings":{"tabBar":"scroll","workTab":"off"},"home":{"items":[]}}"""
        val (files, rejected) = readableBackupFiles(org.json.JSONObject(json)) { _, _ -> null }
        assertEquals(emptyList<String>(), rejected)
        assertEquals("scroll", files["looks"]!!["tabBar"])
        assertEquals("off", files["looks"]!!["workTab"])
    }

    @Test
    fun settingsOnlyDesignerFileDoesNotSupplyLayoutOrDrawerData() {
        val style = org.json.JSONObject("""{"format":"vulcan-backup-2","settings":{"labels":false,"cols":"100"}}""")
        val (files, rejected) = readableBackupFiles(style) { _, _ -> error("A style has no apps") }
        assertEquals(mapOf("looks" to mapOf("labels" to "off")), files)
        assertEquals(listOf("cols=100"), rejected)

        // A real backup can intentionally restore an empty home.
        style.put("home", org.json.JSONObject("""{"items":[]}"""))
        val (backup, _) = readableBackupFiles(style) { _, _ -> null }
        assertEquals("[]", backup["layout"]?.get("items"))
        assertEquals(emptyMap<String, String>(), backup["data"])
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
        // A tab's colour survives a save; one that isn't #RRGGBB falls back to the theme's.
        assertEquals(listOf(Group("T", "tab", listOf("k"), "#E53935")), groupsFromJson(groupsToJson(listOf(Group("T", "tab", listOf("k"), "#E53935")))))
        assertEquals(listOf(Group("T", "tab", emptyList())), groupsFromJson("""[{"name":"T","type":"tab","apps":[],"color":"red"}]"""))
        // All apps' colour (#131) takes the same check.
        assertEquals("#2F6F73", tabColor("#2F6F73")); assertEquals(null, tabColor("red")); assertEquals(null, tabColor(null))
        // Reserved tab keys, blank names and repeats are numbered, so each group keeps its own identity and apps.
        val read = groupsFromJson(groupsToJson(listOf("work", "private", "", "T", "T").map { Group(it, "tab", emptyList()) }))
        assertEquals(listOf("work 2", "private 2", "2", "T", "T 2"), read.map { it.name })
    }

    @Test fun aBackupWithoutHomeItemsIsRefusedNotReadAsEmpty() {
        for (home in listOf("", ""","home":{}""", ""","home":{"Items":[]}""", ""","home":[]"""))
            assertThrows(IllegalArgumentException::class.java) { readableBackupFiles(org.json.JSONObject("""{"format":"vulcan-backup-2","data":{}$home}""")) { _, _ -> null } }
        assertEquals(emptyList<Item>(), itemsFromJson(readableBackupFiles(org.json.JSONObject("""{"format":"vulcan-backup-2","home":{"items":[]}}""")) { _, _ -> null }.first.getValue("layout").getValue("items")))
    }

    @Test fun appPickerKeepsTickedAppsItCouldNotShow() {
        assertEquals(listOf("a", "c", "locked"), pickerResult(listOf("a", "b", "c"), setOf("c", "locked", "a")))
        assertEquals(emptyList<String>(), pickerResult(listOf("a"), emptySet()))
    }

    @Test fun groupNamesAndTheWorkTabsPlace() {
        val groups = listOf(Group("Games", "tab", emptyList()))
        assertEquals(listOf(true, true, true, false), listOf("Games", "work", "private", "Work").map { nameTaken(groups, it) })
        // A, Work, B, C with A deleted: Work stays between what's left of its neighbours.
        assertEquals(0, workAtAfter(listOf("A", "B", "C"), listOf("B", "C"), 1))
        assertEquals(1, workAtAfter(listOf("A", "B", "C"), listOf("A", "B"), 1))
        // A rename, an added tab or a reorder leaves it alone.
        assertEquals(2, workAtAfter(listOf("A", "B"), listOf("A", "X"), 2))
        assertEquals(1, workAtAfter(listOf("A"), listOf("A", "B"), 1))
    }

    @Test fun aHandWrittenBackupIsNotTakenForAnOldInstall() {
        val looks = readableBackupFiles(org.json.JSONObject("""{"format":"vulcan-backup-2","settings":{"cols":"6"},"home":{"items":[]}}""")) { _, _ -> null }.first.getValue("looks")
        assertEquals(mapOf("cols" to "6", "defaults" to "nova", "defaults032" to "on"), looks)
    }

    @Test fun aFormatOneBackupIsCheckedLikeFormatTwo() {
        val old = org.json.JSONObject("""{"format":"vulcan-backup-1","layout":{"items":"[]"},"looks":{"pages":"100000","cols":"6","subgrid":"on"},"data":{"hidden":"[]"}}""")
        val (files, rejected) = legacyBackupFiles(old)
        assertEquals(mapOf("cols" to "6", "subgrid" to "on"), files["looks"])
        assertEquals(listOf("pages=100000"), rejected)
        assertEquals(mapOf("hidden" to "[]"), files["data"])
    }

    @Test fun aFormatOneBackupWithoutALayoutReplacesNoLayout() {
        // import() clears every file when "layout" is among them: settings alone must not wipe home.
        val (files, _) = legacyBackupFiles(org.json.JSONObject("""{"format":"vulcan-backup-1","looks":{"cols":"6"}}"""))
        assertEquals(mapOf("looks" to mapOf("cols" to "6")), files)
    }
}
