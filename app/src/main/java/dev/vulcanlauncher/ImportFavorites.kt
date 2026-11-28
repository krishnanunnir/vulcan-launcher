package dev.vulcanlauncher

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

// A zip holding an SQLite database with a Launcher3-style `favorites` table (and, in older ones, `workspaceScreens` for
// the page order) plus a preferences XML with the grid settings: Nova's .novabackup (see docs/nova-reference.md).

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

object FavoritesFormat : BackupFormat {
    override val name = R.string.format_nova
    override fun matches(archive: BackupArchive) = favoritesDb(archive) != null

    override fun read(context: Context, archive: BackupArchive): ImportedBackup {
        val (rows, ranks) = runCatching { readFavorites(context, favoritesDb(archive)!!) }
            // A database without a layout table isn't a launcher backup at all.
            .getOrElse { throw ImportProblem(context.getString(R.string.not_launcher_backup)) }
        val settings = importedPrefs(archive.text("preferences.xml"))
        return ImportedBackup(importToItems(rows, ranks, settings["dockCols"]?.toInt() ?: 5), settings, name)
    }
}
