package dev.vulcanlauncher

/** The most home pages there can be: the Pages setting's top value. */
const val MAX_PAGES = 9

/**
 * Home's layout: its items, page count and default page, stored together in one write ([Store.save]). Every edit is a
 * function from one of these to the next, and [MainActivity.editLayout] is the only place home stores one, so the three
 * can't disagree.
 */
data class PageLayout(val items: List<Item>, val pages: Int, val defaultPage: Int) {
    /** A default page left out of range (an older build, a hand edit, Pages lowered in Settings) is the last page. */
    private val default get() = defaultPage.coerceIn(0, maxOf(0, pages - 1))

    fun addPage() = copy(pages = (pages + 1).coerceAtMost(MAX_PAGES), defaultPage = default)

    /** Removes page [i] and its items; the default page stays on its contents, or takes the page that moves into its place. */
    fun removePageAt(i: Int): Pair<PageLayout, List<Item>> {
        val (kept, gone) = removePage(items, i)
        return PageLayout(kept, pages - 1, shifted(i, pages - 1)) to gone
    }

    /** The default page follows its contents, like every other page. */
    fun movePageTo(from: Int, to: Int) =
        PageLayout(movePage(items, from, to), pages, (0 until pages).toMutableList().apply { add(to, removeAt(from)) }.indexOf(default))

    fun withDefault(i: Int) = copy(defaultPage = i.coerceIn(0, pages - 1))

    /** Pages set in Settings: items on pages that go are moved by [fitItems] when home is built again. */
    fun withPages(n: Int) = PageLayout(items, n.coerceIn(1, MAX_PAGES), defaultPage.coerceIn(0, n.coerceIn(1, MAX_PAGES) - 1))

    /** [settlePages] after a home drag, with the page it removed (-1 for none). */
    fun settleDrag(from: Int?): Pair<PageLayout, Int> {
        val (kept, n, gone) = settlePages(items, pages, from)
        return PageLayout(kept, n, if (gone < 0) default.coerceAtMost(n - 1) else shifted(gone, n)) to gone
    }

    private fun shifted(removed: Int, n: Int) = (if (default > removed) default - 1 else default).coerceIn(0, n - 1)
}

/**
 * An imported layout ([items]) and the page count its backup gave, if any: never fewer pages than its items need, and
 * never this home's old empty pages.
 */
fun importedLayout(items: List<Item>, pages: Int?, defaultPage: Int): PageLayout {
    val needed = (items.filter { !isDock(it.page) }.maxOfOrNull { it.page } ?: 0) + 1
    val n = maxOf(needed, pages ?: 1).coerceAtMost(MAX_PAGES)
    return PageLayout(items, n, defaultPage.coerceIn(0, n - 1))
}

/** [items] held to what the stored layout allows, as [decodeLayout] reads it: well-formed items, a page count in range, a default page on one of them. */
fun PageLayout.checked(): PageLayout {
    val n = pages.coerceIn(1, MAX_PAGES)
    return PageLayout(items.mapNotNull(::wellFormed), n, defaultPage.coerceIn(0, n - 1))
}

/** An edit of the stored layout made outside home (PinActivity, a widget restore): load, [edit], check, save in one write. */
fun Store.editLayout(sub: Int, edit: (PageLayout) -> PageLayout) = save(edit(load(sub)).checked(), sub)
