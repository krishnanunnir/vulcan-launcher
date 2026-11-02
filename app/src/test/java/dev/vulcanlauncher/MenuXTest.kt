package dev.vulcanlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class MenuXTest {
    @Test fun leftEdgeWhenItFits() = assertEquals(40, menuX(40, 180, 560, 1080, 20))
    @Test fun rightEdgeWhenLeftRunsOff() = assertEquals(1040 - 560, menuX(900, 1040, 560, 1080, 20))
    @Test fun keptOnScreen() = assertEquals(20, menuX(0, 100, 560, 1080, 20))
    @Test fun widerThanScreen() = assertEquals(20, menuX(300, 400, 2000, 1080, 20))
}

class SpringScaleTest {
    @Test fun roomyKeepsDefault() = assertEquals(SPRING_SCALE, springScale(1000, 100f, 1500f), 0f)
    @Test fun tallPageShrinksToFit() = assertEquals(0.7f, springScale(2000, 100f, 1500f), 1e-6f)
    @Test fun neverBelowHalf() = assertEquals(0.5f, springScale(2000, 100f, 200f), 0f)
    @Test fun unmeasured() = assertEquals(SPRING_SCALE, springScale(0, 100f, 200f), 0f)
}
