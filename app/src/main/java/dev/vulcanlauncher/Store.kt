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
 * A widget's children are the other widgets stacked with it.
 * [w] and [h] are in grid cells, except a Now page widget card's [h]: its height in NOW_CARD_STEPs, 1 for the widget's own.
 */
/** [Item.action] of an imported widget's spot. */
const val WIDGET_SLOT = "widgetSlot"

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
    val isAction get() = action.isNotEmpty() && action != "activity" && !isClock && !isWidgetSlot
    /** An imported widget's spot, waiting for a new widget from provider [pkg]/[cls] (see BackupImport). */
    val isWidgetSlot get() = action == WIDGET_SLOT
    /** Keeps its own size when moved, and never goes in the dock or a folder. */
    val isBlock get() = isWidget || isClock || isWidgetSlot
    /** A plain app icon: can be merged into folders. */
    val isApp get() = pkg.isNotEmpty() && !isShortcut && !isWidget && !folder && action.isEmpty()
    val appKey get() = appKey(pkg, cls, user)
    /** A widget's ids, the rest of its stack included (in [children]); none for anything else. */
    val widgetIds get() = if (isWidget) listOf(widgetId) + children.map { it.widgetId } else emptyList()
}

/** This widget holding the stack [ids] (the first one fronts it in the layout), or null when none are left. */
fun Item.withWidgets(ids: List<Int>): Item? =
    ids.firstOrNull()?.let { copy(widgetId = it, children = ids.drop(1).map { id -> Item(0, 0, 0, widgetId = id) }) }

/** [items] keeping only the widgets [valid] accepts; a stack loses the rest, and goes when none are left. */
fun keepWidgets(items: List<Item>, valid: (Int) -> Boolean): List<Item> =
    items.mapNotNull { if (it.isWidget) it.withWidgets(it.widgetIds.filter(valid)) else it }

/**
 * A restored layout's widgets. An ID [provider] ("pkg/cls", null when gone) still knows stays, and the front one only as
 * the widget the backup noted (IDs are small numbers; another phone's can name a different widget here). A widget left
 * with none, whose noted provider [canAdd] takes, becomes a spot to add it again: an import or reset freed its ID.
 */
fun restoredWidgets(items: List<Item>, provider: (Int) -> String?, canAdd: (Item) -> Boolean): List<Item> = items.mapNotNull { i ->
    if (!i.isWidget) return@mapNotNull i
    val noted = if (i.pkg.isEmpty()) null else "${i.pkg}/${i.cls}"
    val ids = i.widgetIds.filter { id -> provider(id)?.let { id != i.widgetId || noted == null || it == noted } == true }
    // ponytail: a stack whose IDs are all gone comes back as its front widget's spot only; note each one's provider if missed
    i.copy(pkg = "", cls = "", user = 0).withWidgets(ids)
        ?: Item(i.page, i.x, i.y, i.w, i.h, i.pkg, i.cls, user = i.user, action = WIDGET_SLOT).takeIf { noted != null && canAdd(it) }
}

fun appKey(pkg: String, cls: String, user: Long) = "$pkg/$cls/$user"

fun Item.toJson(): JSONObject = JSONObject().put("page", page).put("x", x).put("y", y).put("w", w).put("h", h)
    .put("pkg", pkg).put("cls", cls).put("widgetId", widgetId).put("user", user).put("shortcutId", shortcutId)
    .put("action", action).put("folder", folder).put("label", label).put("icon", icon).put("swipe", swipe)
    .put("children", JSONArray().apply { children.forEach { put(it.toJson()) } })

/** Far past any grid, and small enough that adding two never overflows: a hand-written cell or size is held within it. */
const val MAX_CELL = 1000

fun itemFromJson(o: JSONObject): Item = Item(
    o.getInt("page"), cell(o.getInt("x")), cell(o.getInt("y")), cell(o.optInt("w", 1)), cell(o.optInt("h", 1)),
    o.optString("pkg"), o.optString("cls"), o.optInt("widgetId", -1), o.optLong("user"), o.optString("shortcutId"),
    o.optString("action"), o.optBoolean("folder"),
    o.optJSONArray("children")?.let(::itemList) ?: emptyList(),
    o.optString("label"), o.optString("icon"), o.optString("swipe"))

private fun cell(n: Int) = n.coerceIn(-MAX_CELL, MAX_CELL)

