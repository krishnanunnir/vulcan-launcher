package dev.vulcanlauncher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Randomly corrupted variants of a valid backup, fed to each decoder: none may throw, and nothing they let through may
 * reach home off the grid, overlapping or with a size of 0. Seeded, so a failure names the case that broke it.
 */
class CorruptionTest {
    private val items = listOf(
        Item(0, 0, 0, pkg = "a", cls = "A"), Item(0, 1, 0, 2, 2, widgetId = 4, pkg = "w", cls = "W"),
        Item(1, 3, 2, folder = true, label = "F", children = listOf(Item(0, 0, 0, pkg = "b", cls = "B"), Item(0, 0, 0, pkg = "c", cls = "C"))),
        Item(DOCK, 0, 0, pkg = "d", cls = "D"), Item(DOCK, 1, 0, action = "lock", label = "L"), Item(0, 3, 3, 2, 1, action = "clock"),
    )
    private val groups = """[{"name":"Social","type":"tab","apps":["a/A/0"],"color":"#112233"},{"name":"Games","type":"folder","apps":["b/B/0","c/C/0"]}]"""
    private val format2 = readableBackup(mapOf("cols" to "6", "subgrid" to "off"), items, 1,
        mapOf("groups" to groups, "hidden" to """["x/X/0"]""", WELCOME to "false", WORK_AT to "1"), emptyList()) { "s$it" }.toString()
    private val format1 = JSONObject().put("format", "vulcan-backup-1")
        .put("layout", JSONObject().put("items", itemsToJson(items)).put("version", "2").put("sub", "1"))
        .put("looks", JSONObject().put("cols", "6")).put("data", JSONObject().put("groups", groups)).toString()

    private val oddValues: List<Any> = listOf(-1, 0, 1, 2, 7, -2147483648, 2147483647, 1e300, -1e300, 3.5, "NaN", "x", "", "-1",
        "2147483648", true, false, JSONObject.NULL, JSONArray(), JSONObject(), JSONArray("[1,\"a\",null]"), JSONObject("""{"page":0}"""))

    /** [json] with one to four values somewhere in its tree replaced, removed or duplicated, or (one time in five) its text mangled. */
    private fun corrupt(json: String, r: Random): String {
        if (r.nextInt(5) == 0) {
            val s = StringBuilder(json)
            repeat(r.nextInt(1, 6)) {
                val i = r.nextInt(s.length)
                when (r.nextInt(3)) { 0 -> s.deleteCharAt(i); 1 -> s.insert(i, "{}[],:\"-09eE".random(r)); else -> s.setCharAt(i, "{}[],:\"-09eE".random(r)) }
            }
            return s.toString()
        }
        val root = org.json.JSONTokener(json).nextValue()
        repeat(r.nextInt(1, 5)) { mutate(root, r) }
        return root.toString()
    }

    private fun mutate(node: Any, r: Random) {
        // Walk down to a random container, then change one of its entries.
        var at = node
        while (true) {
            val kids = when (at) { is JSONObject -> at.keys().asSequence().map(at::get).toList(); is JSONArray -> (0 until at.length()).map(at::get); else -> emptyList() }
                .filter { it is JSONObject || it is JSONArray }
            if (kids.isEmpty() || r.nextInt(3) == 0) break
            at = kids.random(r)
        }
        val v = oddValues.random(r).let { if (it is JSONArray || it is JSONObject) org.json.JSONTokener(it.toString()).nextValue() else it }
        when (at) {
            is JSONObject -> {
                val keys = at.keys().asSequence().toList()
                when {
                    keys.isEmpty() || r.nextInt(6) == 0 -> at.put(listOf("page", "x", "w", "folder", "children", "version", "sub", "items").random(r), v)
                    r.nextInt(5) == 0 -> at.remove(keys.random(r))
                    else -> at.put(keys.random(r), v)
                }
            }
            is JSONArray -> when {
                at.length() == 0 || r.nextInt(5) == 0 -> at.put(v)
                r.nextInt(4) == 0 -> at.put(at.get(r.nextInt(at.length()))) // a repeat, as a hand edit copies an entry
                r.nextInt(4) == 0 -> at.remove(r.nextInt(at.length()))
                else -> at.put(r.nextInt(at.length()), v)
            }
        }
    }

    /** What a decoder hands home must be wellFormed; once fitted to a roomy grid, all on it, apart, and none of size 0. */
    private fun assertPlaceable(items: List<Item>, case: String) {
        fun ok(i: Item): Boolean = i.x in 0..MAX_CELL && i.y in 0..MAX_CELL && i.w in 1..MAX_CELL && i.h in 1..MAX_CELL &&
            i.page in -MAX_CELL..MAX_CELL && i.children.all(::ok) && !(i.folder && i.children.isEmpty())
        items.forEach { assertTrue("$case: $it", ok(it)) }
        assertFits(items, case)
    }

    private fun assertFits(items: List<Item>, case: String) {
        // Room for every item even at full size (oversized ones shrink to a page): a page each, a dock cell each.
        val pages = items.size + 1
        val home = fitItems(items, pages, 12, 12, items.size + 1, 1, overlapOk = false)
        for ((n, i) in home.withIndex()) {
            assertTrue("$case: off the grid $i", if (isDock(i.page)) i.page == DOCK && i.y == 0 && i.x + i.w <= items.size + 1
                else i.page in 0 until pages && i.x + i.w <= 12 && i.y + i.h <= 12)
            for (o in home.drop(n + 1)) assertTrue("$case: $i overlaps $o", o.page != i.page || !overlaps(i.x, i.y, i.w, i.h, o.x, o.y, o.w, o.h))
        }
    }

