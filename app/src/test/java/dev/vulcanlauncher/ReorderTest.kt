package dev.vulcanlauncher

import org.junit.Assert.assertEquals
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
        // Moving among the cards shown leaves the hidden ones where they were.
        assertEquals(listOf("apps", "calendar", "media", "contacts"), reorderWithin(NOW_CARDS, listOf("media", "contacts", "apps"), 2, 0))
    }
}
