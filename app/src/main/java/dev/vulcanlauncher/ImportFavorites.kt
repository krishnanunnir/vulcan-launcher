package dev.vulcanlauncher

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

// A zip holding an SQLite database with a Launcher3-style `favorites` table (and, in older ones, `workspaceScreens` for
// the page order). Nova's .novabackup (see docs/nova-reference.md) adds its preferences XML with the grid settings; Nova
// 8.9.4 names them nova.db and nova.xml, older ones backup.db and com.teslacoilsw.launcher_preferences.xml.

/** Rows of the `favorites` table in database [db] and page order by screen _id; throws when it has no such table. */
fun readFavorites(context: Context, db: ByteArray): Pair<List<ImportRow>, Map<Long, Int>> {
    val dir = File(context.cacheDir, "launcher-import").apply { deleteRecursively(); mkdirs() }
    val file = File(dir, "import.db").apply { writeBytes(db) }
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
            return rows to ranks
        }
    } finally {
        dir.deleteRecursively()
    }
}

/** The database a favorites backup's layout is in: launcher.db or backup.db by name, else the first one. */
fun favoritesDb(archive: BackupArchive) = archive["launcher.db"] ?: archive["backup.db"]
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
        val settings = importedPrefs(prefs.joinToString("") { archive.text(it) })
        return ImportedBackup(importToItems(rows, ranks, settings["dockCols"]?.toInt() ?: 5), settings, name)
    }
}

object NovaFormat : FavoritesFormat(R.string.format_nova, listOf("nova.xml", "com.teslacoilsw.launcher_preferences.xml"))

/** Any other Launcher3-style backup: its layout comes over, its settings (whose names only it knows) don't. */
object Launcher3Format : FavoritesFormat(R.string.format_launcher3, emptyList()) {
    override val listed = false
}
