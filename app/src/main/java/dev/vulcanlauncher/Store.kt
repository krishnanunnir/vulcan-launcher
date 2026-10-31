package dev.vulcanlauncher

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** First dock page. Dock page n is stored as page `-1 - n`. */
const val DOCK = -1

fun isDock(page: Int) = page < 0
fun dockIndex(page: Int) = -1 - page

/**
 * One thing placed on a home page, the dock or inside a folder:
 * an app (pkg/cls/user), a widget (widgetId), a pinned shortcut (pkg + shortcutId),
 * a launcher action (action), an activity shortcut (pkg/cls + action "activity"), the clock (action "clock") or a folder (children).
 */
data class Item(
    val page: Int,
    val x: Int,
    val y: Int,
    val w: Int = 1,
    val h: Int = 1,
    val pkg: String = "",
    val cls: String = "",
    val widgetId: Int = -1,
    val user: Long = 0,
    val shortcutId: String = "",
    val action: String = "",
    val folder: Boolean = false,
    val children: List<Item> = emptyList(),
    val label: String = "",
    val icon: String = "",
    val swipe: String = "",
) {
    val isWidget get() = widgetId != -1
    val isShortcut get() = shortcutId.isNotEmpty()
    val isClock get() = action == "clock"
    val isAction get() = action.isNotEmpty() && action != "activity" && !isClock
    /** Keeps its own size when moved, and never goes in the dock or a folder. */
    val isBlock get() = isWidget || isClock
    /** A plain app icon: can be merged into folders. */
    val isApp get() = pkg.isNotEmpty() && !isShortcut && !isWidget && !folder && action.isEmpty()
    val appKey get() = appKey(pkg, cls, user)
}

fun appKey(pkg: String, cls: String, user: Long) = "$pkg/$cls/$user"

fun Item.toJson(): JSONObject = JSONObject().put("page", page).put("x", x).put("y", y).put("w", w).put("h", h)
    .put("pkg", pkg).put("cls", cls).put("widgetId", widgetId).put("user", user).put("shortcutId", shortcutId)
    .put("action", action).put("folder", folder).put("label", label).put("icon", icon).put("swipe", swipe)
    .put("children", JSONArray().apply { children.forEach { put(it.toJson()) } })

fun itemFromJson(o: JSONObject): Item = Item(
    o.getInt("page"), o.getInt("x"), o.getInt("y"), o.optInt("w", 1), o.optInt("h", 1),
    o.optString("pkg"), o.optString("cls"), o.optInt("widgetId", -1), o.optLong("user"), o.optString("shortcutId"),
    o.optString("action"), o.optBoolean("folder"),
    o.optJSONArray("children")?.let(::itemList) ?: emptyList(),
    o.optString("label"), o.optString("icon"), o.optString("swipe"))

fun itemsToJson(items: List<Item>) = JSONArray().apply { items.forEach { put(it.toJson()) } }.toString()
/** The items of [s]; an item that can't be read is dropped, not the whole layout. Throws when [s] isn't a JSON array. */
fun itemsFromJson(s: String): MutableList<Item> = itemList(JSONArray(s))

private fun itemList(a: JSONArray) = (0 until a.length()).mapNotNull { runCatching { itemFromJson(a.getJSONObject(it)) }.getOrNull() }.toMutableList()

/** [items] without the apps and shortcuts of an uninstalled package. A folder left with one item turns back into it. */
fun removePackage(items: List<Item>, pkg: String, user: Long) = removeApps(items) { it.pkg == pkg && it.user == user }

/** [items] without the apps, shortcuts and activities [gone] picks (widgets and folders are never passed to it). */
fun removeApps(items: List<Item>, gone: (Item) -> Boolean): List<Item> {
    fun g(i: Item) = i.pkg.isNotEmpty() && !i.isWidget && !i.folder && gone(i)
    return items.filterNot(::g).mapNotNull { f ->
        val rest = f.children.filterNot(::g)
        when {
            rest.size == f.children.size -> f
            rest.isEmpty() -> null
            rest.size == 1 -> rest[0].copy(page = f.page, x = f.x, y = f.y, w = f.w, h = f.h)
            else -> f.copy(children = rest)
        }
    }
}

