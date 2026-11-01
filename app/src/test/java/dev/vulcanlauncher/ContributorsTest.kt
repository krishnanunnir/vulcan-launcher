package dev.vulcanlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class ContributorsTest {
    @Test fun contributorInitials() {
        assertEquals("KR", initials("Krishnanunni R"))
        assertEquals("L", initials("LiterBol"))
        assertEquals("AB", initials(" ada  bee cee"))
        assertEquals("", initials(" "))
    }
}