fun itemsToJson(items: List<Item>) = JSONArray().apply { items.forEach { put(it.toJson()) } }.toString()
/** The items of [s]; an item that can't be read is dropped, not the whole layout. Throws when [s] isn't a JSON array. */
fun itemsFromJson(s: String): MutableList<Item> = itemList(JSONArray(s))

private fun itemList(a: JSONArray) = (0 until a.length()).mapNotNull { runCatching { itemFromJson(a.getJSONObject(it)) }.getOrNull() }.toMutableList()

/** [items] without the apps and shortcuts of an uninstalled package. A folder left with one item turns back into it. */
fun removePackage(items: List<Item>, pkg: String, user: Long) = removeApps(items) { it.pkg == pkg && it.user == user }

/**
 * [items] with [child] taken out of the folder on cell ([page], [x], [y]); a folder left with one item turns back into
 * it, an empty one goes. Null, and nothing to do, when that folder or that child is no longer there.
 */
fun takeFromFolder(items: List<Item>, page: Int, x: Int, y: Int, child: Item): List<Item>? {
    val i = items.indexOfFirst { it.folder && it.page == page && it.x == x && it.y == y }
    val f = items.getOrNull(i) ?: return null
    val j = f.children.indexOf(child).takeIf { it >= 0 } ?: return null
    val rest = f.children.filterIndexed { k, _ -> k != j }
    return items.toMutableList().apply {
        when (rest.size) {
            0 -> removeAt(i)
            1 -> set(i, rest[0].copy(page = f.page, x = f.x, y = f.y, w = f.w, h = f.h))
            else -> set(i, f.copy(children = rest))
        }
    }
}

private fun goneApp(i: Item, gone: (Item) -> Boolean) = i.pkg.isNotEmpty() && !i.isWidget && !i.isWidgetSlot && !i.folder && gone(i)

/** How many icons [removeApps] would take out, folder entries included: what a restore reports as left out. */
fun missingApps(items: List<Item>, gone: (Item) -> Boolean) = items.sumOf { f -> (f.children + f).count { goneApp(it, gone) } }

/**
 * [items] without the apps, shortcuts and activities [gone] picks. Widgets, widget spots and folders are never passed to
 * it: a widget's package may have no app Vulcan can see, so it would look uninstalled.
 */
