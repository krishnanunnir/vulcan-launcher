package dev.vulcanlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

/** Every page edit keeps the items, the page count and the default page in step (PageLayout). */
class PagesTest {
    private val a = Item(0, 0, 0, pkg = "a")
    private val b = Item(1, 0, 0, pkg = "b")
    private val c = Item(2, 0, 0, pkg = "c")
    private val d = Item(DOCK, 0, 0, pkg = "d")
    private val three = PageLayout(listOf(a, b, c, d), 3, 1)

    @Test
    fun addPage() {
        assertEquals(PageLayout(three.items, 4, 1), three.addPage())
        assertEquals(MAX_PAGES, PageLayout(emptyList(), MAX_PAGES, 0).addPage().pages)
    }

    @Test
    fun removePage() {
        // The page before the default: the default moves down with its contents.
        val (l, gone) = three.removePageAt(0)
        assertEquals(listOf(a), gone)
        assertEquals(PageLayout(listOf(b.copy(page = 0), c.copy(page = 1), d), 2, 0), l)
        // The default page itself: the page that moves into its place is the default.
        assertEquals(PageLayout(listOf(a, c.copy(page = 1), d), 2, 1), three.removePageAt(1).first)
        // A page after the default: it stays.
        assertEquals(1, three.removePageAt(2).first.defaultPage)
        // The default is the last page and goes: the new last page takes it.
        assertEquals(1, three.copy(defaultPage = 2).removePageAt(2).first.defaultPage)
        // Down to one page: everything on page 0, dock untouched.
        val one = PageLayout(listOf(a, b, d), 2, 1).removePageAt(0).first
        assertEquals(PageLayout(listOf(b.copy(page = 0), d), 1, 0), one)
    }

    @Test
    fun removePageKeepsTheDock() {
        val dock2 = Item(-2, 1, 0, pkg = "e")
        val (l, gone) = PageLayout(listOf(a, d, dock2), 2, 0).removePageAt(0)
        assertEquals(listOf(a), gone)
        assertEquals(listOf(d, dock2), l.items)
    }

    @Test
    fun movePage() {
        // Across the default: the default follows its contents (b).
        val l = three.movePageTo(0, 2)
        assertEquals(listOf(a.copy(page = 2), b.copy(page = 0), c.copy(page = 1), d), l.items)
        assertEquals(0, l.defaultPage)
        // The default page itself moves.
        assertEquals(2, three.movePageTo(1, 2).defaultPage)
        // Not across it: it stays.
        assertEquals(1, PageLayout(three.items, 4, 1).movePageTo(2, 3).defaultPage)
        // A stored default out of range (Pages lowered in Settings) is the last page, never -1.
        assertEquals(1, three.copy(defaultPage = 7).movePageTo(1, 2).defaultPage)
    }

    @Test
    fun setDefault() {
        assertEquals(2, three.withDefault(2).defaultPage)
        assertEquals(2, three.withDefault(5).defaultPage)
        assertEquals(three.items, three.withDefault(0).items)
    }

    @Test
    fun pagesFromSettings() {
        assertEquals(PageLayout(three.items, 1, 0), three.withPages(1))
        assertEquals(PageLayout(three.items, 5, 1), three.withPages(5))
    }

    @Test
    fun settleDrag() {
        // b dragged from page 1 to page 0: page 1 goes, and the default (2, after it) moves down.
        val (l, gone) = PageLayout(listOf(a, b.copy(page = 0), c, d), 3, 2).settleDrag(1)
        assertEquals(1, gone)
        assertEquals(PageLayout(listOf(a, b.copy(page = 0), c.copy(page = 1), d), 2, 1), l)
        // Something landed on the extra page after the last: the count grows, the default stays.
        val (grown, none) = PageLayout(listOf(a, b.copy(page = 3), c, d), 3, 1).settleDrag(0)
        assertEquals(-1, none)
        assertEquals(4, grown.pages)
        assertEquals(1, grown.defaultPage)
        // The emptied page was the last one, after the default: it goes, the default stays.
        val (last, _) = PageLayout(listOf(a, b, c.copy(page = 0), d), 3, 1).settleDrag(2)
        assertEquals(2, last.pages)
        assertEquals(1, last.defaultPage)
    }

    @Test
    fun dragNeverRemovesTheDefaultPage() {
        // c dragged off page 2, the default: the page stays, empty, and stays the default.
        val (l, gone) = PageLayout(listOf(a, b, c.copy(page = 0), d), 3, 2).settleDrag(2)
        assertEquals(-1, gone)
        assertEquals(PageLayout(listOf(a, b, c.copy(page = 0), d), 3, 2), l)
    }

    @Test
    fun imported() {
        // Enough pages for the items, the backup's count when larger, never more than the cap; the dock doesn't count.
        assertEquals(3, importedLayout(listOf(a, c, d), null, 0).pages)
        assertEquals(5, importedLayout(listOf(a, d), 5, 0).pages)
        assertEquals(MAX_PAGES, importedLayout(listOf(a), 40, 0).pages)
        assertEquals(1, importedLayout(listOf(d), null, 0).pages)
        // A default page beyond the imported pages is the last one.
        assertEquals(2, importedLayout(listOf(a, c), null, 6).defaultPage)
    }

    @Test
    fun checked() {
        // Held to the stored layout's rules, as the decoder reads it: the page count in range, the default on a page.
        assertEquals(PageLayout(listOf(a), MAX_PAGES, MAX_PAGES - 1), PageLayout(listOf(a), 40, 50).checked())
        assertEquals(PageLayout(listOf(a), 1, 0), PageLayout(listOf(a), 0, -3).checked())
        // An empty folder is dropped, an item off the grid is parked where fitItems moves it from.
        val folder = Item(0, 1, 0, folder = true)
        assertEquals(listOf(a), PageLayout(listOf(a, folder), 1, 0).checked().items)
        assertEquals(MAX_CELL, PageLayout(listOf(a.copy(x = -5)), 1, 0).checked().items.single().x)
        assertEquals(three, three.checked())
    }
}
