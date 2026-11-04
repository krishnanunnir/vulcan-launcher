package dev.vulcanlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineFeaturesTest {
    @Test
    fun sunTimes() {
        // London, 21 June (day 172), BST: sunrise ≈ 04:43, sunset ≈ 21:21
        val (rise, set) = sunTimes(51.5, -0.13, 172, 1.0)!!
        assertEquals(4.72, rise, 0.1)
        assertEquals(21.36, set, 0.1)
        // Equator around the equinox: about 06:00–18:00 local solar time
        val (r2, s2) = sunTimes(0.0, 0.0, 80, 0.0)!!
        assertEquals(6.0, r2, 0.25)
        assertEquals(18.1, s2, 0.25)
        assertNull(sunTimes(78.0, 15.0, 172, 2.0)) // Svalbard midsummer: midnight sun
        assertTrue(isNightSun(22.0, rise to set))
        assertTrue(isNightSun(3.0, rise to set))
        assertFalse(isNightSun(12.0, rise to set))
    }

    @Test
    fun normalizeExtent() {
        val n = 10
        val box = List(n * n) { i -> if (i % n in 2..7 && i / n in 3..6) 255 else 0 } // 6 wide, 4 tall
        assertEquals(0.6f, visibleExtent(box, n)!!, 1e-6f)
        assertNull(visibleExtent(List(n * n) { 0 }, n))
        assertEquals(1f, visibleExtent(List(n * n) { 255 }, n)!!, 1e-6f)
    }

    @Test
    fun search() {
        assertEquals(SEARCH_ENGINES.size, SEARCH_ENGINE_NAMES.size)
        assertNull(searchUrl("browser", "x"))
        assertEquals("https://duckduckgo.com/?q=a+b%26c", searchUrl("duckduckgo", "a b&c"))
    }

    @Test
    fun importIntents() {
        assertEquals("com.a" to "com.a.Main", intentComponent("#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;component=com.a/.Main;end"))
        assertEquals("com.a" to "x.Y", intentComponent("#Intent;component=com.a/x.Y;end"))
        assertNull(intentComponent("#Intent;action=android.intent.action.VIEW;end"))
        assertEquals("com.g" to "compose id", intentShortcut("#Intent;action=android.intent.action.MAIN;category=com.android.launcher3.DEEP_SHORTCUT;package=com.g;S.shortcut_id=compose%20id;end"))
        assertNull(intentShortcut("#Intent;component=com.a/.Main;end"))
    }

    @Test
    fun importLayout() {
        fun row(id: Long, container: Long, screen: Long, x: Double, y: Double, type: Int = 0, intent: String = "#Intent;component=p$id/.A;end",
                title: String = "", sx: Double = 1.0, sy: Double = 1.0, rank: Int = 0) =
            ImportRow(id, title, intent, container, screen, x, y, sx, sy, type, -1, rank)
        val rows = listOf(
            row(1, -100, 10, 0.0, 0.0),                                   // app on the first screen
            row(2, -100, 20, 1.0, 2.0, type = 2, intent = "", title = "Tools"), // folder on the second screen
            row(3, 2, 0, 0.0, 0.0, rank = 1),
            row(4, 2, 0, 0.0, 0.0, rank = 0),
            row(5, -101, 6, 6.0, 0.0),                                    // dock position 6 with 5 per page → dock page 2, x 1
            row(6, -100, 10, 2.0, 0.0, type = 4, intent = "", sx = 2.0, sy = 2.0), // widget: skipped
        )
        val out = importToItems(rows, mapOf(10L to 0, 20L to 1), dockCols = 5)
        assertFalse(out.subgrid)
        assertEquals(1, out.skipped)
        assertEquals(Item(0, 0, 0, pkg = "p1", cls = "p1.A"), out.items[0])
        val folder = out.items[1]
        assertTrue(folder.folder)
        assertEquals(Item(1, 1, 2, folder = true, label = "Tools", children = folder.children), folder)
        assertEquals(listOf("p4", "p3"), folder.children.map { it.pkg }) // ordered by rank
        assertEquals(Item(-2, 1, 0, pkg = "p5", cls = "p5.A"), out.items[2])

        val half = importToItems(listOf(row(1, -100, 10, 0.5, 1.5)), mapOf(10L to 0), 5)
        assertTrue(half.subgrid)
        assertEquals(Item(0, 1, 3, 2, 2, pkg = "p1", cls = "p1.A"), half.items[0])
    }

    @Test
    fun importPreferences() {
        val xml = """<?xml version='1.0'?><map><int name="desktop_grid_cols" value="6" /><string name="desktop_grid_rows">7</string>
            <int name="dock_grid_cols" value="4" /><boolean name="x" value="true" /><int name="desktop_page_count" value="2" /></map>"""
        assertEquals(mapOf("cols" to "6", "rows" to "7", "dockCols" to "4", "pages" to "2"), importedPrefs(xml))
    }

    @Test
    fun smartFolders() {
        val none = android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED
        val social = android.content.pm.ApplicationInfo.CATEGORY_SOCIAL
        fun cat(pkg: String, label: String, system: Boolean = false) = smartCategory(none, false, null, pkg, label, system)
        assertEquals(android.content.pm.ApplicationInfo.CATEGORY_AUDIO, smartCategory(android.content.pm.ApplicationInfo.CATEGORY_AUDIO, false, null, "x", "x", false))
        assertEquals(android.content.pm.ApplicationInfo.CATEGORY_GAME, smartCategory(none, true, social, "x", "x", false))
        assertEquals(CAT_TOOLS, smartCategory(none, false, CAT_TOOLS, "com.whatsapp", "WhatsApp", false)) // role beats words
        assertEquals(social, cat("com.whatsapp", "WhatsApp"))
        assertEquals(CAT_FINANCE, cat("com.phonepe.app", "PhonePe"))
        assertEquals(social, cat("com.google.android.dialer", "Phone", system = true)) // not Tools
        assertEquals(android.content.pm.ApplicationInfo.CATEGORY_AUDIO, cat("com.amazon.mp3", "Amazon Music")) // not shopping
        assertEquals(android.content.pm.ApplicationInfo.CATEGORY_VIDEO, cat("com.amazon.avod.thirdpartyclient", "Prime Video"))
        assertEquals(CAT_SHOPPING, cat("in.amazon.mShop.android.shopping", "Amazon"))
        assertNull(cat("com.example.display", "Play")) // "play" and "display" aren't "pay"
        assertNull(cat("org.example.thing", "Thing"))
        assertEquals(CAT_TOOLS, cat("com.sec.android.app.something", "Something", system = true))

        val groups = listOf(Group("Social", "folder", listOf("a")), Group("Mine", "folder", listOf("b")), Group("Work", "tab", listOf("c")))
        val (out, made, added) = smartGroups(groups, mapOf("Social" to listOf("a", "b", "d"), "Games" to listOf("e", "f"), "News" to listOf("g"), "Work" to listOf("h", "i")))
        assertEquals(listOf("a", "d"), out.first { it.name == "Social" }.apps) // b stays in Mine
        assertEquals(listOf("e", "f"), out.first { it.name == "Games" }.apps)
        assertTrue(out.none { it.name == "News" }) // one app isn't a folder
        assertEquals(1, out.count { it.name == "Work" }) // a tab's name isn't reused
        assertEquals(1, made)
        assertEquals(1, added)
    }
}