fun removeApps(items: List<Item>, gone: (Item) -> Boolean): List<Item> {
    fun g(i: Item) = goneApp(i, gone)
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
 * Everything is stored as strings in SharedPreferences files:
 * `layout` (home items), `looks` (settings), `data` (hidden apps, drawer groups, custom app edits) and `usage`
 * (launch counts: written on every app launch, so kept apart from what the user set up).
 */
class Store(private val context: Context) {
    private val prefs = context.getSharedPreferences("layout", Context.MODE_PRIVATE)
    val data = context.getSharedPreferences("data", Context.MODE_PRIVATE)!!.apply {
        val fix = dataTypeFixes(all)
        if (fix.isNotEmpty()) edit().apply { fix.forEach { (k, v) -> if (v is Boolean) putBoolean(k, v) else putString(k, v.toString()) } }.commit()
    }

    private val usage = context.getSharedPreferences("usage", Context.MODE_PRIVATE)
    init { adoptLaunches() }

    /** The counts were data's "launches" before 0.34, and a backup still holds them there: moved to `usage`. */
    private fun adoptLaunches() {
        val old = data.all["launches"] as? String ?: return
        usage.edit().putString("launches", old).commit()
        data.edit().remove("launches").commit()
    }
    /** How often each app (by key) was opened. */
    fun launches() = runCatching { JSONObject(usage.getString("launches", "{}")!!) }.getOrElse { JSONObject() }
    fun countLaunch(key: String) = launches().let { usage.edit().putString("launches", it.put(key, it.optInt(key) + 1).toString()).apply() }
    /** Changes whenever a launch is counted: the drawer's most used order depends on it. */
    val usageState: Any? get() = usage.getString("launches", null)

    /** Nothing saved yet: first run, or after a reset. */
    val isNew get() = !prefs.contains("items")
    /** Daily automatic backups, newest first. Three generations, so a bad day can't overwrite the last good copy. */
    val autoBackups get() = listOf("auto-backup.json", "auto-backup-2.json", "auto-backup-3.json").map { File(context.filesDir, it) }

    /**
     * The saved layout, in subgrid factor [sub]. If it can't be read at all (a bad restore, a hand edit or a newer format), the raw text
     * is kept in `files/layout-unreadable.json` and the newest readable automatic backup is used, so the launcher never
     * crash-loops.
     */
    fun load(sub: Int): MutableList<Item> {
        val raw = prefs.getString("items", "[]")!!
        // Subgrid factors are 1 and 2; any other (a hand-edited backup) is taken as the current one, not divided by.
        val saved = prefs.getString("sub", null)?.toIntOrNull()?.takeIf { it == 1 || it == 2 }
        val unreadable = File(context.filesDir, "layout-unreadable.json")
        runCatching {
            // A layout from a newer build (read after a downgrade) may mean something else: set aside like an unreadable one.
            require((prefs.getString("version", null)?.toIntOrNull() ?: 1) <= LAYOUT_VERSION)
            val items = itemsFromJson(raw)
            // An item this build can't read is dropped, and home saves at once: the text as it was is kept first.
            if (items.size < JSONArray(raw).length()) runCatching { unreadable.writeText(raw) }
            return layoutIn(items, saved, sub).toMutableList()
        }
        runCatching { unreadable.writeText(raw) }
        // What's recovered is saved at once: export reads the stored layout, so a restore (which backs up first) would fail too.
        for (f in autoBackups) runCatching {
            val l = backupLayout(JSONObject(f.readText()))
            return layoutIn(itemsFromJson(l.getValue("items")), l["sub"]?.toIntOrNull()?.takeIf { it == 1 || it == 2 }, sub).toMutableList().also { save(it, sub) }
        }
        return mutableListOf<Item>().also { save(it, sub) }
    }

    /** Saves [items], whose home positions are in subgrid factor [sub]. Strings only, so backups round-trip them. */
    fun save(items: List<Item>, sub: Int) = prefs.edit().putString("items", itemsToJson(items))
        .putString("version", LAYOUT_VERSION.toString()).putString("sub", sub.toString()).apply()

    /**
     * Writes today's automatic backup, keeping the two before it. A setup already kept moves to the front instead of
     * taking a second slot, so restoring back and forth between backups can't push out the last different one.
     */
    fun rotateAutoBackup() {
        val old = autoBackups.mapNotNull { f -> runCatching { f.readText() to f.lastModified() }.getOrNull() }.toMap()
        val now = export()
        // Unchanged since the newest: it is dated now instead, or the daily check (MainActivity.onStop) would rerun on
        // every stop while a kept backup that moved to the front shows its old date.
        if (runCatching { autoBackups[0].readText() }.getOrNull() == now) { autoBackups[0].setLastModified(System.currentTimeMillis()); return }
        val texts = rotatedBackups(now, old.keys.toList(), autoBackups.size)
        // All written whole before any replaces a file: a write cut short (a full disk) must not stand as a backup or lose one.
        // A kept backup keeps its date: the list in Settings tells them apart by it.
        texts.zip(autoBackups).map { (text, f) -> File(f.path + ".tmp").apply { writeText(text); old[text]?.let(::setLastModified) } to f }
            .forEach { (tmp, f) -> check(tmp.renameTo(f)) { context.getString(R.string.auto_backup_not_saved) } }
    }

    // Lenient: a restored or hand-edited value of the wrong type reads as empty instead of crashing home on every start.
    fun strings(key: String): MutableSet<String> = (runCatching { JSONArray(data.getString(key, "[]")) }.getOrNull() ?: JSONArray())
        .let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String }.toMutableSet() }
    fun putStrings(key: String, set: Collection<String>) = data.edit().putString(key, JSONArray(set.toList()).toString()).apply()
    fun obj(key: String) = runCatching { JSONObject(data.getString(key, "{}")!!) }.getOrElse { JSONObject() }
    fun putObj(key: String, o: JSONObject) = data.edit().putString(key, o.toString()).apply()

    /**
     * Whole configuration as one readable JSON document (see [readableBackup]), for backup files. [apps] are listed in it so
     * whoever edits the file knows what is installed.
     */
    fun export(apps: List<App> = emptyList()): String {
        // Sorted: the same setup always reads as the same text, which is how rotateAutoBackup spots one it already keeps.
        val looks = context.getSharedPreferences("looks", Context.MODE_PRIVATE).all.mapValues { it.value.toString() }.toSortedMap()
        val sub = prefs.getString("sub", null)?.toIntOrNull() ?: if (looks["subgrid"] == "on") 2 else 1
        // Each widget notes its provider, so a restore can offer it again once its ID is gone (an import or reset freed it).
        val awm = android.appwidget.AppWidgetManager.getInstance(context)
        val um = context.getSystemService(android.os.UserManager::class.java)
        val items = itemsFromJson(prefs.getString("items", "[]")!!).map { i ->
            (if (i.isWidget) awm.getAppWidgetInfo(i.widgetId) else null)?.let { w ->
                i.copy(pkg = w.provider.packageName, cls = w.provider.className, user = um.getSerialNumberForUser(w.profile))
            } ?: i
        }
        // A backup keeps the launch counts where they always were, in data.
        val data = (data.all.filterKeys { it !in PASSING_STATE }.mapValues { it.value.toString() } +
            listOfNotNull(usage.getString("launches", null)?.let { "launches" to it })).toSortedMap()
        return readableBackup(looks, items, sub, data, apps.map { Triple(it.title, it.pkg, it.cls) to it.serial }, context::getString).toString(2)
    }

    data class RestoreResult(val replacedLayout: Boolean, val rejected: List<String>)

    /**
     * Replaces the configuration from a backup, or merges the settings from an older Designer style file.
     * [cls] finds the main activity for apps written without one. Throws on an unrecognised file.
     */
    fun import(json: String, cls: (pkg: String, user: Long) -> String? = { _, _ -> null }): RestoreResult {
        val root = JSONObject(json)
        // Read the whole file before writing anything, so a bad one leaves the current setup untouched.
        val (files, rejected) = when (root.optString("format")) {
            "vulcan-backup-2" -> readableBackupFiles(root, cls)
            "vulcan-backup-1" -> legacyBackupFiles(root)
            else -> throw IllegalArgumentException(context.getString(R.string.not_vulcan_backup))
        }
        // ponytail: three commits, not one transaction; an I/O failure between them can mix old and new files (each
        // failed commit throws, and the auto-backup just written restores the rest). Upgrade if a mixed restore is ever reported.
        val replacedLayout = "layout" in files
        // What's about to be replaced stays restorable from Settings. It throws, and nothing is replaced, if it can't be saved.
        if (replacedLayout) rotateAutoBackup()
        for ((name, values) in files) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().apply {
            if (replacedLayout) clear()
            // data keeps its flags as booleans; the backup has them as "true"/"false"
            values.forEach { (k, v) -> if (restoredKey(name, k)) { if (restoredAsFlag(name, k)) putBoolean(k, v == "true") else putString(k, v) } }
        }.commit().let { ok -> check(ok) { context.getString(R.string.restore_not_saved) } }
        if (replacedLayout) usage.edit().clear().commit()
        adoptLaunches()
        return RestoreResult(replacedLayout, rejected)
    }

    /** Clears everything, after saving what was there as an automatic backup (best effort: the reset was asked for). */
    fun reset() = runCatching { rotateAutoBackup() }.let { _ -> (FILES + "usage").forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() } }

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
    (action && (Regex("page:[0-8]").matches(v) || Regex("app:[^/]+/[^/]+/\\d+").matches(v) || shortcutAction(v) != null))

