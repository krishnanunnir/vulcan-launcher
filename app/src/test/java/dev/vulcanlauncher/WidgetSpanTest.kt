package dev.vulcanlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSpanTest {
    @Test
    fun targetCellsAreClampedToThePage() {
        // A 5×2 widget on a 4-column grid is placed, and labelled, 4×2.
        assertEquals(4 to 2, widgetSpan(5 to 2, 0, 0, 100f, 100f, 4, 5, 1))
        // Subgrid: target cells become sub-cells.
        assertEquals(6 to 4, widgetSpan(3 to 2, 0, 0, 50f, 50f, 8, 10, 2))
    }

    @Test
    fun minimumSizeRoundsUpToSubCells() {
        assertEquals(2 to 1, widgetSpan(null, 110, 90, 100f, 100f, 4, 5, 1))
        // 110 px over 50 px half cells: three half cells, shown as 1½.
        val (w, h) = widgetSpan(null, 110, 40, 50f, 50f, 8, 10, 2)
        assertEquals(3 to 1, w to h)
        assertEquals("1½", cellCount(w, 2))
        assertEquals("2", cellCount(4, 2))
        assertEquals(1 to 1, widgetSpan(null, 0, 0, 100f, 100f, 4, 5, 1))
        assertEquals(4 to 5, widgetSpan(null, 9999, 9999, 100f, 100f, 4, 5, 1))
    }
}
