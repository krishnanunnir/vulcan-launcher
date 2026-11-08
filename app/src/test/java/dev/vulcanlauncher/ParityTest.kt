package dev.vulcanlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParityTest {
    @Test
    fun twoFingerGestures() {
        val m = 40f
        // fingers 200px apart horizontally, centred at (300, 500)
        assertEquals("pinchIn", twoFingerGesture(200f, 500f, 400f, 500f, 260f, 500f, 340f, 500f, m))
        assertEquals("pinchOut", twoFingerGesture(260f, 500f, 340f, 500f, 150f, 500f, 450f, 500f, m))
        assertEquals("swipeUp2", twoFingerGesture(200f, 500f, 400f, 500f, 200f, 300f, 400f, 300f, m))
        assertEquals("swipeDown2", twoFingerGesture(200f, 500f, 400f, 500f, 200f, 700f, 400f, 700f, m))
        // right finger moves down, left finger up: clockwise on screen
        assertEquals("rotateCw", twoFingerGesture(200f, 500f, 400f, 500f, 220f, 420f, 380f, 580f, m))
        assertEquals("rotateCcw", twoFingerGesture(200f, 500f, 400f, 500f, 220f, 580f, 380f, 420f, m))
        assertNull(twoFingerGesture(200f, 500f, 400f, 500f, 205f, 505f, 405f, 505f, m))
    }

    @Test
    fun actions() {
        assertEquals(ACTIONS.size, ACTION_NAMES.size)
        assertEquals(GESTURE_KEYS.size, GESTURE_NAMES.size)
        assertEquals(GESTURE_KEYS.size, GESTURE_DEFAULTS.size)
        assertTrue(GESTURE_DEFAULTS.all { it in ACTIONS })
        // A fake string lookup that shows which resource and arguments were used.
        val t = { id: Int, args: Array<Any> -> "$id:" + args.joinToString() }
        assertEquals("${R.string.action_jump_to_page}:3", actionName("page:2", t))
        assertEquals("${R.string.action_open_app}:Clock", actionName("app:a/b/0", t) { "Clock" })
        assertEquals("${R.string.action_lock}:", actionName("lock", t))
        assertEquals("${R.string.action_none}:", actionName("bogus", t))
        assertEquals(13, GESTURE_KEYS.size) // 13 home gestures
        assertEquals("${R.string.action_open_folder}:", actionName("folder", t))
    }

    @Test
    fun folderSwipe() {
        // #66: a folder's own swipe action beats the global Swipe to open; #65: "folder" turns it on per folder.
        assertFalse(folderSwipeOpens("page:2", global = true))
        assertFalse(folderSwipeOpens("none", global = true))
        assertTrue(folderSwipeOpens("folder", global = false))
        assertTrue(folderSwipeOpens("", global = true))
        assertFalse(folderSwipeOpens("", global = false))
    }

    @Test
    fun night() {
        assertTrue(isNight("on", 12, 20, 7, false))
        assertFalse(isNight("off", 23, 20, 7, true))
        assertTrue(isNight("system", 12, 20, 7, true))
        assertTrue(isNight("schedule", 23, 20, 7, false))
        assertTrue(isNight("schedule", 3, 20, 7, false))
        assertFalse(isNight("schedule", 7, 20, 7, false))
        assertTrue(isNight("schedule", 10, 9, 17, false))
        assertFalse(isNight("schedule", 18, 9, 17, false))
    }

    @Test
    fun sorting() {
        val keys = listOf(SortKey("b", 1, 5), SortKey("A", 3, 0), SortKey("c", 2, 9))
        assertEquals(listOf("A", "b", "c"), keys.sortedWith(drawerComparator("az")).map { it.label })
        assertEquals(listOf("c", "b", "A"), keys.sortedWith(drawerComparator("za")).map { it.label })
        assertEquals(listOf("A", "c", "b"), keys.sortedWith(drawerComparator("installed")).map { it.label })
        assertEquals(listOf("c", "b", "A"), keys.sortedWith(drawerComparator("used")).map { it.label })
    }

    @Test
    fun localeSorting() {
        val de = listOf("Zoom", "Äpfel", "apps", "Öffi", "Bank")
        assertEquals(listOf("Äpfel", "apps", "Bank", "Öffi", "Zoom"), de.sortedWith(labelOrder(java.util.Locale.GERMAN)))
        val ru = listOf("Yahoo", "Электричка", "Catima", "Ёлка", "Банк", "Organic", "Контакт")
        assertEquals(listOf("Банк", "Ёлка", "Контакт", "Электричка", "Catima", "Organic", "Yahoo"),
            ru.sortedWith(labelOrder(java.util.Locale("ru"))))
        assertEquals(listOf("A", "O", "Е", "#"), listOf("Äpfel", "Öffi", "Ёлка", "1Password").map(::indexLetter))
    }

    @Test
    fun effects() {
        assertEquals(13, EFFECTS.size)
        assertEquals(EFFECTS.size, EFFECT_NAMES.size)
        for (e in EFFECTS) {
            assertEquals(e, PageTransform(), pageTransform(e, 0f))   // the page on screen is untouched
            assertEquals(e, PageTransform(), pageTransform(e, 1f))   // fully off screen too
            val t = pageTransform(e, 0.5f)
            assertTrue(e, t.alpha in 0f..1f && t.scaleX in 0f..1f && t.clipLeft in 0f..1f)
        }
        assertEquals(-0.5f, pageTransform("cardStack", 0.5f).tx) // incoming card stays in place
        assertEquals(0.5f, pageTransform("wipe", 0.5f).clipLeft)
    }

    @Test
    fun folderPreviews() {
        for (s in PREVIEWS) for (n in 1..8) {
            val slots = folderPreview(s, n, false)
            assertTrue("$s $n", slots.size in 1..minOf(n, 6))
            slots.forEach { assertTrue("$s $n", it.cx - it.size / 2 >= -0.01f && it.cx + it.size / 2 <= 1.01f) }
        }
        assertEquals(4, folderPreview("grid", 9, false).size)
        assertEquals(0.56f, folderPreview("grid", 3, true)[0].size)
        assertTrue(folderPreview("grid", 0, false).isEmpty())
    }

    @Test
    fun pages() {
        val a = Item(0, 0, 0, pkg = "a")
        val b = Item(1, 0, 0, pkg = "b")
        val c = Item(2, 0, 0, pkg = "c")
        val d = Item(DOCK, 0, 0, pkg = "d")
        val (kept, gone) = removePage(listOf(a, b, c, d), 1)
        assertEquals(listOf(b), gone)
        assertEquals(listOf(a, c.copy(page = 1), d), kept)
        assertEquals(listOf(a.copy(page = 2), b.copy(page = 0), c.copy(page = 1), d), movePage(listOf(a, b, c, d), 0, 2))
        assertEquals(listOf(a.copy(page = 1), b.copy(page = 2), c.copy(page = 0), d), movePage(listOf(a, b, c, d), 2, 0))
    }

    @Test
    fun dragPages() {
        val a = Item(0, 0, 0, pkg = "a")
        val b = Item(1, 0, 0, pkg = "b")
        val d = Item(DOCK, 0, 0, pkg = "d")
        // Nothing landed on the extra page (2): it goes; page 1 still has b.
        assertEquals(Triple(listOf(a, b, d), 2, -1), settlePages(listOf(a, b, d), 2, 1))
        // b moved from page 1 to the extra page: it's kept, and the emptied page 1 goes.
        assertEquals(Triple(listOf(a, b, d), 2, 1), settlePages(listOf(a, b.copy(page = 2), d), 2, 1))
        // b moved from page 1 to page 0: page 1 goes; a page that was empty before stays.
        assertEquals(Triple(listOf(a, b.copy(page = 0)), 2, 1), settlePages(listOf(a, b.copy(page = 0)), 3, 1))
        // The last page is never removed.
        assertEquals(Triple(listOf(d), 1, -1), settlePages(listOf(d), 1, 0))
    }

    @Test
    fun subgridScaling() {
        val icon = Item(0, 2, 3, pkg = "a")
        val widget = Item(0, 1, 0, 3, 2, widgetId = 4)
        val dock = Item(DOCK, 2, 0, pkg = "d")
        val up = scaleItems(listOf(icon, widget, dock), 2)
        assertEquals(listOf(Item(0, 4, 6, 2, 2, pkg = "a"), Item(0, 2, 0, 6, 4, widgetId = 4), dock), up)
        assertEquals(listOf(icon, widget, dock), scaleItems(up, -2))
        assertEquals(Item(0, 1, 1, 1, 1, pkg = "x"), scaleItems(listOf(Item(0, 3, 3, 1, 1, pkg = "x")), -2)[0])
    }

    @Test
    fun dockPagesAndFreeSpots() {
        val onSecondDockPage = Item(-2, 1, 0, pkg = "a")
        assertEquals(listOf(onSecondDockPage), fitItems(listOf(onSecondDockPage), 1, 4, 4, dockCols = 4, dockPages = 2))
        assertEquals(Item(DOCK, 0, 0, pkg = "a"), fitItems(listOf(onSecondDockPage), 1, 4, 4, dockCols = 4, dockPages = 1)[0])
        val busy = listOf(Item(0, 0, 0), Item(0, 1, 0))
        assertEquals(Item(0, 0, 1, 1, 1), firstFreeSpot(busy, 1, 2, 2, 1, 1))
        assertEquals(Item(1, 0, 0, 2, 2), firstFreeSpot(busy, 2, 2, 2, 2, 2))
        assertNull(firstFreeSpot(busy, 1, 2, 1, 1, 1))
    }

    @Test
    fun itemJsonRoundTrip() {
        val folder = Item(1, 2, 3, folder = true, label = "Work", swipe = "page:1",
            children = listOf(Item(0, 0, 0, pkg = "p", cls = "c", user = 10), Item(0, 0, 0, pkg = "q", shortcutId = "s1")))
        val list = listOf(folder, Item(DOCK, 0, 0, action = "drawer", icon = "pack:x:y"), Item(0, 0, 0, 4, 2, widgetId = 9))
        assertEquals(list, itemsFromJson(itemsToJson(list)))
        // Stage 1 layouts (no new fields) still load.
        assertEquals(Item(0, 1, 2, pkg = "a", cls = "b"),
            itemsFromJson("""[{"page":0,"x":1,"y":2,"w":1,"h":1,"pkg":"a","cls":"b","widgetId":-1}]""")[0])
        assertTrue(folder.folder && !folder.isApp)
        assertTrue(list[1].isAction && !list[1].isApp)
        assertTrue(Item(0, 0, 0, pkg = "p", cls = "c", action = "activity").let { !it.isAction && !it.isApp })
    }

    @Test
    fun folderPopupOverItsIcon() {
        assertEquals(400, spanAround(500, 200, 16, 1064))   // centred on the icon
        assertEquals(16, spanAround(50, 200, 16, 1064))     // pushed in from the left edge
        assertEquals(864, spanAround(1050, 200, 16, 1064))  // and from the right
        assertEquals(16, spanAround(500, 2000, 16, 1064))   // too big: starts at the edge
    }

    @Test
    fun folderGridFitsTheScreen() {
        assertEquals(2 to 100, folderGrid(4, 100, 60, 1000))  // room to spare: 2×2 at full width
        assertEquals(4 to 100, folderGrid(12, 100, 60, 1000))
        assertEquals(4 to 75, folderGrid(12, 100, 60, 300))   // narrow screen: cells shrink, still four columns
        assertEquals(3 to 66, folderGrid(12, 100, 60, 200))   // narrower: a column goes rather than squash the icons
        assertEquals(1 to 50, folderGrid(3, 100, 60, 50))     // never fewer than one
        assertEquals(6 to 100, folderGrid(4, 100, 60, 1000, want = 6)) // set columns win over the content-sized grid
        assertEquals(3 to 66, folderGrid(4, 100, 60, 200, want = 6))   // but still fit the screen
    }

    @Test
    fun drawerTabs() {
        val all = listOf("a", "b", "c", "w")
        val groups = listOf(Group("Games", "tab", listOf("b")), Group("Tools", "folder", listOf("c")))
        assertEquals(listOf("a", "b", "c"), tabApps(all, groups, "", emptySet(), false, true, setOf("w"), true))
        assertEquals(listOf("a"), tabApps(all, groups, "", emptySet(), false, false, setOf("w"), true))
        assertEquals(listOf("a", "c", "w"), tabApps(all, groups, "", setOf("b"), false, true, setOf("w"), false))
        assertEquals(listOf("a", "b", "c", "w"), tabApps(all, groups, "", setOf("b"), true, true, setOf("w"), false))
        assertEquals(listOf("w"), tabApps(all, groups, "work", emptySet(), false, true, setOf("w"), true))
        assertEquals(listOf("b"), tabApps(all, groups, "Games", emptySet(), false, true, emptySet(), false))
        assertEquals(emptyList<String>(), tabApps(all, groups, "Tools", emptySet(), false, true, emptySet(), false))
        // Private Space apps only ever show in their own tab, even with no work tab.
        assertEquals(listOf("a", "b", "c"), tabApps(all, groups, "", emptySet(), false, true, emptySet(), false, setOf("w")))
        assertEquals(listOf("w"), tabApps(all, groups, "private", emptySet(), false, true, emptySet(), false, setOf("w")))
        assertEquals(groups, groupsFromJson(groupsToJson(groups)))
    }

    @Test
    fun settingsCatalogue() {
        val keys = LOOKS.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(15, SHAPES.size) // 15 icon shapes
        assertEquals(5, PREVIEWS.size)
        assertEquals(COLOR_VALUES.size, COLOR_NAMES.size)
    }
}
