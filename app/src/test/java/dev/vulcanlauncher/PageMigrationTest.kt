package dev.vulcanlauncher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layout version 3 keeps the page count and default page with the items. Every way they can arrive (an older layout,
 * a backup of either format, a style file) ends with them in the layout, and an older build refuses a version 3 one.
 */
class PageMigrationTest {
    private val items = listOf(Item(0, 1, 2, pkg = "a", cls = "A"), Item(3, 0, 0, pkg = "b", cls = "B"), Item(DOCK, 0, 0, pkg = "c", cls = "C"))
    private fun JSONObject.files() = readableBackupFiles(this) { _, _ -> null }.first

    @Test fun aVersionTwoLayoutTakesItsPagesFromWhereAdoptPagesPutThem() {
        // Before adoptPages runs, a version 2 layout has no page keys: one page, the first.
        val bare = decodeLayout(itemsToJson(items), "2", "1").value!!
        assertEquals(1 to 0, bare.pages to bare.defaultPage)
        // After it, the looks values are in the layout, read and held to range like any other.
        val adopted = decodeLayout(itemsToJson(items), "3", "1", "4", "2")
        assertEquals(4 to 2, adopted.value!!.pages to adopted.value!!.defaultPage)
        assertEquals(emptyList<String>(), adopted.repaired)
        val wild = decodeLayout(itemsToJson(items), "3", "1", "100000", "7")
        assertEquals(MAX_PAGES to 7, wild.value!!.pages to wild.value!!.defaultPage)
        assertEquals(listOf("pages 100000"), wild.repaired)
        // A default page past the last page is the last.
        assertEquals(2, decodeLayout("[]", "3", "1", "3", "8").value!!.defaultPage)
    }

    @Test fun aVersionThreeLayoutRoundTrips() {
        val f = layoutFile(items, 2, 4, 3)
        assertEquals("3", f["version"])
        val l = decodeLayout(f["items"], f["version"], f["sub"], f["pages"], f["defaultPage"]).value!!
        assertEquals(listOf(items, 2, 4, 3), listOf(l.items, l.sub, l.pages, l.defaultPage))
    }

    @Test fun anOlderBuildRefusesANewerLayout() {
        // This build refuses version 4; a build of version 2 refuses what this one writes by the same rule (v > its own).
        assertNull(decodeLayout(itemsToJson(items), "4", "1", "2", "0").value)
        assertTrue(layoutFile(items, 1, 2, 0).getValue("version").toInt() > 2)
    }

    @Test fun aReadableBackupCarriesThePagesInHomeAndForOlderBuildsInSettings() {
        val doc = JSONObject(readableBackup(mapOf("cols" to "5"), items, 1, emptyMap(), emptyList(), 4, 2) { "s$it" }.toString())
        assertEquals(4 to 2, doc.getJSONObject("home").getInt("pages") to doc.getJSONObject("home").getInt("defaultPage"))
        assertEquals("4" to "2", doc.getJSONObject("settings").getString("pages") to doc.getJSONObject("settings").getString("defaultPage"))
        val files = doc.files()
        assertEquals("4" to "2", files.getValue("layout")["pages"] to files.getValue("layout")["defaultPage"])
        assertTrue(PAGE_KEYS.none { it in files.getValue("looks") })
        assertEquals(items, itemsFromJson(files.getValue("layout").getValue("items")))
    }

    @Test fun aReadableBackupFromBeforeVersionThreeHasThemInSettings() {
        val l = JSONObject("""{"format":"vulcan-backup-2","settings":{"pages":"5","defaultPage":3},"home":{"items":[]}}""").files()
        assertEquals("5" to "3", l.getValue("layout")["pages"] to l.getValue("layout")["defaultPage"])
        assertTrue(PAGE_KEYS.none { it in l.getValue("looks") })
        // home's win over settings'.
        val both = JSONObject("""{"format":"vulcan-backup-2","settings":{"pages":"5"},"home":{"pages":2,"defaultPage":1,"items":[]}}""").files()
        assertEquals("2" to "1", both.getValue("layout")["pages"] to both.getValue("layout")["defaultPage"])
        // Neither: one page, the first.
        val none = JSONObject("""{"format":"vulcan-backup-2","home":{"items":[]}}""").files()
        assertEquals("1" to "0", none.getValue("layout")["pages"] to none.getValue("layout")["defaultPage"])
    }

