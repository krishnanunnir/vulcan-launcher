package dev.vulcanlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReorderTest {
    @Test
    fun movesAnItem() {
        assertEquals(listOf("b", "c", "a"), moveItem(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("c", "a", "b"), moveItem(listOf("a", "b", "c"), 2, 0))
        assertEquals(listOf("a", "b"), moveItem(listOf("a", "b"), 0, 5))
    }

    @Test
    fun targetFollowsTheDraggedCentre() {
        val tops = listOf(0, 100, 200, 300)
        val heights = List(4) { 100 }
        assertEquals(1, reorderTarget(tops, heights, 1, 0f))
        assertEquals(1, reorderTarget(tops, heights, 1, 49f)) // its bottom not yet past the next row's middle
        assertEquals(2, reorderTarget(tops, heights, 1, 51f))
        assertEquals(3, reorderTarget(tops, heights, 1, 260f))
        assertEquals(0, reorderTarget(tops, heights, 1, -60f))
    }

    @Test
    fun workTabMovesAmongTabs() {
        val tabs = listOf("A", "B", "C")
        assertEquals(listOf(WORK_SLOT, "A", "B", "C"), withWorkSlot(tabs, 0))
        assertEquals(listOf("A", "B", "C", WORK_SLOT), withWorkSlot(tabs, 9))
        assertEquals(tabs to 2, moveWithWork(tabs, 0, 0, 2)) // Work dragged down past A and B
        assertEquals(listOf("B", "A", "C") to 0, moveWithWork(tabs, 0, 2, 1)) // B dragged above A, under Work
        assertEquals(listOf("C", "A", "B") to 2, moveWithWork(tabs, 1, 3, 0)) // C to the top: Work stays right after A
        val g = listOf(Group("A", "tab", emptyList()), Group("F", "folder", emptyList()), Group("B", "tab", emptyList()))
        assertEquals(listOf("B", "F", "A"), withTabOrder(g, listOf("B", "A")).map { it.name })
    }

    @Test
    fun groupsReorderWithinTheirType() {
        val g = listOf(Group("A", "tab", emptyList()), Group("F", "folder", emptyList()), Group("B", "tab", emptyList()), Group("C", "tab", emptyList()))
        assertEquals(listOf("C", "F", "A", "B"), reorderGroups(g, "tab", 2, 0).map { it.name })
        assertEquals(g, reorderGroups(g, "folder", 0, 0))
    }

    @Test
    fun pickedAppsLeaveOtherGroups() {
        val g = listOf(Group("A", "tab", listOf("x", "y")), Group("B", "tab", listOf("y", "z")))
        assertEquals(listOf(Group("A", "tab", listOf("x")), Group("B", "tab", listOf("y", "z"))), withoutApps(g, setOf("y"), "B"))
    }

    @Test
    fun nowCardOrder() {
        assertEquals(NOW_CARDS, nowOrder(null))
        assertEquals(listOf("apps", "media", "calendar", "contacts"), nowOrder("apps,bogus,media"))
        // Your own cards join the order: new ones at the end, a removed one's id dropped.
        val w = Item(0, 0, 0, widgetId = 12)
        val a = Item(0, 0, 0, children = listOf(Item(0, 0, 0, pkg = "p", cls = "c")))
        val ids = listOf(nowCardId(w), nowCardId(a))
        assertEquals(NOW_CARDS + ids, nowOrder(null, ids))
        assertEquals(listOf(ids[1], "apps") + NOW_CARDS.dropLast(1) + ids[0], nowOrder("${ids[1]},apps,w99", ids))
        assertEquals(NOW_CARDS, nowOrder("${ids[1]}", emptyList()))
        // An app card's id follows its apps, so it stays put across restarts.
        assertEquals(nowCardId(a), nowCardId(a.copy()))
        assertTrue(nowCardId(a) != nowCardId(Item(0, 0, 0, children = listOf(Item(0, 0, 0, pkg = "q", cls = "c")))))
        // Moving among the cards shown leaves the hidden ones where they were.
        assertEquals(listOf("apps", "calendar", "media", "contacts"), reorderWithin(NOW_CARDS, listOf("media", "contacts", "apps"), 2, 0))
    }
}
