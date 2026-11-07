package dev.vulcanlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class IconCacheTest {
    @Test
    fun capacityScalesWithTheHeapAndStaysBounded() {
        assertEquals(64, iconCacheCapacity(64L * 1024 * 1024))     // a sixthteenth is 4 MB: floored
        assertEquals(148, iconCacheCapacity(256L * 1024 * 1024))   // 16 MB of icons at ~110 kB each
        assertEquals(256, iconCacheCapacity(512L * 1024 * 1024))   // capped, however large the heap
        assertEquals(256, iconCacheCapacity(4L * 1024 * 1024 * 1024))
    }
}
