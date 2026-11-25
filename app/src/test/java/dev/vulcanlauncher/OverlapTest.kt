package dev.vulcanlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlapTest {
    @Test
    fun overlaps() {
        assertTrue(overlaps(0, 0, 2, 2, 1, 1, 1, 1))   // icon inside a 2x2 widget
        assertTrue(overlaps(1, 1, 1, 1, 0, 0, 2, 2))   // symmetric
        assertFalse(overlaps(0, 0, 2, 2, 2, 0, 1, 1))  // touching right edge
        assertFalse(overlaps(0, 0, 2, 2, 0, 2, 1, 1))  // touching bottom edge
        assertFalse(overlaps(0, 0, 1, 1, 3, 3, 1, 1))  // far apart
    }

    @Test
    fun subgridOffDoesNotStackItemsThatSharedACell() {
        // Four half-cell items in one full cell: each takes its own cell instead of all landing on (0, 0).
        val quarter = listOf(Item(0, 0, 0, pkg = "a"), Item(0, 1, 0, widgetId = 1), Item(0, 0, 1, pkg = "c"), Item(0, 1, 1, widgetId = 2))
        assertEquals(listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1), scaleItems(quarter, -2).map { it.x to it.y })
        // An overlap that was already there (widget overlap on) is kept as it is.
        val covered = listOf(Item(0, 0, 0, 4, 4, widgetId = 3), Item(0, 2, 2, 2, 2, pkg = "d"))
        assertEquals(listOf(Item(0, 0, 0, 2, 2, widgetId = 3), Item(0, 1, 1, 1, 1, pkg = "d")), scaleItems(covered, -2))
    }

    @Test
    fun dockItemMovedHomeTakesAFullCell() {
        // The dock shrank to one cell, held by a: b goes home as a whole icon, 2x2 half cells with the subgrid on.
        val dock = listOf(Item(DOCK, 0, 0, pkg = "a"), Item(DOCK, 1, 0, pkg = "b"))
        assertEquals(Item(0, 0, 0, 2, 2, pkg = "b"), fitItems(dock, pages = 1, cols = 8, rows = 8, dockCols = 1, sub = 2)[1])
    }

    @Test
    fun pickingFolderAppsKeepsAppsThePickerCouldNotShow() {
        val shown = Item(0, 0, 0, pkg = "shown", cls = "c")
        val dropped = Item(0, 0, 0, pkg = "dropped", cls = "c")
        val locked = Item(0, 0, 0, pkg = "locked", cls = "c")
        val action = Item(0, 0, 0, action = "settings")
        val keys = mapOf(shown to "shown", dropped to "dropped")
        assertEquals(listOf(shown, locked, action), keptFolderChildren(listOf(shown, dropped, locked, action), listOf("shown")) { keys[it] })
    }

    @Test
    fun regrownGridDoesNotStackAnItemMovedIntoAHiddenOnesCells() {
        val widget = Item(0, 0, 0, 5, 2, widgetId = 7)   // hidden while the grid had 4 columns
        val folder = Item(0, 0, 0, pkg = "a")            // moved to (0,0) meanwhile
        val out = fitItems(listOf(folder, widget), pages = 1, cols = 5, rows = 5, dockCols = 5, overlapOk = false)
        assertEquals(widget, out.first { it.widgetId == 7 })
        assertEquals(Item(0, 0, 2, pkg = "a"), out.first { it.pkg == "a" })
        // With widget overlap on, an overlap is the user's and stays.
        assertEquals(listOf(folder, widget), fitItems(listOf(folder, widget), pages = 1, cols = 5, rows = 5, dockCols = 5))
    }
}
