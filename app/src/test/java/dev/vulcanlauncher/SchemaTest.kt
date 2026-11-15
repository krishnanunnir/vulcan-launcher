package dev.vulcanlauncher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * docs/schema/vulcan-backup-2.json is what the Designer and an AI assistant read: every setting, its default, its
 * values, where it sits, and the actions it takes. It is kept by hand, so this holds it to [LOOKS] and [SECTIONS].
 */
class SchemaTest {
    private val root = JSONObject(schemaFile().readText())
    private val settings = root.getJSONObject("properties").getJSONObject("settings").getJSONObject("properties")

    private fun schemaFile(): File = listOf("../docs/schema/vulcan-backup-2.json", "docs/schema/vulcan-backup-2.json")
        .map(::File).firstOrNull { it.exists() } ?: error("docs/schema/vulcan-backup-2.json not found")

    @Test
    fun everySettingIsListedWithItsDefaultValuesAndGroup() {
        assertEquals(LOOKS.map { it.key }.toSet(), settings.keys().asSequence().toSet())
        for (c in LOOKS) {
            val p = settings.getJSONObject(c.key)
            assertEquals("${c.key} default", c.default, p.getString("default"))
            assertEquals("${c.key} group", c.group, p.getString("description").substringBefore('.'))
            if (!c.action) assertEquals("${c.key} values", c.values.toSet(), values(p).toSet())
        }
    }

    /** A gesture, the home button and an icon swipe can open a page, an app or one of its shortcuts. */
    @Test
    fun everyActionSettingTakesPageAppAndShortcutCodes() {
        for (c in LOOKS.filter { it.action }) {
            val anyOf = settings.getJSONObject(c.key).getJSONArray("anyOf")
            val patterns = (0 until anyOf.length()).mapNotNull { anyOf.getJSONObject(it).optString("pattern").ifEmpty { null } }
            assertEquals(c.key, setOf("^page:[0-8]$", "^app:[^/]+/[^/]+/\\d+$", "^shortcut:[^/]+/.+/\\d+$"), patterns.toSet())
        }
    }

    /**
     * The Designer shows the Drawer tabs and Drawer folders pages inside App drawer, the page that links to them, so
     * those two groups have no tab of their own there.
     */
    @Test
    fun sectionsMatchTheAppsGroups() {
        val listed = mutableMapOf<String, MutableSet<String>>()
        val groups = root.getJSONArray("x-sections")
        for (i in 0 until groups.length()) {
            val group = groups.getJSONObject(i)
            val keys = listed.getOrPut(group.getString("group")) { mutableSetOf() }
            val sections = group.getJSONArray("sections")
            for (j in 0 until sections.length()) {
                val rows = sections.getJSONObject(j).getJSONArray("keys")
                for (k in 0 until rows.length()) assertTrue("${rows.getString(k)} listed twice", keys.add(rows.getString(k)))
            }
        }
        // A group with no SECTIONS entry shows its settings as one untitled panel, so LOOKS has every key.
        val keysOf = LOOKS.groupBy { it.group }.mapValues { (_, choices) -> choices.map { it.key }.toSet() }
        val absorbed = mapOf(DRAWER to listOf(TABS_GROUP, DRAWER_FOLDERS))
        val shown = keysOf.keys - absorbed.values.flatten().toSet()
        for (group in shown) {
            val keys = keysOf.getValue(group) + absorbed[group].orEmpty().flatMap { keysOf.getValue(it) }
            assertEquals(group, keys, listed[group])
        }
        assertEquals(shown, listed.keys)
    }

    private fun values(p: JSONObject): List<String> {
        val array = p.optJSONArray("enum") ?: p.getJSONArray("anyOf").getJSONObject(0).getJSONArray("enum")
        return (0 until array.length()).map(array::getString)
    }
}
