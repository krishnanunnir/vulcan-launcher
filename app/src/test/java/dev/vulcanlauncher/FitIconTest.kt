package dev.vulcanlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class FitIconTest {
    @Test
    fun fitIcon() {
        assertEquals(192 to true, fitIcon(192, 270, 330, 60))      // 4x6 grid: room to spare
        assertEquals(158 to true, fitIcon(192, 180, 330, 60))      // narrow: icon shrinks, keeps air
        assertEquals(130 to true, fitIcon(192, 270, 190, 60))      // short: icon shrinks above the label
        assertEquals(79 to false, fitIcon(192, 90, 330, 60))       // 12 columns: label goes, icon fits
        assertEquals(105 to false, fitIcon(192, 600, 120, 60))     // landscape row: label goes, icon keeps air
        assertEquals(150 to true, fitIcon(192, 1 shl 24, 210, 60)) // unspecified width
        assertEquals(88 to true, fitIcon(192, 270, 100, 0))        // no label: capped by height, with air
    }
}

class ScaleSpanTest {
    @Test
    fun scaleSpan() {
        assertEquals(4, scaleSpan(4, 1080, 1080))   // portrait: unchanged
        assertEquals(9, scaleSpan(4, 1080, 2340))   // landscape columns
        assertEquals(3, scaleSpan(6, 2340, 1080))   // landscape rows
        assertEquals(1, scaleSpan(1, 2340, 1080))   // never below one
    }
}

class SampleSizeTest {
    @Test
    fun sampleSize() {
        assertEquals(16, sampleSize(3000, 168))  // 12 MP photo → 187 px, still above the icon size
        assertEquals(1, sampleSize(168, 168))    // already the right size
        assertEquals(1, sampleSize(100, 168))    // smaller: never upsampled
        assertEquals(1, sampleSize(-1, 168))     // unreadable bounds
    }
}
