package dev.vulcanlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeStateTest {
    @Test fun lightPanelsDarkenTheStatusIcons() {
        assertTrue(lightStatusIcons(1f, darkTheme = false, over = OverlayKind.FOLDER, housingDrawer = true, forceDark = false))
        assertTrue(lightStatusIcons(1f, darkTheme = false, over = null, housingDrawer = false, forceDark = false))
        // Under halfway, or a dark theme: light icons.
        assertFalse(lightStatusIcons(0.4f, darkTheme = false, over = OverlayKind.FOLDER, housingDrawer = false, forceDark = false))
        assertFalse(lightStatusIcons(1f, darkTheme = true, over = OverlayKind.NOW, housingDrawer = false, forceDark = false))
    }

    @Test fun darkScrimAndHousingDrawerKeepLightIcons() {
        assertFalse(lightStatusIcons(1f, darkTheme = false, over = OverlayKind.OVERVIEW, housingDrawer = false, forceDark = false))
        assertFalse(lightStatusIcons(1f, darkTheme = false, over = null, housingDrawer = true, forceDark = false))
        // The housing rule is the drawer's: a panel over home still darkens them.
        assertTrue(lightStatusIcons(1f, darkTheme = false, over = OverlayKind.RESIZE, housingDrawer = true, forceDark = false))
        assertTrue(lightStatusIcons(0f, darkTheme = true, over = OverlayKind.OVERVIEW, housingDrawer = true, forceDark = true))
    }

    @Test fun onlyTheOverviewRecedesHome() {
        assertEquals(listOf(OverlayKind.OVERVIEW), OverlayKind.entries.filter { it.depth })
        assertEquals(listOf(OverlayKind.OVERVIEW), OverlayKind.entries.filter { it.darkScrim })
    }
}

class LifetimeTest {
    @Test fun boundWorkRunsWithinItsLifetime() {
        val l = Lifetime()
        var ran = 0
        val work = l.bind { ran++ }
        work(); work()
        assertEquals(2, ran)
    }

    @Test fun aRebuildDropsWorkFromBefore() {
        val l = Lifetime()
        var stale = 0
        var fresh = 0
        val old = l.bind { stale++ }
        val alive = l.token()
        l.end()
        val now = l.bind { fresh++ }
        old(); now()
        assertEquals(0, stale)
        assertEquals(1, fresh)
        assertFalse(alive())
        assertTrue(l.token()())
    }

    @Test fun everyRebuildEndsOneLifetime() {
        val l = Lifetime()
        val first = l.token()
        l.end()
        val second = l.token()
        l.end()
        assertFalse(first())
        assertFalse(second())
        assertEquals(2, l.generation)
    }
}
