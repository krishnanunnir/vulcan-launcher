package dev.vulcanlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerPullTest {
    @Test
    fun releaseOpensByDistanceOrFling() {
        assertTrue(drawerOpens(0.6f, 0f, 1000f))       // past halfway
        assertFalse(drawerOpens(0.4f, 0f, 1000f))      // short of halfway
        assertTrue(drawerOpens(0.1f, 1500f, 1000f))    // short pull, flung up
        assertFalse(drawerOpens(0.9f, -1500f, 1000f))  // nearly open, flung down
        assertTrue(drawerOpens(0.35f, 0f, 1000f, 0.3f))  // pulled up past 30%: opens
        assertFalse(drawerOpens(0.65f, 0f, 1000f, 0.7f)) // pulled down below 70%: closes
    }

    @Test
    fun releaseIsQuickerWhenFlungFaster() {
        assertEquals(200L, swipeDuration(0f, 0.5f))   // slow release: 400 ms per whole travel
        assertEquals(80L, swipeDuration(10f, 0.5f))   // 10 px/ms: five times quicker
        assertEquals(80L, swipeDuration(50f, 0.5f))   // never under 80 ms
        assertEquals(80L, swipeDuration(0f, 0f))      // a tiny remainder still counts as a fifth
    }
}

class PagingTest {
    @Test
    fun flingSettlesFromWhereTheFingerIs() {
        assertEquals(2, releasePage(1, 1.3f, -2000f, 500f))  // flung on toward the next page
        assertEquals(1, releasePage(1, 1.3f, 2000f, 500f))   // short drag toward 2, flung back: home to 1, not past it to 0
        assertEquals(1, releasePage(1, 1.6f, 2000f, 500f))   // long drag toward 2, flung back: returns to 1
        assertEquals(0, releasePage(1, 1.0f, 2000f, 500f))   // flung from rest goes one page
        assertEquals(1, releasePage(0, 0.45f, 100f, 500f))   // slow release past 40%: on to the next page
        assertEquals(0, releasePage(0, 0.35f, 100f, 500f))   // slow release short of 40%: back
        assertEquals(0, releasePage(1, 0.55f, 100f, 500f))   // dragged back from 1 past 40%: on to 0
        assertEquals(0, releasePage(0, -0.1f, 100f, 500f))   // released in edge overscroll
    }

    @Test
    fun snapTakesLongerWhenSlower() {
        assertEquals(750L, snapDuration(500, 1000, 100f, 250f, 1500f))                                      // slow release: fixed 750 ms
        assertTrue(snapDuration(500, 1000, 2000f, 250f, 1500f) > snapDuration(500, 1000, 6000f, 250f, 1500f)) // faster fling, quicker snap
        assertEquals(snapDuration(500, 1000, 300f, 250f, 1500f), snapDuration(500, 1000, 1500f, 250f, 1500f)) // floored at the minimum snap speed
    }

    @Test
    fun overscrollResistsAndStopsAtTheLimit() {
        assertEquals(0f, rubberBand(0f, 100f), 0f)
        assertTrue(rubberBand(50f, 100f) in 1f..50f)
        assertTrue(rubberBand(10_000f, 100f) < 100f)
        assertEquals(-rubberBand(50f, 100f), rubberBand(-50f, 100f), 0f)
    }
}

class SwipedTabTest {
    @org.junit.Test fun neighbours() {
        val keys = listOf("", "work", "Games")
        org.junit.Assert.assertEquals("work", swipedTab(keys, "", true))
        org.junit.Assert.assertEquals("", swipedTab(keys, "work", false))
        org.junit.Assert.assertEquals("", swipedTab(keys, "Games", true))
        org.junit.Assert.assertEquals("Games", swipedTab(keys, "", false))
        org.junit.Assert.assertNull(swipedTab(listOf(""), "", true))
        org.junit.Assert.assertNull(swipedTab(emptyList(), "", true))
    }
}

