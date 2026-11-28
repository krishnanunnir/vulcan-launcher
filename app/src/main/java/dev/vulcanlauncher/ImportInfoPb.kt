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

/** A DataStore preferences file's text entries: a string { 5 } as one, a string set { 6 { 1 each } } as its strings. */
fun dataStoreStrings(b: ByteArray): Map<String, List<String>> = protoFields(b)[1].orEmpty().mapNotNull { e ->
    val entry = protoFields(e as? ByteArray ?: return@mapNotNull null)
    val value = entry.bytes(2)?.let(::protoFields) ?: return@mapNotNull null
    val texts = value.bytes(5)?.let { listOf(it.decodeToString()) }
        ?: value.bytes(6)?.let { set -> protoFields(set)[1].orEmpty().mapNotNull { (it as? ByteArray)?.decodeToString() } }
        ?: return@mapNotNull null
    entry.bytes(1)?.decodeToString()?.let { it to texts }
}.toMap()

/** Lawnchair's gesture settings by name, with the Vulcan setting each is (its Back button has no Vulcan setting). */
val LAWNCHAIR_GESTURES = mapOf("swipe_up_gesture_handler" to "g_swipeUp", "swipe_down_gesture_handler" to "g_swipeDown",
    "double_tap_gesture_handler" to "g_doubleTap", "home_press_gesture_handler" to "homeButton")

// "openNotificationdata" is what Lawnchair itself writes for Open notifications (seen in a real backup).
private val LAWNCHAIR_ACTIONS = mapOf("noOp" to "none", "sleep" to "lock", "recents" to "recents", "openNotifications" to "notifications",
    "openNotificationdata" to "notifications",
    "openAppDrawer" to "drawer", "openAppSearch" to "search", "openSearch" to "search", "openAssistant" to "assistant")

/** A Lawnchair app key ("pkg/cls#user"), personal apps only. */
private fun lawnchairKey(s: String) = componentKey(s) { it.takeIf { it == 0L } } // ponytail: #N is a user id, not a serial; work apps are dropped until a backup with one shows how to map it

/** The Vulcan action for a Lawnchair gesture value, JSON such as {"type":"openApp","target":{"key":"pkg/cls#0"}}. */
fun lawnchairAction(value: String): String? {
    val o = runCatching { org.json.JSONObject(value) }.getOrNull() ?: return null
    val type = o.optString("type")
    return if (type == "openApp") appAction(o.optJSONObject("target")?.optString("key")?.let(::lawnchairKey)) else LAWNCHAIR_ACTIONS[type]
}

/** Lawnchair's icon shapes where Vulcan's name differs; the rest are Vulcan's as they are. */
private val LAWNCHAIR_SHAPES = mapOf("roundedSquare" to "rounded")

/** Settings, gestures left out and the drawer from Lawnchair's DataStore file [b]; nothing from one that can't be read. */
fun lawnchairOwn(b: ByteArray?): Triple<Map<String, String>, Int, ImportedDrawer> {
    val strings = b?.let { runCatching { dataStoreStrings(it) }.getOrNull() }.orEmpty()
    val (gestures, left) = gestureSettings(strings.mapNotNull { (k, v) -> v.firstOrNull()?.let { k to it } }.toMap(), LAWNCHAIR_GESTURES, ::lawnchairAction)
    val shape = strings["icon_shape"]?.firstOrNull()?.let { choice("shape", LAWNCHAIR_SHAPES[it] ?: it) }
    return Triple(gestures + listOfNotNull(shape), left, ImportedDrawer(hidden = strings["hidden_apps"].orEmpty().mapNotNull(::lawnchairKey).toSet()))
}

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
        choice("drawerAlpha", percentStep(prefs["pref_drawerOpacity"]?.toDoubleOrNull())),
    ).toMap()
}

private const val PREFS = "com.android.launcher3.prefs.xml"

object InfoPbFormat : BackupFormat {
    override val name = R.string.format_lawnchair
    override val reads = listOf("info.pb", "launcher.db", PREFS, "preferences.preferences_pb")
    override fun matches(archive: BackupArchive) = "info.pb" in archive

    override fun read(context: Context, archive: BackupArchive): ImportedBackup {
        val info = archive["info.pb"]!!
        val fields = protoFields(info)
        val db = archive["launcher.db"]
        // A backup of the wallpaper alone has no layout.
        if (db == null || (fields.long(4) ?: 1L) and 1L == 0L) throw ImportProblem(context.getString(R.string.import_no_layout, context.getString(name)))
        val settings = infoPbSettings(info, archive.text(PREFS), archive["preferences.preferences_pb"])
        val (rows, ranks) = readFavorites(context, db)
        val (own, left, drawer) = lawnchairOwn(archive["preferences.preferences_pb"])
        return ImportedBackup(importToItems(rows, ranks, settings["dockCols"]?.toInt() ?: 5), settings + own, name,
            newer = (fields.long(2) ?: 1L) > 1L, drawer = drawer, gesturesLeft = left)
    }
}