    @Test fun aFileThatIsNotJsonGivesNoParserTextAsItsReason() {
        for (text in listOf(format2.take(format2.length / 2), "\u0000\ufffd\u0001", "[1,2,3]", "")) {
            val d = decodeBackup(text)
            assertTrue(text.take(20), d.value == null && d.dropped.isEmpty())
        }
    }

    @Test fun backupFilesOfEitherFormat() {
        val r = Random(1)
        var restored = 0
        repeat(3000) { n ->
            val text = corrupt(if (n % 2 == 0) format2 else format1, r)
            val case = "case $n: $text"
            val d = decodeBackup(text) { pkg, _ -> "$pkg.Main" }
            val files = d.value?.first ?: return@repeat
            restored++
            // The layout it would store reads back as stored, and what home places from it is on the grid.
            files["layout"]?.let { l ->
                val stored = decodeLayout(l["items"], l["version"], l["sub"])
                assertTrue(case, stored.value != null && stored.dropped.isEmpty() && stored.repaired.isEmpty())
                assertPlaceable(stored.value!!.items, case)
            }
            // Data goes in as the types its readers take.
            files["data"]?.forEach { (k, v) -> val t = dataValue(k, v); assertTrue(case, t == null || t is Boolean || t is Int || t is String) }
            files["data"]?.get("groups")?.let { assertGroups(groupsFromJson(it), case) }
        }
        // Most corruptions still restore something: the test exercises the decoders, not only their refusals.
        assertTrue("$restored restored", restored > 1000)
    }

    @Test fun storedLayoutPreferences() {
        val r = Random(2)
        val raw = itemsToJson(items)
        repeat(3000) { n ->
            val text = corrupt(raw, r)
            val version = listOf<Any?>("2", "1", null, "3", "x", 2, true).random(r)
            val sub = listOf<Any?>("1", "2", null, "0", "7", 2, false).random(r)
            val d = decodeLayout(text, version, sub)
            val l = d.value ?: return@repeat
            assertTrue(l.sub == null || l.sub == 1 || l.sub == 2)
            assertPlaceable(l.items, "case $n: $text")
            // Scaled by load into half cells (an off-grid cell doubles to 2 × MAX_CELL, no overflow), it still fits.
            assertFits(layoutIn(l.items, 1, 2), "case $n scaled: $text")
        }
    }

    @Test fun importedLauncherRows() {
        val r = Random(3)
        val odd = listOf(0.0, 1.0, 2.5, -1.0, -3.0, 1e12, -1e12, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.49)
        repeat(2000) { n ->
            val rows = List(r.nextInt(1, 12)) { id ->
                ImportRow(id.toLong(), "t", listOf("#Intent;component=p/.A;end", "#Intent;package=p;S.shortcut_id=%zz;end", "", "component=/").random(r),
                    listOf(-100L, -101L, r.nextLong(-3, 12)).random(r), r.nextLong(-5, 5) * listOf(1L, 1L shl 40).random(r),
                    odd.random(r), odd.random(r), odd.random(r), odd.random(r), r.nextInt(-1, 7), r.nextLong(-2, 12), r.nextInt(), "p/.W")
            }
            val l = importToItems(rows, mapOf(0L to 0, 1L to 1), r.nextInt(3, 10))
            assertPlaceable(l.items, "case $n: $rows")
        }
    }

    private fun assertGroups(g: List<Group>, case: String) {
        assertEquals(case, g.size, g.map { it.name }.toSet().size)
        assertTrue(case, g.none { it.name.isBlank() || it.name == "work" || it.name == "private" })
    }

    @Test fun drawerGroups() {
        val r = Random(4)
        repeat(3000) { assertGroups(groupsFromJson(corrupt(groups, r)), "case $it") }
    }

    @Test fun dataPreferences() {
        val r = Random(5)
        val keys = listOf(WELCOME, WORK_AT, ALL_HIDDEN, "groups", "hidden", "custom")
        repeat(2000) {
            val all = keys.associateWith { listOf<Any>("true", "false", "on", true, false, 3, "3", "x", 2.5, 7L).random(r) }
            val fixed = all + dataTypeFixes(all)
            for ((k, v) in fixed) when (k) {
                WELCOME, ALL_HIDDEN -> assertTrue("$k=$v", v is Boolean)
                WORK_AT -> assertTrue("$k=$v", v == null || v is Int)
                else -> assertTrue("$k=$v", v == null || v is String)
            }
        }
    }

    @Test fun iconPackAppfilter() {
        val r = Random(6)
        val xml = """<resources><item component="ComponentInfo{a/a.A}" drawable="x"/><iconback img1="b"/><iconmask img1="m"/><scale factor="0.8"/></resources>"""
        repeat(2000) {
            val s = StringBuilder(xml)
            repeat(r.nextInt(1, 6)) { val i = r.nextInt(s.length); s.setCharAt(i, "<>/=\"{}x0.-".random(r)) }
            // readPackXml stops at a malformed tag; what was read before it must still be usable.
            val f = Appfilter()
            runCatching { readAppfilter(org.kxml2.io.KXmlParser().apply { setInput(java.io.StringReader(s.toString())) }, f) }
            assertTrue("$s", f.scale > 0f && f.scale.isFinite())
        }
    }
}