val BACKUP_HOW_TO = listOf(
    "A Vulcan setup. Edit it by hand or with an AI assistant, then load it on the phone: long-press the home screen, Settings, Backup & restore, Restore from a file.",
    "settings: one value per key. reference.settings lists every key, what it does, its default and the values it takes. A colour setting also takes any #RRGGBB or #AARRGGBB. A value that isn't allowed is skipped and the setting keeps its default.",
    "home.items: everything on the home pages and the dock. page 0 is the first home page (settings.pages says how many); the dock is page -1 (a second dock page is -2). x and y count cells from the top left; w and h are the size in cells (default 1). The grid is settings.cols by settings.rows; the dock is one row of settings.dockCols. When home.sub is 2, home positions and sizes count half cells. Don't overlap items; one that falls off the grid moves to the first free cell.",
    "An app: {\"page\":0,\"x\":1,\"y\":3,\"pkg\":\"<package>\"} with pkg from reference.installedApps. cls picks one of its activities: leave it out for the main one, but give it when installedApps lists the package more than once (Google has search and voice search). Optional: label (replaces the name), swipe (an action code run when the icon is swiped up), user (a work profile number from installedApps).",
    "A folder: {\"page\":0,\"x\":0,\"y\":0,\"folder\":true,\"label\":\"Social\",\"children\":[apps, each with page, x and y set to 0]}. An action icon: {\"page\":0,\"x\":2,\"y\":0,\"action\":\"<action code>\",\"label\":\"Lock\"}.",
    "Action codes (gestures, swipe, action icons): see reference.actions, plus page:N (jump to page N, 0-based) and app:<pkg>/<cls>/<user> (open an app).",
    "icon (on apps, folders, action icons and in data.custom): empty for the app's own icon, \"pack:<icon pack package>:<drawable name>\" for an installed icon pack, or \"uri:<uri>\" for a photo (\"uriraw:<uri>\" draws it without the icon shape).",
    "data.hidden: app keys (<pkg>/<cls>/<user>) left out of the drawer. data.groups: drawer tabs and folders, [{\"name\":\"Social\",\"type\":\"tab\" or \"folder\",\"apps\":[app keys],\"color\":\"#RRGGBB\" (optional, a tab's own colour)}]. data.custom: an app's name and icon everywhere it has no home-screen override, {\"<app key>\":{\"label\":\"Mail\",\"icon\":\"\"}}. data.launches: launch counts, for sorting by most used. data.drawerOrder: app keys in the order arranged by hand (the manual sort; a tab keeps its own in its apps list). data.favourites: app keys pinned above the drawer's tabs, in order.",
    "Widgets can't be made from this file; add them on the phone.",
)

