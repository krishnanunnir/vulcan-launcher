package dev.vulcanlauncher

import android.content.Context
import android.os.UserHandle
import android.os.UserManager
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.roundToInt

// A zip of JSON arrays, one `<Table>.txt` per database table: Octopi's OtpLauncher_<date>.zip. What it holds:
// - Screen: one per display size and orientation, with the grid (homeIconsX × homeIconsY), drawer columns, side paddings
//   and the default page.
// - HomeScreen: a page of a Screen (homeScreenIndex is its order).
// - HomeScreenIcon / HomeScreenFolder / HomeScreenWidget: on a page, placed in pixels (x, y, width, height). Loose icons
//   sit in a nameless "blank" folder; a real folder's icons carry its id and their order in it.
// - HomePanels with a DOCK panel and its PanelIcons: the dock, x in pixels across the panel.
// - profile "UserHandle{N}": the user id, not a serial.

/** [v] in cells of [size] pixels, on the nearest half cell; NaN (a missing number) stays NaN, for importToItems to place. */
private fun cells(v: Double, size: Double) = (v / size * 2).let { if (it.isNaN()) it else it.roundToInt() / 2.0 }

/** A widget's [v] pixels in whole half cells, rounded down: one that only grazes the next cell (Octopi lets it) doesn't take it. */
private fun span(v: Double, size: Double) = (v / size * 2 + 0.05).let { if (it.isNaN()) it else floor(it) / 2.0 }

/** The commonest value, or null for none. */
private fun <T> commonest(values: List<T>) = values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

private fun List<Double>.sizes() = filter { it.isFinite() && it > 0 }

/** What [jsonTablesToRows] read: [gesturesLeft] counts the gestures Vulcan has no action for. */
data class JsonTables(val rows: List<ImportRow>, val ranks: Map<Long, Int>, val settings: Map<String, String>, val gesturesLeft: Int)

/**
 * Rows, page order and Vulcan settings (gestures too) from the JSON tables [tables] (by name, without ".txt"). [serialOf] maps a user id
 * to this phone's user serial. Throws when there's no screen to read.
 */