/**
 * Layout format. 2 stores "sub", the subgrid factor its home positions are in, next to the items; 1 (no "version")
 * left it to the subgrid setting in another file, so a crash between the two writes could halve or double every position.
 */
const val LAYOUT_VERSION = 2

/** Home positions saved in subgrid factor [saved] (null: a version 1 layout, already in [current]), converted to [current]. */
fun layoutIn(items: List<Item>, saved: Int?, current: Int): List<Item> = when {
    saved == null || saved == current -> items
    current > saved -> scaleItems(items, current / saved)
    else -> scaleItems(items, -(saved / current))
}

/**
 * Everything is stored as strings in three SharedPreferences files:
 * `layout` (home items), `looks` (settings) and `data` (hidden apps, drawer groups, launch counts, custom app edits).
 */
class Store(private val context: Context) {
    private val prefs = context.getSharedPreferences("layout", Context.MODE_PRIVATE)
    val data = context.getSharedPreferences("data", Context.MODE_PRIVATE)!!

    /** Nothing saved yet: first run, or after a reset. */
    val isNew get() = !prefs.contains("items")
    /** Daily automatic backups, newest first. Three generations, so a bad day can't overwrite the last good copy. */
    val autoBackups get() = listOf("auto-backup.json", "auto-backup-2.json", "auto-backup-3.json").map { File(context.filesDir, it) }

    /**
     * The saved layout, in subgrid factor [sub]. If it can't be read at all (a bad restore or a hand edit), the raw text
     * is kept in `files/layout-unreadable.json` and the newest readable automatic backup is used, so the launcher never
     * crash-loops.
     */
    fun load(sub: Int): MutableList<Item> {
        val raw = prefs.getString("items", "[]")!!
        runCatching { return layoutIn(itemsFromJson(raw), prefs.getString("sub", null)?.toIntOrNull(), sub).toMutableList() }
        runCatching { File(context.filesDir, "layout-unreadable.json").writeText(raw) }
        for (f in autoBackups) runCatching {
            val b = JSONObject(f.readText())
            val layout = b.getJSONObject("layout")
            // A backup from before version 2 is in the subgrid factor of the settings saved with it.
            val saved = layout.optString("sub").toIntOrNull() ?: if (b.optJSONObject("looks")?.optString("subgrid") == "on") 2 else 1
            return layoutIn(itemsFromJson(layout.getString("items")), saved, sub).toMutableList()
        }
        return mutableListOf()
    }

    /** Saves [items], whose home positions are in subgrid factor [sub]. Strings only, so backups round-trip them. */
    fun save(items: List<Item>, sub: Int) = prefs.edit().putString("items", itemsToJson(items))
        .putString("version", LAYOUT_VERSION.toString()).putString("sub", sub.toString()).apply()

    /** Writes today's automatic backup, keeping the two before it. */
    fun rotateAutoBackup() {
        val (a, b, c) = autoBackups
        if (b.exists()) b.renameTo(c)
        if (a.exists()) a.renameTo(b)
        a.writeText(export())
    }

    fun strings(key: String): MutableSet<String> = JSONArray(data.getString(key, "[]")).let { a -> MutableList(a.length()) { a.getString(it) }.toMutableSet() }
    fun putStrings(key: String, set: Collection<String>) = data.edit().putString(key, JSONArray(set.toList()).toString()).apply()
    fun obj(key: String) = JSONObject(data.getString(key, "{}")!!)
    fun putObj(key: String, o: JSONObject) = data.edit().putString(key, o.toString()).apply()

    /**
     * Whole configuration as one readable JSON document (see [readableBackup]), for backup files. [apps] are listed in it so
     * whoever edits the file knows what is installed.
     */
    fun export(apps: List<App> = emptyList()): String {
        val looks = context.getSharedPreferences("looks", Context.MODE_PRIVATE).all.mapValues { it.value.toString() }
        val sub = prefs.getString("sub", null)?.toIntOrNull() ?: if (looks["subgrid"] == "on") 2 else 1
        val items = itemsFromJson(prefs.getString("items", "[]")!!)
        val data = data.all.mapValues { it.value.toString() }
        return readableBackup(looks, items, sub, data, apps.map { Triple(it.title, it.pkg, it.cls) to it.serial }, context::getString).toString(2)
    }

