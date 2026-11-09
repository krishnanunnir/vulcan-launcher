package dev.vulcanlauncher

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

// Importing another launcher's backup (.novabackup): a zip holding an SQLite database with a Launcher3-style
// `favorites` table plus `workspaceScreens`, and a preferences XML with the grid settings (see docs/nova-reference.md).

/** One `favorites` row. [container]: -100 desktop, -101 dock, otherwise the _id of the folder it is in. */
data class ImportRow(
    val id: Long, val title: String, val intent: String, val container: Long, val screen: Long,
    val cellX: Double, val cellY: Double, val spanX: Double, val spanY: Double, val itemType: Int, val profileId: Long, val rank: Int,
    /** A widget's provider, "pkg/.Cls". */
    val provider: String = "",
)

/** Package and class from an intent URI ("#Intent;…;component=pkg/.Cls;end"), or null when it names no component. */
fun intentComponent(intent: String): Pair<String, String>? {
    val c = Regex("component=([^;]+)").find(intent)?.groupValues?.get(1) ?: return null
    val pkg = c.substringBefore("/")
    val cls = c.substringAfter("/", "")
    if (pkg.isEmpty() || cls.isEmpty()) return null
    return pkg to if (cls.startsWith(".")) pkg + cls else cls
}

/** (package, shortcut id) of a deep-shortcut intent, or null. */
fun intentShortcut(intent: String): Pair<String, String>? {
    val id = Regex("S\\.shortcut_id=([^;]+)").find(intent)?.groupValues?.get(1) ?: return null
    val pkg = Regex("(?:^|;)package=([^;]+)").find(intent)?.groupValues?.get(1) ?: intentComponent(intent)?.first ?: return null
    return pkg to java.net.URLDecoder.decode(id.replace("+", "%2B"), "UTF-8")
}

/**
 * A widget's spot from its provider ("pkg/.Cls"), filled with a new widget of that kind after the import. Null for the
 * other launcher's own widgets ("pkg/#custom-widget-0"), which exist only inside it.
 */
fun widgetSlot(provider: String): Item? = intentComponent("component=$provider")?.takeIf { '#' !in it.second }
    ?.let { (pkg, cls) -> Item(0, 0, 0, pkg = pkg, cls = cls, action = WIDGET_SLOT) }

/** In Store.data: set by an import with widgets, until each spot is filled or the user stops (MainActivity.restoreNextWidget). */
const val RESTORING_WIDGETS = "restoringWidgets"

/** Result of converting an imported layout: items, whether it uses half cells, and how many rows couldn't be brought over. */
class ImportedLayout(val items: List<Item>, val subgrid: Boolean, val skipped: Int) {
    val widgets get() = items.count { it.isWidgetSlot }
}

/**
 * Converts imported rows to Vulcan items. [screenRank] maps screen _id → page index. Widgets become [widgetSlot]s: their IDs
 * belong to the other launcher's widget host, so new ones are made in their place. Dock positions past [dockCols] go to later dock pages.
 */
fun importToItems(rows: List<ImportRow>, screenRank: Map<Long, Int>, dockCols: Int): ImportedLayout {
    val subgrid = rows.any { it.container == -100L && (it.cellX % 1 != 0.0 || it.cellY % 1 != 0.0 || it.spanX % 1 != 0.0 || it.spanY % 1 != 0.0) }
    val f = if (subgrid) 2 else 1
    var skipped = 0
    fun leaf(r: ImportRow): Item? {
        val user = maxOf(0L, r.profileId)
        return when (r.itemType) {
            6 -> intentShortcut(r.intent)?.let { (pkg, id) -> Item(0, 0, 0, pkg = pkg, shortcutId = id, user = user, label = r.title) }
            0, 1 -> intentComponent(r.intent)?.let { (pkg, cls) -> Item(0, 0, 0, pkg = pkg, cls = cls, user = user, label = if (r.itemType == 1) r.title else "") }
            else -> null
        }
    }
    val out = ArrayList<Item>()
    for (r in rows.filter { it.container == -100L || it.container == -101L }) {
        val base = when (r.itemType) {
            2 -> {
                val kids = rows.filter { it.container == r.id }.sortedWith(compareBy({ it.rank }, { it.cellY }, { it.cellX })).mapNotNull { k ->
                    leaf(k).also { if (it == null) skipped++ }
                }
                // ponytail: an untitled imported folder is named in English (this is pure code, no resources); Nova always stores a title.
                if (kids.isEmpty()) null else Item(0, 0, 0, folder = true, label = r.title.ifEmpty { "Folder" }, children = kids)
            }
            4, 5 -> widgetSlot(r.provider)
            else -> leaf(r)
        }
        if (base == null) { skipped++; continue }
        out += if (r.container == -101L) {
            val pos = (if (r.cellX >= 0) r.cellX else r.screen.toDouble()).roundToInt()
            base.copy(page = -1 - pos / dockCols, x = pos % dockCols, y = 0)
        } else {
            val page = screenRank[r.screen] ?: r.screen.toInt()
            base.copy(page = page, x = (r.cellX * f).roundToInt(), y = (r.cellY * f).roundToInt(),
                w = maxOf(1, (r.spanX * f).roundToInt()), h = maxOf(1, (r.spanY * f).roundToInt()))
        }
    }
    return ImportedLayout(out, subgrid, skipped)
}