fun jsonTablesToRows(tables: Map<String, String>, serialOf: (Int) -> Long): JsonTables {
    fun table(name: String): List<JSONObject> = tables[name]?.let(::JSONArray)?.let { a ->
        (0 until minOf(a.length(), MAX_IMPORT_ROWS)).mapNotNull(a::optJSONObject)
    }.orEmpty()
    val icons = table("HomeScreenIcon")
    val folders = table("HomeScreenFolder")
    val pagesOf = table("HomeScreen").groupBy { it.optLong("screenOwnerId") }
    fun iconsOn(s: JSONObject) = pagesOf[s.optLong("screenId")].orEmpty().map { it.optLong("homeScreenId") }.toSet()
        .let { pages -> icons.count { it.optLong("homeScreenOwnerId") in pages } }
    // The phone-shaped screen the user set up, the one with the most icons when there are several.
    val screen = table("Screen").filter { it.optBoolean("screenConfigCompleted", true) && it.optString("screenOrientation") != "LANDSCAPE" }
        .maxByOrNull(::iconsOn) ?: error("no screen")
    val screenId = screen.optLong("screenId")
    val ranks = pagesOf[screenId].orEmpty().sortedBy { it.optInt("homeScreenIndex") }.withIndex().associate { (i, p) -> p.optLong("homeScreenId") to i }
    val cols = screen.optInt("homeIconsX", 5).coerceAtLeast(1)
    val rowCount = screen.optInt("homeIconsY", 6).coerceAtLeast(1)
    val placed = (icons + folders).filter { it.optLong("homeScreenOwnerId") in ranks }
    // A cell is as big as an icon there; the grid starts where most icons line up.
    val cellW = commonest(placed.map { it.optDouble("width") }.sizes())
        ?: ((screen.optDouble("screenWidth", 1080.0) - screen.optDouble("leftPadding", 0.0) - screen.optDouble("rightPadding", 0.0)) / cols)
    val cellH = commonest(placed.map { it.optDouble("height") }.sizes()) ?: cellW
    fun origin(key: String, size: Double) =
        commonest(placed.map { it.optDouble(key) }.filter { it.isFinite() }.map { ((it % size + size) % size * 2).roundToInt() / 2.0 }) ?: 0.0
    val x0 = origin("x", cellW)
    val y0 = origin("y", cellH)
    fun user(o: JSONObject) = Regex("\\d+").find(o.optString("profile"))?.value?.toIntOrNull()?.let(serialOf)?.takeIf { it >= 0 } ?: 0L
    fun app(o: JSONObject, container: Long, screen: Long, x: Double, y: Double, rank: Int = 0): ImportRow {
        val component = o.optString("componentName")
        val shortcut = o.optString("shortcutID")
        val pkg = o.optString("packageName").ifEmpty { component.substringBefore('/') }
        val (type, intent) = when {
            shortcut.isNotEmpty() -> ITEM_DEEP_SHORTCUT to "#Intent;package=$pkg;S.shortcut_id=${java.net.URLEncoder.encode(shortcut, "UTF-8").replace("+", "%20")};end"
            '/' in component -> ITEM_APP to "#Intent;component=$component;end"
            else -> ITEM_OWN to ""
        }
        return ImportRow(0, o.optString("label"), intent, container, screen, x, y, 1.0, 1.0, type, user(o), rank)
    }
    val rows = ArrayList<ImportRow>()
    val real = folders.filter { it.optLong("homeScreenOwnerId") in ranks && it.optString("folderInternalName") != "blank" }
        .associateBy { it.optLong("homeScreenFolderId") }
    for (f in real.values) rows += ImportRow(f.optLong("homeScreenFolderId"), f.optString("label"), "", -100, f.optLong("homeScreenOwnerId"),
        cells(f.optDouble("x") - x0, cellW), cells(f.optDouble("y") - y0, cellH), 1.0, 1.0, ITEM_FOLDER, 0, 0)
    for (i in icons) {
        val page = i.optLong("homeScreenOwnerId").takeIf { it in ranks } ?: continue
        val folder = if (i.isNull("homeScreenFolderOwnerId")) null else i.optLong("homeScreenFolderOwnerId")
        rows += if (folder != null && folder in real) {
            app(i, folder, page, cells(i.optDouble("x") - x0, cellW), cells(i.optDouble("y") - y0, cellH), i.optInt("homeScreenFolderIndex"))
        } else {
            app(i, -100, page, cells(i.optDouble("x") - x0, cellW), cells(i.optDouble("y") - y0, cellH))
        }
    }
    for (w in table("HomeScreenWidget")) {
        val page = w.optLong("homeScreenOwnerId").takeIf { it in ranks } ?: continue
        // In a folder, a widget is in a stack, which Vulcan doesn't have.
        val stacked = !w.isNull("homeScreenFolderOwnerId") && w.optLong("homeScreenFolderOwnerId") in real
        // Octopi lets a widget run past the screen's edge; Vulcan's grid would drop it, so it is pulled back inside.
        val spanX = span(w.optDouble("width"), cellW).coerceIn(0.5, cols.toDouble())
        val spanY = span(w.optDouble("height"), cellH).coerceIn(0.5, rowCount.toDouble())
        rows += ImportRow(0, w.optString("label"), "", -100, page, cells(w.optDouble("x") - x0, cellW).coerceAtMost(cols - spanX),
            cells(w.optDouble("y") - y0, cellH).coerceAtMost(rowCount - spanY), spanX, spanY, if (stacked) ITEM_OWN else ITEM_WIDGET, user(w), 0,
            w.optString("provider"))
    }
    val dock = table("HomePanels").firstOrNull { it.optLong("screenOwnerId") == screenId && it.optString("panelType").startsWith("DOCK") }
    val dockCols = dock?.optInt("maxGridItemsX", 5)?.coerceAtLeast(1) ?: 5
    if (dock != null) {
        val slot = dock.optDouble("width", cellW * dockCols) / dockCols
        for (p in table("PanelIcons").filter { it.optLong("homePanelOwnerId") == dock.optLong("homePanelId") }) {
            val inFolder = !p.isNull("homePanelFolderOwnerId")
            val pos = p.optInt("panelIndex") * dockCols + (p.optDouble("x") / slot).roundToInt()
            rows += app(p, -101, 0, pos.toDouble(), 0.0).let { if (inFolder) it.copy(itemType = ITEM_OWN) else it }
        }
    }
    val settings = listOfNotNull(
        setting("cols", cols), setting("rows", screen.optInt("homeIconsY").takeIf { it > 0 }),
        setting("dockCols", dockCols), setting("drawerCols", screen.optInt("drawerIconsX").takeIf { it > 0 }),
        setting("pages", ranks.size.takeIf { it > 0 }), setting("defaultPage", screen.optInt("defaultHomeScreenIndex")),
        choice("drawerAlpha", percentStep(screen.optDouble("drawerBackgroundAlpha"))),
    ).toMap()
    val (gestures, left) = octopiGestures(screen)
    return JsonTables(rows, ranks, settings + gestures, left)
}

