package dev.vulcanlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class IconCacheTest {
    @Test
    fun capacityScalesWithTheHeapAndStaysBounded() {
        assertEquals(64, iconCacheCapacity(64L * 1024 * 1024))     // a thirty-second is 2 MB: floored
        assertEquals(74, iconCacheCapacity(256L * 1024 * 1024))    // 8 MB of icons at ~110 kB each
        assertEquals(112, iconCacheCapacity(512L * 1024 * 1024))   // capped, however large the heap
        assertEquals(112, iconCacheCapacity(4L * 1024 * 1024 * 1024))
    }
}
