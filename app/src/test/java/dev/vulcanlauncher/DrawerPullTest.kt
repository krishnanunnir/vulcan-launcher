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
