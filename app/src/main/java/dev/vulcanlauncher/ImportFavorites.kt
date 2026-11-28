package dev.vulcanlauncher

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

// A zip holding an SQLite database with a Launcher3-style `favorites` table (and, in older ones, `workspaceScreens` for
// the page order). Nova's .novabackup (see docs/nova-reference.md) adds its preferences XML with the grid settings; Nova
// 8.9.4 names them nova.db and nova.xml, older ones backup.db and com.teslacoilsw.launcher_preferences.xml.

/** [read] run on database [db], opened read-only from a copy that is deleted after. */
private fun <T> withDatabase(context: Context, db: ByteArray, read: (SQLiteDatabase) -> T): T {
    val dir = File(context.cacheDir, "launcher-import").apply { deleteRecursively(); mkdirs() }
    val file = File(dir, "import.db").apply { writeBytes(db) }
    try {
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use(read)
    } finally {
        dir.deleteRecursively()
    }
}

/** Rows of the `favorites` table in database [db] and page order by screen _id; throws when it has no such table. */
fun readFavorites(context: Context, db: ByteArray): Pair<List<ImportRow>, Map<Long, Int>> =
        withDatabase(context, db) { sql ->
            val ranks = HashMap<Long, Int>()
            runCatching {
                sql.rawQuery("SELECT _id, screenRank FROM workspaceScreens ORDER BY screenRank", null).use { c ->
                    var i = 0
                    while (c.moveToNext()) ranks[c.getLong(0)] = i++
                }
            }
            val rows = ArrayList<ImportRow>()
            sql.rawQuery("SELECT * FROM favorites LIMIT $MAX_IMPORT_ROWS", null).use { c ->
                fun s(n: String) = c.getColumnIndex(n).let { if (it < 0 || c.isNull(it)) "" else c.getString(it) }
                fun d(n: String) = c.getColumnIndex(n).let { if (it < 0 || c.isNull(it)) 0.0 else c.getDouble(it) }
                fun l(n: String, def: Long = 0) = c.getColumnIndex(n).let { if (it < 0 || c.isNull(it)) def else c.getLong(it) }
                while (c.moveToNext()) rows += ImportRow(l("_id"), s("title"), s("intent"), l("container"), l("screen"),
                    d("cellX"), d("cellY"), d("spanX").takeIf { it > 0 } ?: 1.0, d("spanY").takeIf { it > 0 } ?: 1.0,
                    l("itemType").toInt(), l("profileId", -1), l("rank").toInt(), s("appWidgetProvider"))
            }
            rows to ranks
        }

/** A row of Nova's `drawer_groups`: [type] is its groupType, [color] its tabColor. */
class NovaGroup(val id: Long, val title: String, val type: String, val order: Int, val color: Int)

/** Nova's tabColor when the tab has none of its own. */
private const val NOVA_NO_COLOR = 262914

/**
 * Nova's drawer from its tables: [groups]; [members] as `appgroups` (groupId, "pkg/cls#serial"), where group -1 holds the
 * hidden apps; [titles] as `allapps` (componentName, profileId, title), a title being a name the user gave the app.
 */
fun novaDrawer(groups: List<NovaGroup>, members: List<Pair<Long, String>>, titles: List<Triple<String, Long, String>>): ImportedDrawer {
    val apps = members.groupBy({ it.first }, { it.second }).mapValues { (_, c) -> c.mapNotNull { componentKey(it) }.distinct() }
    // The Apps and Work tabs are Vulcan's own; groupId -100 is Nova's list of apps it has sorted into smart folders.
    val made = groups.sortedWith(compareBy({ it.order }, { it.id })).mapNotNull { g ->
        val type = when (g.type) { "TAB_APP_GROUP" -> "tab"; "FOLDER_APP_GROUP" -> "folder"; else -> return@mapNotNull null }
        val keys = apps[g.id].orEmpty().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Group(g.title, type, keys, if (type == "tab" && g.color != NOVA_NO_COLOR) "#%06X".format(g.color and 0xFFFFFF) else null)
    }
    val labels = titles.mapNotNull { (cn, user, title) -> title.trim().takeIf { it.isNotEmpty() }?.let { t -> componentKey("$cn#$user")?.let { it to t } } }.toMap()
    return ImportedDrawer(made, apps[-1L].orEmpty().toSet(), labels)
}

