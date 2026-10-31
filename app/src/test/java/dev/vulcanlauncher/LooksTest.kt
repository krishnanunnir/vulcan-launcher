package dev.vulcanlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class LooksTest {
    @Test
    fun fitItemsMovesWhatNoLongerFits() {
        val keep = Item(0, 0, 0, pkg = "a")
        val offGrid = Item(0, 4, 4, pkg = "b")           // 5x5 → 4x4
        val lostPage = Item(2, 0, 0, pkg = "c")          // 3 pages → 2
        val dock = Item(DOCK, 4, 0, pkg = "d")           // 5 dock slots → 4
        val wide = Item(1, 0, 0, w = 5, h = 2, widgetId = 7)
        val out = fitItems(listOf(keep, offGrid, lostPage, dock, wide), pages = 2, cols = 4, rows = 4, dockCols = 4)
        assertEquals(5, out.size)
        assertTrue(keep in out)
        assertEquals(Item(0, 1, 0, pkg = "b"), out.first { it.pkg == "b" })
        assertEquals(Item(0, 2, 0, pkg = "c"), out.first { it.pkg == "c" })
        assertEquals(Item(DOCK, 0, 0, pkg = "d"), out.first { it.pkg == "d" })
        assertEquals(Item(1, 0, 0, w = 4, h = 2, widgetId = 7), out.first { it.isWidget })
    }

    @Test
    fun fitItemsKeepsItemWhenNoRoom() {
        val a = Item(0, 0, 0, pkg = "a")
        val b = Item(1, 0, 0, pkg = "b")
        assertEquals(listOf(a, b), fitItems(listOf(a, b), pages = 1, cols = 1, rows = 1, dockCols = 1))
    }

    @Test
    fun shapes() {
        assertNull(shapeOutline("system"))
        for (s in SHAPES.drop(1)) shapeOutline(s)!!.forEach { (x, y) -> assertTrue(s, x in -1e-4f..1.0001f && y in -1e-4f..1.0001f) }
        shapeOutline("circle")!!.forEach { (x, y) -> assertEquals(0.5, hypot(x - 0.5, y - 0.5), 1e-4) }
        assertEquals(listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f), shapeOutline("square"))
        assertEquals(6, shapeOutline("hexagon")!!.size)
        assertEquals(SHAPES.size, SHAPE_NAMES.size)
    }

    @Test
    fun parseComponent() {
        assertEquals("com.a/com.a.Main", parseComponent("ComponentInfo{com.a/com.a.Main}"))
        assertEquals("com.a/com.a.Main", parseComponent("ComponentInfo{com.a/.Main}"))
        assertNull(parseComponent("ComponentInfo{com.a}"))
        assertNull(parseComponent(":LAUNCHER_ACTION_APP_DRAWER"))
    }

    @Test
    fun choicesHaveTheirDefault() {
        for (c in LOOKS) {
            assertTrue(c.key, c.default in c.values)
            // Every value has a name: a string resource, or untranslated text (numbers, sizes).
            assertEquals(c.key, c.values.size, maxOf(c.names.size, c.shown.size))
            c.values.indices.forEach { i -> assertTrue("${c.key} $i", i < c.names.size || c.shown[i].isNotEmpty()) }
        }
    }

    @Test
    fun preNovaDefaultsAreChangedValidChoices() {
        // "glance" is no longer a setting: it turns into a clock item.
        for ((k, v) in PRE_NOVA_DEFAULTS - "glance") {
            val c = LOOKS.first { it.key == k }
            assertTrue(k, v in c.values && v != c.default)
        }
    }
}