    /**
     * Replaces the configuration with a backup made by [export] (or by hand, or an AI assistant), or by an older version.
     * [cls] finds the main activity of a package, for apps written without one. Returns the settings it left out because
     * their values aren't allowed. Throws on a file that isn't a backup.
     */
    fun import(json: String, cls: (pkg: String, user: Long) -> String? = { _, _ -> null }): List<String> {
        val root = JSONObject(json)
        // Read the whole file before writing anything, so a bad one leaves the current setup untouched.
        val (files, rejected) = when (root.optString("format")) {
            "vulcan-backup-2" -> readableBackupFiles(root, cls)
            "vulcan-backup-1" -> FILES.associateWith { name ->
                val o = root.optJSONObject(name) ?: JSONObject()
                o.keys().asSequence().associateWith { o.getString(it) }
            }.also { f -> f["layout"]?.get("items")?.let { JSONArray(it) } } to emptyList()
            else -> throw IllegalArgumentException(context.getString(R.string.not_vulcan_backup))
        }
        // ponytail: three commits, not one transaction; an I/O failure between them can mix old and new files. The layout
        // carries its own subgrid factor, so its positions still read right; only grid sizes could be the old ones (fitItems copes)
        for ((name, values) in files) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply {
            values.forEach { (k, v) -> putString(k, v) }
        }.commit()
        return rejected
    }

    fun reset() = FILES.forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }

    companion object { val FILES = listOf("layout", "looks", "data") }
}

// ---- Readable backup (format 2): meant to be read and written by people and AI assistants ----

/** [this] without the fields left at their defaults. */
fun Item.toReadable(): JSONObject {
    val o = toJson().apply { remove("children") }
    val d = Item(page, x, y).toJson()
    for (k in d.keys().asSequence().toList()) if (k != "children" && o.opt(k) == d.opt(k) && k !in listOf("page", "x", "y")) o.remove(k)
    if (children.isNotEmpty()) o.put("children", JSONArray(children.map { it.toReadable() }))
    return o
}

/** Whether setting [this] can take [v]: one of its values, any #RRGGBB / #AARRGGBB colour, or a page or app action. */
fun Choice.accepts(v: String) = v in values ||
    (COLOR_VALUES[0] in values && Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})").matches(v)) ||
    (action && (Regex("page:[0-8]").matches(v) || Regex("app:[^/]+/[^/]+/\\d+").matches(v)))

val BACKUP_HOW_TO = listOf(
    "A Vulcan setup. Edit it by hand or with an AI assistant, then load it on the phone: long-press the home screen, Settings, Backup & restore, Restore from a file.",
    "settings: one value per key. reference.settings lists every key, what it does, its default and the values it takes. A colour setting also takes any #RRGGBB or #AARRGGBB. A value that isn't allowed is skipped and the setting keeps its default.",
    "home.items: everything on the home pages and the dock. page 0 is the first home page (settings.pages says how many); the dock is page -1 (a second dock page is -2). x and y count cells from the top left; w and h are the size in cells (default 1). The grid is settings.cols by settings.rows; the dock is one row of settings.dockCols. When home.sub is 2, home positions and sizes count half cells. Don't overlap items; one that falls off the grid moves to the first free cell.",
    "An app: {\"page\":0,\"x\":1,\"y\":3,\"pkg\":\"<package>\"} with pkg from reference.installedApps. cls picks one of its activities: leave it out for the main one, but give it when installedApps lists the package more than once (Google has search and voice search). Optional: label (replaces the name), swipe (an action code run when the icon is swiped up), user (a work profile number from installedApps).",
    "A folder: {\"page\":0,\"x\":0,\"y\":0,\"folder\":true,\"label\":\"Social\",\"children\":[apps, each with page, x and y set to 0]}. An action icon: {\"page\":0,\"x\":2,\"y\":0,\"action\":\"<action code>\",\"label\":\"Lock\"}.",
    "Action codes (gestures, swipe, action icons): see reference.actions, plus page:N (jump to page N, 0-based) and app:<pkg>/<cls>/<user> (open an app).",
    "icon (on apps, folders, action icons and in data.custom): empty for the app's own icon, or \"pack:<icon pack package>:<drawable name>\" for an installed icon pack.",
    "data.hidden: app keys (<pkg>/<cls>/<user>) left out of the drawer. data.groups: drawer tabs and folders, [{\"name\":\"Social\",\"type\":\"tab\" or \"folder\",\"apps\":[app keys]}]. data.custom: an app's name and icon everywhere it has no home-screen override, {\"<app key>\":{\"label\":\"Mail\",\"icon\":\"\"}}. data.launches: launch counts, for sorting by most used.",
    "Widgets can't be made from this file; add them on the phone.",
)