/** Nova's drawer tabs and folders, hidden apps and app names from database [db]; an empty drawer for a database without them. */
fun readNovaDrawer(context: Context, db: ByteArray): ImportedDrawer = withDatabase(context, db) { sql ->
    fun <T> rows(query: String, row: (android.database.Cursor) -> T): List<T> = runCatching {
        sql.rawQuery("$query LIMIT $MAX_IMPORT_ROWS", null).use { c -> buildList { while (c.moveToNext()) add(row(c)) } }
    }.getOrDefault(emptyList())
    fun android.database.Cursor.s(i: Int) = if (isNull(i)) "" else getString(i)
    novaDrawer(
        rows("SELECT _id, title, groupType, tabOrder, tabColor FROM drawer_groups") { NovaGroup(it.getLong(0), it.s(1), it.s(2), it.getInt(3), it.getInt(4)) },
        rows("SELECT groupId, component FROM appgroups") { it.getLong(0) to it.s(1) },
        rows("SELECT componentName, profileId, title FROM allapps") { Triple(it.s(0), it.getLong(1), it.s(2)) },
    )
}

/** Nova's gesture settings by name, with the Vulcan setting each is. */
val NOVA_GESTURES = mapOf("gesture_swipe_up" to "g_swipeUp", "gesture_swipe_down" to "g_swipeDown",
    "gesture_swipe_two_up" to "g_swipeUp2", "gesture_two_finger_scroll_up" to "g_swipeUp2",
    "gesture_swipe_two_down" to "g_swipeDown2", "gesture_two_finger_scroll_down" to "g_swipeDown2",
    "gesture_swipe_left" to "g_swipeLeft", "gesture_swipe_right" to "g_swipeRight", "gesture_double_tap" to "g_doubleTap",
    "gesture_double_tap_swipe_up" to "g_doubleTapUp", "gesture_double_tap_swipe_down" to "g_doubleTapDown",
    "gesture_pinch" to "g_pinchIn", "gesture_pinch_in" to "g_pinchIn", "gesture_pinch_out" to "g_pinchOut",
    "gesture_rotate_cw" to "g_rotateCw", "gesture_rotate_ccw" to "g_rotateCcw", "home_button_intent" to "homeButton")

/** Nova's own actions (LAUNCHER_ACTION) that Vulcan has. */
private val NOVA_ACTIONS = mapOf("NONE" to "none", "APP_DRAWER" to "drawer", "APP_SEARCH" to "search", "GLOBAL_SEARCH" to "search",
    "ASSIST" to "assistant", "VOICE_SEARCH" to "voice", "EXPAND_STATUS_BAR" to "notifications", "EXPAND_STATUS_SETTINGS_BAR" to "quicksettings",
    "SHOW_RECENT_APPS" to "recents", "SHOW_PREVIEWS" to "previews", "GOTO_DEFAULT_SCREEN" to "defaultpage", "TOGGLE_DOCK" to "toggledock",
    "TOGGLE_STATUS_BAR" to "togglebar", "SCREEN_OFF" to "lock", "NOVA_SETTINGS" to "settings", "NOVA_NOW" to "now")

/** The Vulcan action for a Nova gesture value ("Label$#Intent;…;end"): its LAUNCHER_ACTION, Nova's settings, or an app. */
fun novaAction(value: String): String? {
    val intent = value.substringAfter("$", value)
    Regex(";S\\.LAUNCHER_ACTION=([A-Z_]+);").find(intent)?.let { return NOVA_ACTIONS[it.groupValues[1]] }
    val component = Regex(";component=([^;]+);").find(intent)?.groupValues?.get(1) ?: return null
    if (component.startsWith("com.teslacoilsw.launcher/")) return "settings".takeIf { component.endsWith("SettingsActivity") }
    return appAction(componentKey(component))
}

