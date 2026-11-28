package dev.vulcanlauncher

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

// Importing another launcher's backup: the shared part. Each format (ImportFavorites.kt, ImportInfoPb.kt, ImportJsonTables.kt)
// reads its files into Launcher3-style rows; [importToItems] turns them into Vulcan items.

/** One `favorites` row. [container]: -100 desktop, -101 dock, otherwise the _id of the folder it is in. */
data class ImportRow(
    val id: Long, val title: String, val intent: String, val container: Long, val screen: Long,
    val cellX: Double, val cellY: Double, val spanX: Double, val spanY: Double, val itemType: Int, val profileId: Long, val rank: Int,
    /** A widget's provider, "pkg/.Cls". */
    val provider: String = "",
)

// ImportRow.itemType, numbered as Launcher3 does (7 and up are each launcher's own).
const val ITEM_APP = 0
const val ITEM_SHORTCUT = 1
const val ITEM_FOLDER = 2
const val ITEM_WIDGET = 4
const val ITEM_CUSTOM_WIDGET = 5
const val ITEM_DEEP_SHORTCUT = 6
/** A row the launcher alone has (its "open drawer" icon, a dock folder): counted as its own, never placed. */
const val ITEM_OWN = -1

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

/**
 * Result of converting an imported layout: items, whether it uses half cells, how many rows couldn't be brought over (the
 * other launcher's own, or nothing in Vulcan to hold them) and how many were left out as their app isn't on this phone.
 */