/** Octopi's gesture fields on a Screen (actionType…), with the Vulcan setting each is. Its empty ones are its defaults. */
private val OCTOPI_GESTURES = mapOf("SwipeUp" to "g_swipeUp", "SwipeDown" to "g_swipeDown", "DoubleSwipeUp" to "g_swipeUp2",
    "DoubleSwipeDown" to "g_swipeDown2", "DoubleTap" to "g_doubleTap", "TapAndSwipeUp" to "g_doubleTapUp", "TapAndSwipeDown" to "g_doubleTapDown",
    "PinchIn" to "g_pinchIn", "PinchOut" to "g_pinchOut", "HomeAction" to "homeButton")

private val OCTOPI_ACTIONS = mapOf("SCREEN_OFF" to "lock", "SCREEN_OFF_OLD" to "lock", "NOTIFICATIONS" to "notifications",
    "QUICK_SETTINGS" to "quicksettings", "RECENTS" to "recents", "ASSISTANT" to "assistant", "TOGGLE_DOCK" to "toggledock", "SETTINGS" to "settings")

// ponytail: only the action names seen in Octopi 1.955's code; an app's profile is taken as the personal one
/** Gesture settings and how many were left out, from Octopi's [screen]; an app gesture opens its componentName. */
fun octopiGestures(screen: JSONObject): Pair<Map<String, String>, Int> = gestureSettings(
    OCTOPI_GESTURES.keys.associateWith { k -> screen.optString("actionType$k").let { t -> screen.optString("componentName$k").takeIf { '/' in it }?.let { "app:$it" } ?: t } },
    OCTOPI_GESTURES,
) { v -> if (v.startsWith("app:")) appAction(componentKey(v.removePrefix("app:"))) else OCTOPI_ACTIONS[v] }

// ponytail: Octopi's drawer folders (DrawerFolder) hold no name in the backups seen; they stay behind until one shows where it is
/** Octopi's drawer from [tables]: the apps it hid and the nicknames it gave them. [serialOf] as for [jsonTablesToRows]. */
fun jsonTablesDrawer(tables: Map<String, String>, serialOf: (Int) -> Long): ImportedDrawer {
    val items = runCatching { JSONArray(tables["DrawerItem"] ?: "[]") }.getOrDefault(JSONArray()).let { a -> (0 until minOf(a.length(), MAX_IMPORT_ROWS)).mapNotNull(a::optJSONObject) }
    fun key(o: JSONObject): String? {
        val user = Regex("\\d+").find(o.optString("profile"))?.value?.toIntOrNull()?.let(serialOf)?.takeIf { it >= 0 } ?: 0L
        return componentKey(o.optString("componentName")) { user }
    }
    val apps = items.filter { it.optString("shortcutID").isEmpty() }
    return ImportedDrawer(hidden = apps.filter { it.optBoolean("hidden") }.mapNotNull(::key).toSet(),
        labels = apps.mapNotNull { o -> o.optString("nickname").trim().takeIf { it.isNotEmpty() }?.let { n -> key(o)?.let { it to n } } }.toMap())
}

object JsonTablesFormat : BackupFormat {
    override val name = R.string.format_octopi
    override val reads = listOf(".txt")
    override fun matches(archive: BackupArchive) = "Screen.txt" in archive && "HomeScreenIcon.txt" in archive

    override fun read(context: Context, archive: BackupArchive): ImportedBackup {
        val users = context.getSystemService(UserManager::class.java)
        val tables = archive.files.filterKeys { it.endsWith(".txt") }.map { (k, v) -> k.removeSuffix(".txt") to v.decodeToString() }.toMap()
        val serialOf = { id: Int -> runCatching { users.getSerialNumberForUser(UserHandle.getUserHandleForUid(id * 100_000)) }.getOrDefault(-1L) }
        val (rows, ranks, settings, left) = jsonTablesToRows(tables, serialOf)
        return ImportedBackup(importToItems(rows, ranks, settings["dockCols"]?.toInt() ?: 5), settings, name,
            drawer = jsonTablesDrawer(tables, serialOf), gesturesLeft = left)
    }
}