/** Nova's icon shapes by name, where Vulcan's name differs; the rest are Vulcan's lowercased. */
private val NOVA_SHAPES = mapOf("DEFAULT" to "system", "NONE" to "system", "ROUNDED_SQUARE" to "rounded")

/** Vulcan look settings from Nova's preferences [values]: icon shape, drawer search, style, opacity and keyboard, the search bar. */
fun novaLooks(values: Map<String, String>): Map<String, String> = listOfNotNull(
    values["adaptive_icon_shape"]?.let { choice("shape", NOVA_SHAPES[it] ?: it.lowercase()) },
    choice("reshape", mapOf("OFF" to "off", "ON" to "always", "ALWAYS" to "always", "LEGACY" to "unthemed")[values["adaptive_icon_reshape"]]),
    choice("drawerSearch", mapOf("TOP" to "top", "BOTTOM" to "bottom", "NONE" to "off")[values["drawer_searchbar_position"]]),
    choice("drawerStyle", mapOf("VERTICAL" to "grid", "HORIZONTAL" to "pages", "LIST" to "list")[values["drawer_style"]]),
    choice("searchBar", mapOf("DOCK_ABOVE_ICONS" to "dockAbove", "DOCK_BELOW_ICONS" to "dockBelow", "PERSISTENT" to "top", "NONE" to "off")[values["searchbar_placement"]]),
    choice("drawerAlpha", percentStep(values["drawer_transparency"]?.toDoubleOrNull()?.div(255))),
    choice("showKeyboard", mapOf("true" to "on", "false" to "off")[values["drawer_show_keyboard_by_default"]]),
).toMap()

/** The database a favorites backup's layout is in: launcher.db or backup.db by name, else the first one. */
fun favoritesDb(archive: BackupArchive) = archive["launcher.db"] ?: archive["backup.db"] ?: archive["nova.db"]
    ?: archive.files.entries.firstOrNull { it.key.endsWith(".db") }?.value

/**
 * A favorites backup: [prefs] names the launcher's preferences files, the grid's source and the sign that a backup is
 * that launcher's; none for a catch-all.
 */
open class FavoritesFormat(override val name: Int, private val prefs: List<String>) : BackupFormat {
    override val reads = listOf(".db") + prefs
    override fun matches(archive: BackupArchive) = favoritesDb(archive) != null && (prefs.isEmpty() || prefs.any { it in archive })

    override fun read(context: Context, archive: BackupArchive): ImportedBackup {
        val (rows, ranks) = runCatching { readFavorites(context, favoritesDb(archive)!!) }
            // A database without a layout table isn't a launcher backup at all.
            .getOrElse { throw ImportProblem(context.withLaunchers(R.string.not_launcher_backup)) }
        val xml = prefs.joinToString("") { archive.text(it) }
        val settings = importedPrefs(xml)
        val base = ImportedBackup(importToItems(rows, ranks, settings["dockCols"]?.toInt() ?: 5), settings, name)
        return withLauncherOwn(context, archive, prefValues(xml), base)
    }

    /** [base] with what only this launcher's settings and tables hold, by [values] (its preferences); as is when none. */
    protected open fun withLauncherOwn(context: Context, archive: BackupArchive, values: Map<String, String>, base: ImportedBackup) = base
}

object NovaFormat : FavoritesFormat(R.string.format_nova, listOf("nova.xml", "com.teslacoilsw.launcher_preferences.xml")) {
    override fun withLauncherOwn(context: Context, archive: BackupArchive, values: Map<String, String>, base: ImportedBackup): ImportedBackup {
        val (gestures, left) = gestureSettings(values, NOVA_GESTURES, ::novaAction)
        return ImportedBackup(base.layout, base.settings + novaLooks(values) + gestures, base.name,
            drawer = readNovaDrawer(context, favoritesDb(archive)!!), gesturesLeft = left)
    }
}

/** Any other Launcher3-style backup: its layout comes over, its settings (whose names only it knows) don't. */
object Launcher3Format : FavoritesFormat(R.string.format_launcher3, emptyList()) {
    override val listed = false
}
