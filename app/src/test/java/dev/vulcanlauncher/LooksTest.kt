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
    fun fitItemsMovesNegativeCellsAndEmptySizes() {
        val keep = Item(0, 0, 0, pkg = "a")
        // As read from outside: wellFormed puts a bad cell off the grid, and fitItems moves it.
        val out = fitItems(listOf(keep, Item(0, -1, 0, pkg = "b"), Item(0, 2, 2, w = 0, h = -3, pkg = "c")).mapNotNull(::wellFormed), pages = 1, cols = 4, rows = 4, dockCols = 4)
        assertEquals(listOf(keep, Item(0, 1, 0, pkg = "b"), Item(0, 2, 0, pkg = "c")), out)
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
        // A teardrop badge's sharp corner points at the icon's centre, wherever the badge sits.
        for ((pos, point) in mapOf("topRight" to (0f to 1f), "topLeft" to (1f to 1f), "bottomRight" to (0f to 0f), "bottomLeft" to (1f to 0f)))
            assertTrue(pos, point in badgeOutline("teardrop", pos))
        assertEquals(shapeOutline("circle"), badgeOutline("circle", "bottomLeft"))
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
            val c = choiceOf(k)!!
            assertTrue(k, v in c.values && v != c.default)
        }
    }

    /** Every setting of a sectioned page shows in exactly one of its sections, and every section key is a real setting. */
    @Test
    fun sectionsCoverEverySetting() {
        for ((group, sections) in SECTIONS) {
            val listed = sections.flatMap { it.second }.filterNot { it.startsWith("@") }
            assertEquals(group, listed.size, listed.toSet().size)
            val keys = LOOKS.filter { it.group == group }.map { it.key } + if (group == LOOK) listOf("iconPack") else emptyList()
            assertEquals(group, keys.toSet(), listed.toSet())
        }
    }

    /**
     * Settings must not grow without end (CLAUDE.md). A new setting replaces or merges an old one; raising
     * [SETTINGS_BUDGET] needs the owner's yes, said in the commit message. Gestures are one row per input, so exempt.
     */
    @Test
    fun settingsStayWithinBudget() {
        assertTrue("${LOOKS.size} settings, budget $SETTINGS_BUDGET", LOOKS.size <= SETTINGS_BUDGET)
        for ((group, sections) in SECTIONS) for ((title, keys) in sections)
            if (keys !== GESTURE_KEYS) assertTrue("$group section $title has ${keys.size} rows", keys.size <= MAX_SECTION_ROWS)
    }

    @Test
    fun pagePaddingReadsSidesTopAndBottom() {
        assertEquals(Triple(16, 8, 24), paddingOf("16x8x24"))
        // Two numbers, from before top and bottom were split (#96): the second is both.
        assertEquals(Triple(16, 8, 8), paddingOf("16x8"))
        assertEquals(Triple(0, 0, 0), paddingOf("0x0"))
        // Out of range is held to the last step; anything else, such as the old one-number value, is none.
        assertEquals(Triple(48, 0, 0), paddingOf("99x0"))
        assertEquals(Triple(0, 0, 0), paddingOf("32"))
        assertEquals(Triple(0, 0, 0), paddingOf("16xax8"))
        assertEquals(Triple(0, 0, 0), paddingOf(""))
    }

    @Test fun gridNumbersStayWithinTheirSetting() {
        fun c(key: String) = choiceOf(key)!!
        assertEquals(9, c("pages").intOf("100000"))
        assertEquals(3, c("cols").intOf("-4"))
        assertEquals(5, c("dockCols").intOf("five"))
        assertEquals(6, c("rows").intOf(null))
        assertEquals(7, c("rows").intOf("7"))
    }

    @Test fun aHugeSizeDoesNotOverflowIntoAFit() {
        val huge = Item(0, 1, 0, w = Int.MAX_VALUE, h = 1, pkg = "a")
        val moved = fitItems(listOfNotNull(wellFormed(huge)), 1, 5, 5, 5).single()
        assertEquals(5, moved.w)
        assertEquals(Item(0, MAX_CELL, MAX_CELL, w = MAX_CELL, h = 1), itemsFromJson(itemsToJson(listOf(Item(0, 1, 2, w = Int.MAX_VALUE, h = Int.MIN_VALUE)))).single())
    }

    /** Only settings home reads when used skip the rebuild; a new one is rebuilt for until it's proven otherwise. */
    @Test fun onlyUseTimeSettingsSkipTheRebuild() {
        val live = LOOKS.filter { it.reload == Reload.LIVE }.map { it.key }.toSet()
        assertEquals(GESTURE_KEYS.toSet() - setOf("g_swipeUp", "g_swipeRight") + setOf("homeButton", "homeFirst"), live)
    }

    private companion object {
        const val SETTINGS_BUDGET = 118 // 120 for Widget padding (#121), the owner's yes 2026-11-24; 118 since Pages and Default page are the layout's (2026-11-27)
        const val MAX_SECTION_ROWS = 8
    }
}