/**
 * The readable backup document. [apps]: (label, package, activity) to profile serial of each installed app. [text] reads a
 * string resource: setting titles and value names are in the phone's language.
 */
fun readableBackup(looks: Map<String, String>, items: List<Item>, sub: Int, data: Map<String, String>,
                   apps: List<Pair<Triple<String, String, String>, Long>>, text: (Int) -> String): JSONObject = JSONObject()
    .put("format", "vulcan-backup-2")
    .put("howTo", JSONArray(BACKUP_HOW_TO))
    .put("settings", JSONObject(LOOKS.associate { it.key to it.default } + looks))
    .put("home", JSONObject().put("sub", sub).put("items", JSONArray(items.map { it.toReadable() })))
    // Values the launcher keeps as JSON text (hidden apps, groups, custom edits) are written as JSON, not as strings of it.
    .put("data", JSONObject().apply { data.forEach { (k, v) -> put(k, runCatching { org.json.JSONTokener(v).nextValue() }.getOrNull()?.takeIf { it is JSONObject || it is JSONArray } ?: v) } })
    .put("reference", JSONObject()
        .put("settings", JSONObject().apply {
            for (c in LOOKS) put(c.key, JSONObject().put("group", c.group).put("title", text(c.title)).put("default", c.default)
                .put("values", if (c.action) "an action code" else JSONObject().apply { c.values.forEachIndexed { i, v -> put(v, c.name(i, text)) } }))
        })
        .put("actions", JSONObject().apply { ACTIONS.zip(ACTION_NAMES).forEach { (v, n) -> put(v, text(n)) } })
        .put("installedApps", JSONArray(apps.sortedWith(compareBy(labelOrder()) { it.first.first }).map { (a, user) ->
            JSONObject().put("label", a.first).put("pkg", a.second).put("cls", a.third).apply { if (user != 0L) put("user", user) }
        })))

/**
 * The three preference files a readable backup restores to, and the "key=value" settings left out because the value isn't
 * allowed. true/false are taken as on/off. [cls] fills in the main activity of apps written with only a package.
 */
fun readableBackupFiles(root: JSONObject, cls: (pkg: String, user: Long) -> String?): Pair<Map<String, Map<String, String>>, List<String>> {
    val rejected = ArrayList<String>()
    val looks = HashMap<String, String>()
    root.optJSONObject("settings")?.let { s ->
        for (k in s.keys()) {
            val v = when (val raw = s.get(k)) { true -> "on"; false -> "off"; else -> raw.toString() }
            if (LOOKS.firstOrNull { it.key == k }?.accepts(v) != false) looks[k] = v else rejected += "$k=$v"
        }
    }
    val home = root.optJSONObject("home") ?: JSONObject()
    fun fill(i: Item): Item = i.copy(cls = if (i.isApp && i.cls.isEmpty()) cls(i.pkg, i.user).orEmpty() else i.cls, children = i.children.map(::fill))
    val items = itemList(home.optJSONArray("items") ?: JSONArray()).map(::fill)
    val sub = home.optInt("sub", if (looks["subgrid"] == "on") 2 else 1)
    val data = root.optJSONObject("data")?.let { d -> d.keys().asSequence().associateWith { d.get(it).toString() } } ?: emptyMap()
    return mapOf("layout" to mapOf("items" to itemsToJson(items), "version" to LAYOUT_VERSION.toString(), "sub" to sub.toString()),
        "looks" to looks, "data" to data) to rejected
}
