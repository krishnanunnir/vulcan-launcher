package dev.vulcanlauncher

import android.content.Context

// A zip with an `info.pb` describing the backup, a Launcher3 `launcher.db` (its `favorites` table read as Nova's is),
// `com.android.launcher3.prefs.xml` and a DataStore `preferences.preferences_pb`: Lawnchair's .lawnchairbackup.
//
// info.pb fields: 2 backup version (1 is the one this reads), 4 contents (bit 0: layout and settings, bit 1: wallpaper),
// 7 grid state { 1 "columns,rows", 2 dock icons }.

/**
 * A protobuf message's fields by number: varints as Long, length-delimited ones as ByteArray (fixed-size ones are skipped).
 * Throws [IllegalArgumentException] on bytes that aren't a message.
 */
fun protoFields(b: ByteArray): Map<Int, List<Any>> {
    val out = HashMap<Int, MutableList<Any>>()
    var i = 0
    fun varint(): Long {
        var r = 0L
        var shift = 0
        while (true) {
            require(i < b.size && shift < 64) { "not a protobuf message" }
            val x = b[i++].toInt()
            r = r or ((x and 0x7f).toLong() shl shift)
            if (x and 0x80 == 0) return r
            shift += 7
        }
    }
    fun skip(n: Long) { require(n in 0..(b.size - i).toLong()) { "not a protobuf message" }; i += n.toInt() }
    while (i < b.size) {
        val key = varint()
        val value: Any? = when ((key and 7).toInt()) {
            0 -> varint()
            2 -> { val n = varint(); val start = i; skip(n); b.copyOfRange(start, i) }
            1 -> { skip(8); null }
            5 -> { skip(4); null }
            else -> throw IllegalArgumentException("not a protobuf message")
        }
        if (value != null) out.getOrPut((key ushr 3).toInt(), ::ArrayList) += value
    }
    return out
}

private fun Map<Int, List<Any>>.long(f: Int) = this[f]?.firstOrNull() as? Long
private fun Map<Int, List<Any>>.bytes(f: Int) = this[f]?.firstOrNull() as? ByteArray

/** A DataStore preferences file's whole-number entries: a map entry is { 1 name, 2 value { 3 int } }. */
fun dataStoreInts(b: ByteArray): Map<String, Int> = protoFields(b)[1].orEmpty().mapNotNull { e ->
    val entry = protoFields(e as? ByteArray ?: return@mapNotNull null)
    val n = entry.bytes(2)?.let(::protoFields)?.long(3) ?: return@mapNotNull null
    entry.bytes(1)?.decodeToString()?.let { it to n.toInt() }
}.toMap()

/** "columns,rows" as two numbers, or null. */
private fun gridSize(s: String?) = s?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 2 }

/**
 * Vulcan settings from an info.pb backup: the grid as the backup states it (info.pb), else as its preferences remember it,
 * and the drawer's columns from the DataStore file when set.
 */
fun infoPbSettings(info: ByteArray, prefsXml: String, dataStore: ByteArray?): Map<String, String> {
    val grid = protoFields(info).bytes(7)?.let(::protoFields)
    val prefs = prefValues(prefsXml)
    val size = gridSize(grid?.bytes(1)?.decodeToString()) ?: gridSize(prefs["migration_src_workspace_size"])
    val dock = grid?.long(2)?.toInt()?.takeIf { it > 0 } ?: prefs["migration_src_hotseat_count"]?.toIntOrNull()
        ?: prefs["pref_hotseatColumns"]?.toIntOrNull()
    val drawer = dataStore?.let { runCatching { dataStoreInts(it) }.getOrNull() }.orEmpty()
    return listOfNotNull(
        setting("cols", size?.get(0) ?: prefs["pref_workspaceColumns"]?.toIntOrNull()),
        setting("rows", size?.get(1) ?: prefs["pref_workspaceRows"]?.toIntOrNull()),
        setting("dockCols", dock),
        setting("drawerCols", drawer["drawer_columns"]),
    ).toMap()
}

object InfoPbFormat : BackupFormat {
    override val name = R.string.format_lawnchair
    override fun matches(archive: BackupArchive) = "info.pb" in archive

    override fun read(context: Context, archive: BackupArchive): ImportedBackup {
        val info = archive["info.pb"]!!
        val fields = protoFields(info)
        val db = archive["launcher.db"]
        // A backup of the wallpaper alone has no layout.
        if (db == null || (fields.long(4) ?: 1L) and 1L == 0L) throw ImportProblem(context.getString(R.string.import_no_layout, context.getString(name)))
        val settings = infoPbSettings(info, archive.text("prefs.xml"), archive["preferences.preferences_pb"])
        val (rows, ranks) = readFavorites(context, db)
        return ImportedBackup(importToItems(rows, ranks, settings["dockCols"]?.toInt() ?: 5), settings, name,
            newer = (fields.long(2) ?: 1L) > 1L)
    }
}