class MoveGroupTest {
    private val zulu = Group("Zulu", "tab", listOf("a"))
    private val box = Group("Box", "folder", emptyList())
    private val alpha = Group("Alpha", "tab", listOf("b"))
    private val groups = listOf(zulu, box, alpha)

    @org.junit.Test fun swapsWithNearestTabSkippingFolders() {
        org.junit.Assert.assertEquals(listOf(alpha, box, zulu), moveGroup(groups, "Alpha", -1))
        org.junit.Assert.assertEquals(listOf(alpha, box, zulu), moveGroup(groups, "Zulu", 1))
    }
    @org.junit.Test fun nullAtEitherEnd() {
        org.junit.Assert.assertNull(moveGroup(groups, "Zulu", -1))
        org.junit.Assert.assertNull(moveGroup(groups, "Alpha", 1))
        org.junit.Assert.assertNull(moveGroup(groups, "Box", 1))
        org.junit.Assert.assertNull(moveGroup(groups, "Nope", 1))
    }
}

class ManualOrderTest {
    private fun eq(expected: Any?, actual: Any?) = org.junit.Assert.assertEquals(expected, actual)

    @org.junit.Test fun manualSortUsesRankThenLabels() {
        val keys = listOf(SortKey("Zulu", 0, 0, 0), SortKey("Bravo", 0, 0), SortKey("Mike", 0, 0, 1), SortKey("Alpha", 0, 0))
        eq(listOf("Zulu", "Mike", "Alpha", "Bravo"), keys.sortedWith(drawerComparator("manual", java.util.Locale.ENGLISH)).map { it.label })
    }
    @org.junit.Test fun tabKeepsItsOwnOrderOnlyWhenManual() {
        val groups = listOf(Group("T", "tab", listOf("c", "a", "x", "b")))
        val all = listOf("a", "b", "c")
        eq(listOf("a", "b", "c"), tabApps(all, groups, "T", emptySet(), false, true, emptySet(), false))
        eq(listOf("c", "a", "b"), tabApps(all, groups, "T", emptySet(), false, true, emptySet(), false, manual = true))
        eq(listOf("c", "b"), tabApps(all, groups, "T", setOf("a"), false, true, emptySet(), false, manual = true))
    }
    @org.junit.Test fun arrangingKeepsTheRestBehind() {
        eq(listOf("c", "a", "b", "x"), arranged(listOf("a", "x", "b", "c"), listOf("c", "a", "b")))
        eq(listOf("b", "a"), arranged(emptyList(), listOf("b", "a")))
    }
    @org.junit.Test fun movedSwapsNeighboursAndStopsAtEnds() {
        eq(listOf("b", "a", "c"), moved(listOf("a", "b", "c"), 1, -1))
        eq(listOf("a", "c", "b"), moved(listOf("a", "b", "c"), 1, 1))
        eq(listOf("a", "b", "c"), moved(listOf("a", "b", "c"), 0, -1))
        eq(listOf("a", "b", "c"), moved(listOf("a", "b", "c"), 2, 1))
    }
    @org.junit.Test fun favouritesReplaceMostUsed() {
        val used = mapOf("a" to 5, "b" to 9, "c" to 0, "h" to 20)
        val shown = { k: String -> k != "h" && k != "gone" }
        eq(listOf("b", "a"), frequentApps(emptyList(), used, shown, 4))
        eq(listOf("c", "a"), frequentApps(listOf("c", "gone", "a"), used, shown, 4))
        eq(listOf("c"), frequentApps(listOf("c", "a"), used, shown, 1))
        eq(listOf("b", "a"), frequentApps(listOf("gone"), used, shown, 4))
    }
    @org.junit.Test fun otherGroupsOfEachApp() {
        val groups = listOf(Group("A", "tab", listOf("x", "y")), Group("B", "folder", listOf("x")), Group("C", "tab", listOf("z")))
        eq(mapOf("x" to listOf("A", "B"), "y" to listOf("A"), "z" to listOf("C")), groupsOfApps(groups, null))
        eq(mapOf("x" to listOf("B"), "z" to listOf("C")), groupsOfApps(groups, "A"))
    }
}
