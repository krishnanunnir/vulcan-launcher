package dev.vulcanlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What stored, restored and imported data that this build didn't write turns into: each case is an input that once
 * crashed home or misplaced items (the commit that fixed it is named).
 */
class BoundaryTest {
    // A 6x5 grid with one page and a 5-cell dock, as home reads a stored layout: decoded, then fitted to the grid.
    private fun home(json: String, overlapOk: Boolean = false) = fitItems(itemsFromJson(json), 1, 6, 5, 5, overlapOk = overlapOk)

    @Test fun aNegativeCellOrAZeroSizeMovesOntoTheGrid() { // ef94dd8
        val out = home("""[{"page":0,"x":0,"y":0,"pkg":"a"},{"page":0,"x":-3,"y":-1,"pkg":"b"},{"page":0,"x":2,"y":2,"w":0,"h":-4,"pkg":"c"}]""")
        assertEquals(Item(0, 0, 0, pkg = "a"), out[0])
        assertEquals(Item(0, 1, 0, pkg = "b"), out[1])
        // Size 0 is size 1, and its spot (now that it is off the grid) is the first free one.
        assertEquals(Item(0, 2, 0, pkg = "c"), out[2])
    }

    @Test fun aHugeSizeOrPositionCannotOverflowTheFit() { // e3a4a57
        val out = home("""[{"page":0,"x":1,"y":0,"w":2147483647,"h":1,"pkg":"a"},{"page":0,"x":2147483647,"y":0,"pkg":"b"}]""")
        assertEquals(listOf(Item(0, 0, 0, 6, 1, pkg = "a"), Item(0, 0, 1, pkg = "b")), out)
        // Read back, a cell is held within MAX_CELL, so x + w can't wrap round to a small number.
        val read = itemsFromJson("""[{"page":0,"x":2147483647,"y":-2147483648}]""")[0]
        assertTrue(read.x in 0..MAX_CELL && read.y in 0..MAX_CELL)
    }

    @Test fun aDockItemOffTheDockMovesIntoIt() {
        val out = home("""[{"page":-1,"x":-1,"y":0,"pkg":"a"},{"page":-1,"x":1,"y":3,"pkg":"b"},{"page":-7,"x":0,"y":0,"pkg":"c"}]""")
        assertEquals(listOf(Item(-1, 0, 0, pkg = "a"), Item(-1, 1, 0, pkg = "b"), Item(-1, 2, 0, pkg = "c")), out)
    }

    @Test fun aHomeItemOnAPageThatIsntThereMovesToTheFirstPage() {
        assertEquals(listOf(Item(0, 0, 0, pkg = "a")), home("""[{"page":2147483647,"x":0,"y":0,"pkg":"a"}]"""))
    }

    @Test fun overlappingItemsAreSeparatedUnlessOverlapIsChosen() { // b64cabc
        val json = """[{"page":0,"x":0,"y":0,"w":2,"h":2,"widgetId":5},{"page":0,"x":1,"y":1,"pkg":"a"}]"""
        assertEquals(Item(0, 2, 0, pkg = "a"), home(json)[1])
        assertEquals(Item(0, 1, 1, pkg = "a"), home(json, overlapOk = true)[1])
    }

    @Test fun aSettingNumberIsHeldToTheSettingsRange() { // a97d0a2
        val pages = LOOKS.first { it.key == "pages" }
        assertEquals(listOf(9, 1, 1, 1, 3), listOf("100000", "-5", "abc", null, "3").map(pages::intOf))
    }

    @Test fun anImportedRowWithABadPlaceStaysOnTheGrid() { // 5e37e9b
        fun row(container: Long, screen: Long, x: Double, y: Double = 0.0, sx: Double = 1.0, sy: Double = 1.0) =
            ImportRow(1, "t", "#Intent;component=p/.A;end", container, screen, x, y, sx, sy, 0, 0, 0)
        val l = importToItems(listOf(row(-101, -2, -3.0), row(-100, -5, 1.0), row(-100, 9_000_000_000, 0.0), row(-100, 0, 1.0, 1.0, 0.0, -2.0)), emptyMap(), 5)
        assertEquals(listOf(-1 to 0, 0 to 1, Int.MAX_VALUE to 0, 0 to 1), l.items.map { it.page to it.x })
        assertEquals(1 to 1, l.items[3].w to l.items[3].h)
        // A malformed escape in a shortcut id drops that shortcut, not the import.
        assertNull(intentShortcut("#Intent;package=p;S.shortcut_id=%zz;end"))
    }

    @Test fun anImportedGridSettingIsHeldToTheSettingsRange() {
        val xml = """<map><int name="desktop_grid_cols" value="100" /><int name="dock_grid_cols" value="0" /><string name="desktop_grid_rows">x</string></map>"""
        assertEquals(mapOf("cols" to "12", "dockCols" to "3"), importedPrefs(xml))
    }

    @Test fun aRestoredFlagOrStringOfTheWrongTypeIsPutRight() { // ef6086b, ef94dd8
        assertEquals(mapOf("welcome" to false, "groups" to "true"), dataTypeFixes(mapOf("welcome" to "false", "groups" to true, "hidden" to "[]")))
        // A value a flag can't be is left for the reader, which takes it as false.
        assertTrue(dataTypeFixes(mapOf("welcome" to "on")).isEmpty())
    }

    @Test fun aBackupsSubgridOtherThanOneOrTwoIsLeftForHomeToIgnore() { // 68dc274
        val (files, _) = readableBackupFiles(org.json.JSONObject("""{"format":"vulcan-backup-2","home":{"sub":7,"items":[]}}""")) { _, _ -> null }
        assertEquals("7", files.getValue("layout")["sub"])
    }

    @Test fun aStoredLayoutIsDecodedOnceAndSaysWhatItLeftOut() { // 0cdc623, 927f199, 68dc274
        val items = """[{"page":0,"x":0,"y":0,"pkg":"a"},{"page":"oops"},{"page":0,"x":1,"y":0,"folder":true,"children":[]}]"""
        val d = decodeLayout(items, "2", "2")
        assertEquals(listOf(Item(0, 0, 0, pkg = "a")), d.value!!.items)
        assertEquals(2, d.value!!.sub)
        // A folder left with no children is dropped with the unreadable item: home keeps the text before saving over it.
        assertEquals(listOf("item 1", "item 2"), d.dropped)
        // A layout from a newer build isn't read at all, however it looks: its items may mean something else.
        assertNull(decodeLayout("[]", "3", "1").value)
        // Neither is one that isn't a list, or a value restored as the wrong type.
        for (bad in listOf("{", "{}", "5", null, 7)) assertNull(decodeLayout(bad, "2", "1").value)
        // No version is version 1, whose positions are in the current subgrid factor; so is one that isn't 1 or 2.
        assertNull(decodeLayout("[]", null, null).value!!.sub)
        assertEquals(listOf("sub 7"), decodeLayout("[]", "2", "7").repaired)
        assertNull(decodeLayout("[]", "2", true).value!!.sub)
    }
}
