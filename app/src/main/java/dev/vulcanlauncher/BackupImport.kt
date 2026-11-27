package dev.vulcanlauncher

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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
    // A malformed %xx drops this shortcut, not the whole import.
    return runCatching { pkg to java.net.URLDecoder.decode(id.replace("+", "%2B"), "UTF-8") }.getOrNull()
}

/**
 * A widget's spot from its provider ("pkg/.Cls"), filled with a new widget of that kind after the import. Null for the
 * other launcher's own widgets ("pkg/#custom-widget-0"), which exist only inside it.
 */
fun widgetSlot(provider: String): Item? = intentComponent("component=$provider")?.takeIf { '#' !in it.second }
    ?.let { (pkg, cls) -> Item(0, 0, 0, pkg = pkg, cls = cls, action = WIDGET_SLOT) }

/** In Store.data: set by an import with widgets, until each spot is filled or the user stops (MainActivity.restoreNextWidget). */
const val RESTORING_WIDGETS = "restoringWidgets"

/** In Store.data: set by a restore or import made while Vulcan isn't the default home app, until its shortcuts are pinned (#103). */
const val REPIN_SHORTCUTS = "repinShortcuts"

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
    val inFolder = rows.groupBy { it.container }
    val out = ArrayList<Item>()
    for (r in rows.filter { it.container == -100L || it.container == -101L }) {
        val base = when (r.itemType) {
            2 -> {
                val kids = inFolder[r.id].orEmpty().sortedWith(compareBy({ it.rank }, { it.cellY }, { it.cellX })).mapNotNull { k ->
                    leaf(k).also { if (it == null) skipped++ }
                }
                // ponytail: an untitled imported folder is named in English (this is pure code, no resources); Nova always stores a title.
                if (kids.isEmpty()) null else Item(0, 0, 0, folder = true, label = r.title.ifEmpty { "Folder" }, children = kids)
            }
            4, 5 -> widgetSlot(r.provider)
            else -> leaf(r)
        }
        if (base == null) { skipped++; continue }
        val item = if (r.container == -101L) {
            // Never below 0: a negative position would land on a home page.
            val pos = cell(if (r.cellX >= 0) r.cellX else r.screen.toDouble()).coerceAtLeast(0)
            base.copy(page = -1 - pos / dockCols, x = pos % dockCols, y = 0)
        } else {
            // An unlisted screen is taken as a page number; a negative one (or one past Int) must not become a dock page.
            val page = screenRank[r.screen] ?: r.screen.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            base.copy(page = page, x = cell(r.cellX * f), y = cell(r.cellY * f), w = cell(r.spanX * f), h = cell(r.spanY * f))
        }
        // Held to what home can place, as a stored layout is (an off-grid cell moves to the first free one).
        wellFormed(item)?.let { out += it } ?: skipped++
    }
    return ImportedLayout(out, subgrid, skipped)
}

/** A cell or span from a backup row, in whole cells: NaN (which roundToInt throws on) is -1, off the grid. */
private fun cell(d: Double) = if (d.isNaN()) -1 else d.roundToInt()

/** Vulcan settings from the backup's preferences XML (only the grid and page settings carry over). */
fun importedPrefs(xml: String): Map<String, String> {
    val values = Regex("<(?:int|string|long)\\s+name=\"([^\"]+)\"(?:\\s+value=\"([^\"]*)\"\\s*/>|>([^<]*)</string>)").findAll(xml)
        .associate { m -> m.groupValues[1] to (m.groupValues[2].ifEmpty { m.groupValues[3] }) }
    val map = mapOf("desktop_grid_cols" to "cols", "desktop_grid_rows" to "rows", "desktop_page_count" to "pages",
        "dock_grid_cols" to "dockCols", "dock_pages_count" to "dockPages", "drawer_app_grid_cols" to "drawerCols", "drawer_app_grid_rows" to "drawerRows")
    // Kept within what the setting allows: a value outside it shows wrong in settings and is refused by a later restore.
    return map.mapNotNull { (theirs, ours) ->
        val allowed = choiceOf(ours)!!.values
        values[theirs]?.toIntOrNull()?.let { ours to it.coerceIn(allowed.first().toInt(), allowed.last().toInt()).toString() }
    }.toMap()
}

/** More than any backup holds: a picked file is read on the main thread, so a bigger one (or a zip bomb) is refused. */
const val MAX_BACKUP_BYTES = 16L shl 20

/** Copies this stream to [out], throwing [IOException] with message [tooLarge] once more than [max] bytes come. Returns the bytes copied. */
fun InputStream.copyAtMost(out: OutputStream, max: Long, tooLarge: String): Long {
    val buf = ByteArray(8192)
    var total = 0L
    while (true) {
        val n = read(buf).takeIf { it >= 0 } ?: return total
        total += n
        if (total > max) throw IOException(tooLarge)
        out.write(buf, 0, n)
    }
}

fun InputStream.readAtMost(max: Long, tooLarge: String): ByteArray = ByteArrayOutputStream().also { copyAtMost(it, max, tooLarge) }.toByteArray()

/** More rows or zip entries than any launcher backup has: a file with more entries is refused, rows past it are left out, not read for minutes on the main thread. */
const val MAX_IMPORT_ROWS = 5000

/** Reads a .novabackup chosen by the user. Throws with a readable message when the file isn't one. */
fun readLauncherBackup(context: Context, uri: Uri): Pair<ImportedLayout, Map<String, String>> {
    val dir = File(context.cacheDir, "launcher-import").apply { deleteRecursively(); mkdirs() }
    var db: File? = null
    var prefs = ""
    val tooLarge = context.getString(R.string.backup_too_large)
    // A budget for everything unpacked, entries skipped included (they're unpacked to move past them), so no entry is an
    // uncapped zip bomb; roomier than one entry's cap, as a backup can also hold a wallpaper and icons.
    var left = 4 * MAX_BACKUP_BYTES
    val skip = object : OutputStream() { override fun write(b: Int) {}; override fun write(b: ByteArray, off: Int, len: Int) {} }
    ZipInputStream(context.contentResolver.openInputStream(uri)!!).use { zip ->
        for (count in 0..MAX_IMPORT_ROWS) {
            if (count == MAX_IMPORT_ROWS) throw IOException(tooLarge)
            val e = zip.nextEntry ?: break
            val name = e.name.substringAfterLast('/')
            fun copy(out: OutputStream) { left -= zip.copyAtMost(out, minOf(MAX_BACKUP_BYTES, left), tooLarge) }
            when {
                e.isDirectory -> {}
                name.endsWith(".db") && (db == null || name == "backup.db" || name == "launcher.db") ->
                    db = File(dir, "import.db").apply { outputStream().use(::copy) }
                name.endsWith("preferences.xml") -> prefs = ByteArrayOutputStream().also(::copy).toByteArray().decodeToString()
                else -> copy(skip)
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
            sql.rawQuery("SELECT * FROM favorites LIMIT $MAX_IMPORT_ROWS", null).use { c ->
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