    @Test fun aFormatOneBackupHasThemInItsLooks() {
        val old = JSONObject("""{"format":"vulcan-backup-1","layout":{"items":"[]","version":"2"},"looks":{"pages":"4","defaultPage":"1","cols":"6"}}""")
        val (files, rejected) = legacyBackupFiles(old)
        assertEquals("4" to "1", files.getValue("layout")["pages"] to files.getValue("layout")["defaultPage"])
        assertEquals(LAYOUT_VERSION.toString(), files.getValue("layout")["version"])
        assertEquals(mapOf("cols" to "6"), files["looks"])
        assertEquals(emptyList<String>(), rejected)
    }

    @Test fun aStyleFileKeepsThemInLooksForAdoptPages() {
        // No layout to put them in: they stay with the settings, and Store.adoptPages moves them onto the current layout.
        val files = JSONObject("""{"format":"vulcan-backup-2","settings":{"pages":"3","cols":"6"}}""").files()
        assertEquals(mapOf("looks" to mapOf("pages" to "3", "cols" to "6")), files)
        assertEquals(mapOf("looks" to mapOf("pages" to "2")), legacyBackupFiles(JSONObject("""{"format":"vulcan-backup-1","looks":{"pages":"2"}}""")).first)
    }

    @Test fun aNewerLayoutWithOldPageKeysWaitsForTheUsersFirstEdit() {
        // A downgrade: the stored layout is a newer build's, and looks still has page keys from before version 3.
        val newer = mapOf("items" to """[{"page":0,"x":0,"y":0,"pkg":"n"}]""", "version" to "4", "sub" to "1")
        val backup = readableBackup(emptyMap(), items, 1, emptyMap(), emptyList(), 4, 2) { "s$it" }.toString()
        for (launch in 1..2) {
            val l = loadLayout(newer, sequenceOf(backup), 1)
            // The backup stands in, with its own page count and default page.
            assertTrue(l.standIn)
            assertEquals(PageLayout(items, 4, 2), l.layout)
            // Nothing automatic writes: the looks keys wait, and the load on start and the sweeps change home only.
            assertEquals(PageAdoption.WAIT, pageAdoption(newer["version"], readable = false, restored = false))
            assertEquals(false, savesNow(automatic = true) { false })
        }
        // The user's first edit saves the stand-in over the stored text, pages and default page inside it.
        assertTrue(savesNow(automatic = false) { false })
        val edited = loadLayout(newer, sequenceOf(backup), 1).layout.addPage()
        val f = layoutFile(edited.items, 1, edited.pages, edited.defaultPage)
        val now = loadLayout(f, emptySequence(), 1)
        assertEquals(false, now.standIn)
        assertEquals(PageLayout(items, 5, 2), now.layout)
        // From then on the old looks keys are stale: dropped, not moved over the layout's own.
        assertEquals(PageAdoption.DROP, pageAdoption(f["version"], readable = true, restored = false))
        // Before version 3 they are the layout's: moved. A style file's replace the layout's.
        assertEquals(PageAdoption.MOVE, pageAdoption("2", readable = true, restored = false))
        assertEquals(PageAdoption.MOVE, pageAdoption(null, readable = true, restored = false))
        assertEquals(PageAdoption.MOVE, pageAdoption("3", readable = true, restored = true))
        assertEquals(PageAdoption.WAIT, pageAdoption("4", readable = false, restored = true))
    }
}