/**
 * The readable backup document. [apps]: (label, package, activity) to profile serial of each installed app. [text] reads a
 * string resource: setting titles and value names are in the phone's language.
 */
/** data keys that say where a welcome or a widget's setup stands: true of the moment, so they stay out of backups. */
val PASSING_STATE = setOf("welcome", "welcomeStep", "welcomeImport", "pendingWidget", "pendingSlot", "widgetToNow", RESTORING_WIDGETS)

/** data keys read with getBoolean, which throws on text: on every start, for a hand-edited "hideWorkTab": "on". */
val DATA_FLAGS = setOf(WELCOME, WELCOME_IMPORT, RESTORING_WIDGETS, REPIN_SHORTCUTS, ALL_HIDDEN, WORK_HIDDEN, "widgetToNow")

/** Whether a restore stores [key] of preference file [file] as a boolean: a flag always, whatever the backup says. */
fun restoredAsFlag(file: String, key: String) = file == "data" && key in DATA_FLAGS

/**
 * looks keys that belong to this phone: its last known location (sunset night mode, looked up again after a restore) and
 * whether Vulcan hid its status-bar clock (restored elsewhere, it would take out a clock the user hid). Never in a backup.
 */
val DEVICE_KEYS = setOf("lat", "lon", "clockHiddenByUs")

/**
 * Whether a restore writes [key] of preference file [file]. Passing state (a widget mid-setup, a welcome step) belongs to
 * the phone that wrote it, and a hand-edited one could crash every start; [DEVICE_KEYS] belong to this phone.
 */
fun restoredKey(file: String, key: String) = !(file == "data" && key in PASSING_STATE) && !(file == "looks" && key in DEVICE_KEYS)

/**
 * data's values ([all]) stored as the wrong type, each as it should be: a flag kept as "true"/"false" text (a restore
 * before 0.31) makes getBoolean throw, and any other key kept as a boolean (a restore before 0.35 of a hand-edited
 * "groups": false) makes getString throw, on every start.
 */
fun dataTypeFixes(all: Map<String, Any?>): Map<String, Any> = all.mapNotNull { (k, v) ->
    when {
        k in DATA_FLAGS -> if (v == "true" || v == "false") v == "true" else null
        v is Boolean -> v.toString()
        else -> null
    }?.let { k to it }
}.toMap()

/** The automatic backups after saving [now] in front of [kept] (newest first): one already kept moves up, not into a second slot. */
fun rotatedBackups(now: String, kept: List<String>, slots: Int) = (listOf(now) + kept).distinct().take(slots)