/** Vulcan settings from the backup's preferences XML (only the grid and page settings carry over). */
fun importedPrefs(xml: String): Map<String, String> {
    val values = Regex("<(?:int|string|long)\\s+name=\"([^\"]+)\"(?:\\s+value=\"([^\"]*)\"\\s*/>|>([^<]*)</string>)").findAll(xml)
        .associate { m -> m.groupValues[1] to (m.groupValues[2].ifEmpty { m.groupValues[3] }) }
    val map = mapOf("desktop_grid_cols" to "cols", "desktop_grid_rows" to "rows", "desktop_page_count" to "pages",
        "dock_grid_cols" to "dockCols", "dock_pages_count" to "dockPages", "drawer_app_grid_cols" to "drawerCols", "drawer_app_grid_rows" to "drawerRows")
    return map.mapNotNull { (theirs, ours) -> values[theirs]?.toIntOrNull()?.let { ours to it.toString() } }.toMap()
}

/** Reads a .novabackup chosen by the user. Throws with a readable message when the file isn't one. */
fun readLauncherBackup(context: Context, uri: Uri): Pair<ImportedLayout, Map<String, String>> {
    val dir = File(context.cacheDir, "launcher-import").apply { deleteRecursively(); mkdirs() }
    var db: File? = null
    var prefs = ""
    ZipInputStream(context.contentResolver.openInputStream(uri)!!).use { zip ->
        while (true) {
            val e = zip.nextEntry ?: break
            val name = e.name.substringAfterLast('/')
            when {
                e.isDirectory -> {}
                name.endsWith(".db") && (db == null || name == "backup.db" || name == "launcher.db") ->
                    db = File(dir, "import.db").apply { outputStream().use { zip.copyTo(it) } }
                name.endsWith("preferences.xml") -> prefs = zip.readBytes().decodeToString()
            }
        }
    }
    val file = db ?: error(context.getString(R.string.not_launcher_backup))
    try {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sql ->
            val ranks = HashMap<Long, Int>()
            runCatching {
                sql.rawQuery("SELECT _id, screenRank FROM workspaceScreens ORDER BY screenRank", null).use { c ->
                    var i = 0
                    while (c.moveToNext()) ranks[c.getLong(0)] = i++
                }
            }
            val rows = ArrayList<ImportRow>()
            sql.rawQuery("SELECT * FROM favorites", null).use { c ->
                fun s(n: String) = c.getColumnIndex(n).let { if (it < 0 || c.isNull(it)) "" else c.getString(it) }
                fun d(n: String) = c.getColumnIndex(n).let { if (it < 0 || c.isNull(it)) 0.0 else c.getDouble(it) }
                fun l(n: String, def: Long = 0) = c.getColumnIndex(n).let { if (it < 0 || c.isNull(it)) def else c.getLong(it) }
                while (c.moveToNext()) rows += ImportRow(l("_id"), s("title"), s("intent"), l("container"), l("screen"),
                    d("cellX"), d("cellY"), d("spanX").takeIf { it > 0 } ?: 1.0, d("spanY").takeIf { it > 0 } ?: 1.0,
                    l("itemType").toInt(), l("profileId", -1), l("rank").toInt(), s("appWidgetProvider"))
            }
            val settings = importedPrefs(prefs)
            val layout = importToItems(rows, ranks, settings["dockCols"]?.toInt() ?: 5)
            return layout to settings
        }
    } finally {
        dir.deleteRecursively()
    }
}