class ImportedLayout(val items: List<Item>, val subgrid: Boolean, val skipped: Int, val missing: Int = 0) {
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
            ITEM_DEEP_SHORTCUT -> intentShortcut(r.intent)?.let { (pkg, id) -> Item(0, 0, 0, pkg = pkg, shortcutId = id, user = user, label = r.title) }
            ITEM_APP, ITEM_SHORTCUT -> intentComponent(r.intent)?.let { (pkg, cls) ->
                Item(0, 0, 0, pkg = pkg, cls = cls, user = user, label = if (r.itemType == ITEM_SHORTCUT) r.title else "")
            }
            else -> null
        }
    }
    val inFolder = rows.groupBy { it.container }
    val out = ArrayList<Item>()
    for (r in rows.filter { it.container == -100L || it.container == -101L }) {
        val base = when (r.itemType) {
            ITEM_FOLDER -> {
                val kids = inFolder[r.id].orEmpty().sortedWith(compareBy({ it.rank }, { it.cellY }, { it.cellX })).mapNotNull { k ->
                    leaf(k).also { if (it == null) skipped++ }
                }
                // ponytail: an untitled imported folder is named in English (this is pure code, no resources); Nova always stores a title.
                if (kids.isEmpty()) null else Item(0, 0, 0, folder = true, label = r.title.ifEmpty { "Folder" }, children = kids)
            }
            ITEM_WIDGET, ITEM_CUSTOM_WIDGET -> widgetSlot(r.provider)
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

/** A SharedPreferences XML file's int, long and string values by name. */
fun prefValues(xml: String): Map<String, String> =
    Regex("<(?:int|string|long)\\s+name=\"([^\"]+)\"(?:\\s+value=\"([^\"]*)\"\\s*/>|>([^<]*)</string>)").findAll(xml)
        .associate { m -> m.groupValues[1] to (m.groupValues[2].ifEmpty { m.groupValues[3] }) }

/**
 * Vulcan setting [ours] set to [n], kept within what the setting allows (a value outside it shows wrong in settings and is
 * refused by a later restore); null for no number.
 */
fun setting(ours: String, n: Int?): Pair<String, String>? {
    val allowed = choiceOf(ours)!!.values
    return n?.let { ours to it.coerceIn(allowed.first().toInt(), allowed.last().toInt()).toString() }
}

/** Vulcan settings from a favorites backup's preferences XML (only the grid and page settings carry over). */
fun importedPrefs(xml: String): Map<String, String> {
    val values = prefValues(xml).toMutableMap()
    // Nova 8 keeps a grid as one "ROWSxCOLS" ("6x5 subgrid") and the page count under another name; older Novas had a key each.
    for ((grid, rows, cols) in listOf(Triple("desktop_grid", "desktop_grid_rows", "desktop_grid_cols"), Triple("drawer_grid", "drawer_app_grid_rows", "drawer_app_grid_cols")))
        Regex("^(\\d+)x(\\d+)").find(values[grid].orEmpty().trim())?.let { m -> values.putIfAbsent(rows, m.groupValues[1]); values.putIfAbsent(cols, m.groupValues[2]) }
    values["workspace_screen_count"]?.let { values.putIfAbsent("desktop_page_count", it) }
    val map = mapOf("desktop_grid_cols" to "cols", "desktop_grid_rows" to "rows", "desktop_page_count" to "pages",
        "dock_grid_cols" to "dockCols", "dock_pages_count" to "dockPages", "drawer_app_grid_cols" to "drawerCols", "drawer_app_grid_rows" to "drawerRows")
    return map.mapNotNull { (theirs, ours) -> setting(ours, values[theirs]?.toIntOrNull()) }.toMap()
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

/** A launcher backup's files by base name (no path from the archive is ever used), only those a format reads. */
class BackupArchive(val files: Map<String, ByteArray>, /** Not a zip but a JSON text: a Vulcan backup picked here. */ val isJson: Boolean = false) {
    operator fun get(name: String) = files[name]
    operator fun contains(name: String) = name in files
    /** The text of file [name], or "". */
    fun text(name: String) = files[name]?.decodeToString().orEmpty()
}

/** Entries some format reads ([BackupFormat.reads]); anything else (icons, wallpapers, screenshots) is passed over unread. */
private fun wanted(name: String) = BACKUP_FORMATS.any { f -> f.reads.any { if (it.startsWith(".")) name.endsWith(it) else name == it } }

/**
 * Reads a picked file as a backup archive, within the caps: [MAX_BACKUP_BYTES] an entry, four times that for everything
 * unpacked (skipped entries included, as they're unpacked to move past them), [MAX_IMPORT_ROWS] entries. Throws an
 * [IOException] with [tooLarge] past them. A file that isn't a zip comes back empty.
 */
fun readArchive(input: InputStream, tooLarge: String): BackupArchive {
    val buffered = java.io.BufferedInputStream(input)
    buffered.mark(64)
    val head = ByteArray(64).let { it.copyOf(maxOf(0, buffered.read(it))) }
    buffered.reset()
    if (head.decodeToString().trimStart().startsWith("{")) return BackupArchive(emptyMap(), isJson = true)
    val files = LinkedHashMap<String, ByteArray>()
    var left = 4 * MAX_BACKUP_BYTES
    val skip = object : OutputStream() { override fun write(b: Int) {}; override fun write(b: ByteArray, off: Int, len: Int) {} }
    ZipInputStream(buffered).use { zip ->
        for (count in 0..MAX_IMPORT_ROWS) {
            if (count == MAX_IMPORT_ROWS) throw IOException(tooLarge)
            val e = zip.nextEntry ?: break
            val name = e.name.substringAfterLast('/').substringAfterLast('\\')
            fun copy(out: OutputStream) { left -= zip.copyAtMost(out, minOf(MAX_BACKUP_BYTES, left), tooLarge) }
            if (!e.isDirectory && wanted(name) && name !in files) files[name] = ByteArrayOutputStream().also(::copy).toByteArray()
            else copy(skip)
        }
    }
    return BackupArchive(files)
}

/** What an import read: the layout, Vulcan settings, the launcher's name (R.string) and whether it came from a newer version than this reads. */
class ImportedBackup(val layout: ImportedLayout, val settings: Map<String, String>, val name: Int, val newer: Boolean = false)

/** A problem to tell the user as it is, not as "can't read this file". */
class ImportProblem(message: String) : Exception(message)

/**
 * One launcher's backup file. A new launcher is one object reading its files into [ImportRow]s (or [Item]s) and a line in
 * [BACKUP_FORMATS]; everything after (the review, the widgets, storing it) is shared.
 */
interface BackupFormat {
    /** The launcher's name, shown in the review. */
    val name: Int
    /** Whether [name] is in the list of launchers Vulcan says it imports from; false for a catch-all. */
    val listed: Boolean get() = true
    /** The archive entries it reads: exact names, or suffixes starting with ".". */
    val reads: List<String>
    /** Whether [archive] is this format's, from its entries alone. */
    fun matches(archive: BackupArchive): Boolean
    /** Throws [ImportProblem] to say what's wrong; anything else is reported as a file of this kind it can't read. */
    fun read(context: Context, archive: BackupArchive): ImportedBackup
}

/** In order: the first that matches reads the file (favorites databases are last, as other formats hold one too). */
val BACKUP_FORMATS = listOf<BackupFormat>(InfoPbFormat, JsonTablesFormat, NovaFormat, Launcher3Format)

/** The launchers Vulcan imports from, for the user: "Lawnchair, Nova or Octopi". */
fun Context.importLaunchers(): String {
    val names = BACKUP_FORMATS.filter { it.listed }.map { getString(it.name) }.sorted()
    return if (names.size < 2) names.joinToString() else getString(R.string.list_or, names.dropLast(1).joinToString(", "), names.last())
}

/** [text] (with one %1$s) filled in with [importLaunchers]. */
fun Context.withLaunchers(text: Int): String = getString(text, importLaunchers())

/** Reads another launcher's backup chosen by the user. Throws with a readable message when it isn't one. */
fun readLauncherBackup(context: Context, uri: Uri): ImportedBackup {
    val archive = context.contentResolver.openInputStream(uri)!!.use { readArchive(it, context.getString(R.string.backup_too_large)) }
    if (archive.isJson) throw ImportProblem(context.getString(R.string.import_is_vulcan))
    val format = BACKUP_FORMATS.firstOrNull { it.matches(archive) } ?: throw ImportProblem(context.withLaunchers(R.string.not_launcher_backup))
    return try {
        format.read(context, archive)
    } catch (e: ImportProblem) {
        throw e
    } catch (e: Exception) {
        throw ImportProblem(context.getString(R.string.import_unknown_shape, context.getString(format.name)))
    }
}