fun readableBackup(looks: Map<String, String>, items: List<Item>, sub: Int, data: Map<String, String>,
                   apps: List<Pair<Triple<String, String, String>, Long>>, text: (Int) -> String): JSONObject = JSONObject()
    .put("format", "vulcan-backup-2")
    .put("howTo", JSONArray(BACKUP_HOW_TO))
    .put("settings", JSONObject(LOOKS.associate { it.key to it.default } + (looks - DEVICE_KEYS)))
    .put("home", JSONObject().put("sub", sub).put("items", JSONArray(items.map { it.toReadable() })))
    // Values the launcher keeps as JSON text (hidden apps, groups, custom edits) are written as JSON, not as strings of it.
    .put("data", JSONObject().apply { data.forEach { (k, v) -> put(k, runCatching { org.json.JSONTokener(v).nextValue() }.getOrNull()?.takeIf { it is JSONObject || it is JSONArray } ?: v) } })
    .put("reference", JSONObject()
        .put("settings", JSONObject().apply {
            for (c in LOOKS) put(c.key, JSONObject().put("group", c.group).put("title", text(c.title)).put("default", c.default)
                .put("values", if (c.action) "an action code"
                    // Three numbers, 343 combinations: said in words rather than listed.
                    else if (c.key == "pageMargin") "left and right x top x bottom in dp, each 0 to 48 in steps of 8, such as 16x8x24"
                    else JSONObject().apply { c.values.forEachIndexed { i, v -> put(v, c.name(i, text)) } }))
        })
        .put("actions", JSONObject().apply { ACTIONS.zip(ACTION_NAMES).forEach { (v, n) -> put(v, text(n)) } })
        .put("installedApps", JSONArray(apps.sortedWith(compareBy(labelOrder()) { it.first.first }).map { (a, user) ->
            JSONObject().put("label", a.first).put("pkg", a.second).put("cls", a.third).apply { if (user != 0L) put("user", user) }
        })))

/** The `layout` preferences ("items", "sub") held in backup [root], whichever format it is in. Throws when it has no layout. */
fun backupLayout(root: JSONObject): Map<String, String> {
    if (root.optString("format") == "vulcan-backup-2") return readableBackupFiles(root) { _, _ -> null }.first.getValue("layout")
    val layout = root.getJSONObject("layout")
    // A backup from before version 2 is in the subgrid factor of the settings saved with it.
    val sub = layout.optString("sub").toIntOrNull() ?: if (root.optJSONObject("looks")?.optString("subgrid") == "on") 2 else 1
    return mapOf("items" to layout.getString("items"), "sub" to sub.toString())
}

/**
 * The preference files a format 1 backup restores to, and the "key=value" settings left out: its settings are held to
 * what a format 2 one accepts, so a hand-edited "pages": "100000" can't crash home on every start.
 */
fun legacyBackupFiles(root: JSONObject): Pair<Map<String, Map<String, String>>, List<String>> {
    val rejected = ArrayList<String>()
    // Without a layout it's settings to merge: only the files it has, or import() would clear the ones it lacks.
    val files = Store.FILES.filter { root.has("layout") || root.has(it) }.associateWith { name ->
        val o = root.optJSONObject(name) ?: JSONObject()
        o.keys().asSequence().associateWith { o.getString(it) }.filter { (k, v) ->
            (name != "looks" || LOOKS.firstOrNull { it.key == k }?.accepts(v) != false).also { if (!it) rejected += "$k=$v" }
        }
    }
    files["layout"]?.get("items")?.let { JSONArray(it) }
    return files to rejected
}

/**
 * The preference files a readable backup restores to, and the "key=value" settings left out because the value isn't
 * allowed. A legacy Designer style file only supplies looks. [cls] fills in the main activity for apps written with
 * only a package.
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
    if (!root.has("home") && !root.has("data")) {
        require(root.optJSONObject("settings") != null) { "Missing settings" }
        return mapOf("looks" to looks) to rejected
    }
    // Restoring replaces the layout: a home section that's missing, misspelt or the wrong shape must not read as an empty one.
    val home = requireNotNull(root.optJSONObject("home")?.takeIf { it.optJSONArray("items") != null }) { "Missing home items" }
    fun fill(i: Item): Item = i.copy(cls = if (i.isApp && i.cls.isEmpty()) cls(i.pkg, i.user).orEmpty() else i.cls, children = i.children.map(::fill))
    val items = itemList(home.getJSONArray("items")).map(::fill)
    val sub = home.optInt("sub", if (looks["subgrid"] == "on") 2 else 1)
    val data = root.optJSONObject("data")?.let { d -> d.keys().asSequence().associateWith { d.get(it).toString() } } ?: emptyMap()
    return mapOf("layout" to mapOf("items" to itemsToJson(items), "version" to LAYOUT_VERSION.toString(), "sub" to sub.toString()),
        // A phone's own file carries these markers; one written by hand doesn't, and without them the settings it left
        // out would be filled with the defaults of an install from before 0.16 (Looks.adoptDefaults).
        "looks" to (mapOf("defaults" to "nova", "defaults032" to "on") + looks), "data" to data) to rejected
}
