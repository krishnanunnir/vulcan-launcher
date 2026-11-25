package dev.vulcanlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun legacyIconFill() {
        val n = 32
        val orange = 0xFFF57C00.toInt()
        // An orange disc with a white mark inside, on transparent: fills the shape in orange.
        val disc = IntArray(n * n) { i ->
            val dx = i % n - 15.5; val dy = i / n - 15.5
            val d = Math.hypot(dx, dy)
            when { d < 6 -> -1; d < 14 -> orange; else -> 0 }
        }
        val (color, extent) = legacyFill(disc, n)!!
        assertEquals(orange, color)
        assertEquals(28f / n, extent, 0.05f)
        // Left half red, right half blue: no one edge colour, but a clean split, so its edge is stretched. At the size
        // reshaped() analyses (64), where a few pixels by the split are within the bound for art.
        assertNotNull(legacyFill(IntArray(64 * 64) { i -> if (i % 64 < 32) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }, 64))
        assertNull(legacyFill(IntArray(n * n), n))
        // An orange "C" with a see-through middle (Calculator++, #92): one edge colour, but not a solid shape, so a plate.
        val letter = IntArray(n * n) { i ->
            val dx = i % n - 15.5; val dy = i / n - 15.5
            if (Math.hypot(dx, dy) in 8.0..14.0 && !(dx > 0 && Math.abs(dy) < 5)) orange else 0
        }
        assertNull(legacyFill(letter, n))
        // Its plate is white, as for any warm colour; a blue one's is a pale tint of its blue; a grey icon's is white.
        assertEquals(-1, plateTint(letter, n))
        assertEquals(0xFF98C9F4.toInt(), plateTint(letter.map { if (it == 0) 0 else 0xFF1E88E5.toInt() }.toIntArray(), n))
        assertEquals(-1, plateTint(IntArray(n * n) { 0xFF808080.toInt() }, n))
        // An orange square with a white mark in its corner: the circle would cut the mark off, so a plate (as Nova does).
        fun inBox(i: Int, a: Int, b: Int) = i % n in a..b && i / n in a..b
        val marked = IntArray(n * n) { i -> when { inBox(i, 5, 9) -> -1; inBox(i, 4, 27) -> orange; else -> 0 } }
        assertNull(legacyFill(marked, n))
        // Without the mark it fills; with the mark in its middle it fills too.
        assertNotNull(legacyFill(IntArray(n * n) { i -> if (inBox(i, 4, 27)) orange else 0 }, n))
        assertNotNull(legacyFill(IntArray(n * n) { i -> when { inBox(i, 13, 18) -> -1; inBox(i, 4, 27) -> orange; else -> 0 } }, n))
        // A wide banner isn't an icon shape: a plate.
        assertNull(legacyFill(IntArray(n * n) { i -> if (i % n in 1..30 && i / n in 9..22) orange else 0 }, n))
    }

    @Test
    fun imageIconParsing() {
        // A picked image is shaped by default; a legacy "uri:" icon keeps doing so on update (#92).
        assertEquals("content://media/1" to true, imageIcon("uri:content://media/1"))
        // The edit dialog's per-icon opt-out.
        assertEquals("content://media/1" to false, imageIcon("uriraw:content://media/1"))
        assertNull(imageIcon(""))
        assertNull(imageIcon("pack:com.example.icons:mail"))
        assertNull(imageIcon("app:com.example/com.example.Mail"))
    }

    @Test
    fun legacyIconGradientEdge() {
        val n = 32
        fun inSquare(i: Int) = i % n in 4..27 && i / n in 4..27
        // A square shading from purple at the top to orange at the bottom: stretched, not plated.
        val grad = IntArray(n * n) { i ->
            val t = i / n * 255 / (n - 1)
            if (inSquare(i)) 0xFF shl 24 or (0x80 + t / 2 shl 16) or (0x20 + t / 3 shl 8) or (0xC0 - t * 3 / 4) else 0
        }
        val fill = legacyFill(grad, n)!!
        assertFalse(fill.peel)
        assertEquals(24f / n, fill.extent, 0.01f)
        // Stretched: outside the square takes the colour just inside its edge (a pixel in, past anti-aliasing), so the
        // top stays purple and the bottom orange.
        val out = grad.copyOf().also { extendEdge(it, n) }
        assertEquals(grad[4 * n + 4], out[0]) // the corner pixel itself: one step on a diagonal rounds back onto it
        assertEquals(grad[5 * n + 16], out[16])
        assertEquals(grad[26 * n + 16], out[31 * n + 16])
        assertEquals(grad[16 * n + 16], out[16 * n + 16])
        // A noisy square (a photo to its edge) is busy all round: a white plate.
        var seed = 7
        val noisy = IntArray(n * n) { i -> seed = seed * 1103515245 + 12345; if (inSquare(i)) seed or (0xFF shl 24) else 0 }
        assertNull(legacyFill(noisy, n))
    }

    @Test
    fun legacyIconOnSystemPlate() {
        val n = 32
        val green = 0xFF2EBD59.toInt()
        // EMUI's white plate (2..29, shaded at the bottom) with a green square app icon (9..22) on it, its edge blended
        // into the plate, and a white glyph (14..17) in it.
        fun inBox(i: Int, a: Int, b: Int) = i % n in a..b && i / n in a..b
        val emui = IntArray(n * n) { i ->
            when { inBox(i, 14, 17) -> -1; inBox(i, 10, 21) -> green; inBox(i, 9, 22) -> 0xFF97DEAC.toInt()
                inBox(i, 2, 29) -> if (i / n >= 28) 0xFFE6E6E6.toInt() else -1; else -> 0 }
        }
        val (color, extent, peel) = legacyFill(emui, n)!!
        assertEquals(green, color)
        assertEquals(14f / n, extent, 0.01f)
        assertTrue(peel)
        // The plate goes; the glyph inside the icon stays.
        val peeled = emui.copyOf().also { peelPlate(it, n) }
        assertEquals(0, peeled[4 * n + 4])
        assertEquals(-1, peeled[15 * n + 15])
        assertEquals(green, peeled[10 * n + 10])
        // Grown by one, the blended rim (9 and 22) goes too, and the icon inside it stays.
        val grown = emui.copyOf().also { peelPlate(it, n, grow = 1) }
        assertEquals(0, grown[9 * n + 15])
        assertEquals(green, grown[10 * n + 15])
        // A thin green cross on the plate is a mark, not an icon: the shape fills white, nothing peeled.
        val cross = IntArray(n * n) { i -> when { (i % n in 15..16 || i / n in 15..16) && inBox(i, 8, 23) -> green; inBox(i, 2, 29) -> -1; else -> 0 } }
        val (c2, _, p2) = legacyFill(cross, n)!!
        assertEquals(-1, c2)
        assertFalse(p2)
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
        assertNull(intentShortcut("#Intent;package=com.g;S.shortcut_id=bad%zz;end")) // dropped, not the whole import
    }

    @Test
    fun importLayout() {
        fun row(id: Long, container: Long, screen: Long, x: Double, y: Double, type: Int = 0, intent: String = "#Intent;component=p$id/.A;end",
                title: String = "", sx: Double = 1.0, sy: Double = 1.0, rank: Int = 0, provider: String = "") =
            ImportRow(id, title, intent, container, screen, x, y, sx, sy, type, -1, rank, provider)
        val rows = listOf(
            row(1, -100, 10, 0.0, 0.0),                                   // app on the first screen
            row(2, -100, 20, 1.0, 2.0, type = 2, intent = "", title = "Tools"), // folder on the second screen
            row(3, 2, 0, 0.0, 0.0, rank = 1),
            row(4, 2, 0, 0.0, 0.0, rank = 0),
            row(5, -101, 6, 6.0, 0.0),                                    // dock position 6 with 5 per page → dock page 2, x 1
            row(6, -100, 10, 2.0, 0.0, type = 4, intent = "", sx = 2.0, sy = 2.0, provider = "com.w/.Clock"), // widget: a spot for a new one
            row(7, -100, 20, 0.0, 0.0, type = 4, intent = "", provider = "com.teslacoilsw.launcher/#custom-widget-0"), // Nova's own: skipped
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
        assertEquals(Item(0, 2, 0, 2, 2, pkg = "com.w", cls = "com.w.Clock", action = WIDGET_SLOT), out.items[3])
        assertTrue(out.items[3].isWidgetSlot && out.items[3].isBlock && !out.items[3].isAction && !out.items[3].isApp)
        assertEquals(1, out.widgets)
        // A widget's package may have no app Vulcan can see: its spot must survive the uninstalled-app sweep.
        assertEquals(listOf(out.items[3]), removeApps(listOf(out.items[3])) { true })

        val half = importToItems(listOf(row(1, -100, 10, 0.5, 1.5)), mapOf(10L to 0), 5)
        assertTrue(half.subgrid)
        assertEquals(Item(0, 1, 3, 2, 2, pkg = "p1", cls = "p1.A"), half.items[0])

        // Rows from an untrusted file: a negative screen or dock position stays off the dock and home pages it doesn't name.
        val odd = importToItems(listOf(row(1, -100, -3, 0.0, 0.0), row(2, -101, -6, -1.0, 0.0)), emptyMap(), 5).items
        assertEquals(0, odd[0].page)
        assertEquals(Item(-1, 0, 0, pkg = "p2", cls = "p2.A"), odd[1])
    }

    @Test
    fun importPreferences() {
        val xml = """<?xml version='1.0'?><map><int name="desktop_grid_cols" value="6" /><string name="desktop_grid_rows">7</string>
            <int name="dock_grid_cols" value="4" /><boolean name="x" value="true" /><int name="desktop_page_count" value="2" /></map>"""
        assertEquals(mapOf("cols" to "6", "rows" to "7", "dockCols" to "4", "pages" to "2"), importedPrefs(xml))
        // Values outside what a setting allows are brought inside it.
        assertEquals(mapOf("dockCols" to "3", "dockPages" to "3", "drawerCols" to "8"),
            importedPrefs("""<int name="dock_grid_cols" value="0" /><int name="dock_pages_count" value="5" /><int name="drawer_app_grid_cols" value="11" />"""))
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
