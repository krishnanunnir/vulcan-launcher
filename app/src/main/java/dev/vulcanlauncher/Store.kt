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

/**
 * Far past any grid, and small enough that adding two never overflows. Every item read from outside (the stored layout,
 * a backup, another launcher's) is held within it by [wellFormed], so nothing after the read checks again.
 */
const val MAX_CELL = 1000

/**
 * What a decoder made of data this build may not have written: [value], null when the input as a whole can't be used
 * (not the right shape, or a newer format), and what it [dropped] or [repaired] on the way.
 */
class Decoded<T>(val value: T?, val dropped: List<String> = emptyList(), val repaired: List<String> = emptyList())

/**
 * [i] as home can place it: a size of 1 to [MAX_CELL] and a cell from 0 to [MAX_CELL]. An item with a cell outside that
 * (negative, or a hand-written 2147483647) or a size below 1 is put at cell [MAX_CELL], off any grid, for fitItems to move to
 * the first free one. A page is held within ±[MAX_CELL]. Children too, and a folder left with none is null.
 */
fun wellFormed(i: Item): Item? {
    val kids = i.children.mapNotNull(::wellFormed)
    if (i.folder && kids.isEmpty()) return null
    val off = i.x !in 0..MAX_CELL || i.y !in 0..MAX_CELL || i.w < 1 || i.h < 1
    return i.copy(page = i.page.coerceIn(-MAX_CELL, MAX_CELL), x = if (off) MAX_CELL else i.x, y = if (off) MAX_CELL else i.y,
        w = i.w.coerceIn(1, MAX_CELL), h = i.h.coerceIn(1, MAX_CELL), children = kids)
}

/** The item [o] as written, unchecked; [decodeItems] is what reads one from outside. */
private fun itemFromJson(o: JSONObject): Item = Item(
    o.getInt("page"), o.getInt("x"), o.getInt("y"), o.optInt("w", 1), o.optInt("h", 1),
    o.optString("pkg"), o.optString("cls"), o.optInt("widgetId", -1), o.optLong("user"), o.optString("shortcutId"),
    o.optString("action"), o.optBoolean("folder"),
    o.optJSONArray("children")?.let { a -> (0 until a.length()).mapNotNull { runCatching { itemFromJson(a.getJSONObject(it)) }.getOrNull() } } ?: emptyList(),
    o.optString("label"), o.optString("icon"), o.optString("swipe"))

/** The items of [a], each [wellFormed]: one that can't be read is dropped, not the whole list. */
fun decodeItems(a: JSONArray): Decoded<List<Item>> {
    val dropped = ArrayList<String>()
    val repaired = ArrayList<String>()
    val items = (0 until a.length()).mapNotNull { n ->
        val raw = runCatching { itemFromJson(a.getJSONObject(n)) }.getOrNull()
        val item = raw?.let(::wellFormed)
        if (item == null) dropped += "item $n" else if (item != raw) repaired += "item $n"
        item
    }
    return Decoded(items, dropped, repaired)
}

fun itemsToJson(items: List<Item>) = JSONArray().apply { items.forEach { put(it.toJson()) } }.toString()
/** The items of [s] (see [decodeItems]). Throws when [s] isn't a JSON array. */
fun itemsFromJson(s: String): MutableList<Item> = decodeItems(JSONArray(s)).value!!.toMutableList()

/**
 * A stored layout: its items, the subgrid factor their home positions are in (null: a version 1 layout, in the current
 * one), its page count and its default page.
 */
class StoredLayout(val items: List<Item>, val sub: Int?, val pages: Int = 1, val defaultPage: Int = 0)

/**
 * The `layout` preferences as stored ([items], [version], [sub] are whatever the file holds). Null when they aren't a
 * layout this build can read: not a JSON list, or written by a newer build (after a downgrade), whose items may mean
 * something else. A subgrid factor other than 1 or 2 (a hand edit) is taken as the current one.
 */
fun decodeLayout(items: Any?, version: Any?, sub: Any?, pages: Any? = null, defaultPage: Any? = null, readable: Int = LAYOUT_VERSION): Decoded<StoredLayout> {
    val v = (version as? String)?.toIntOrNull() ?: 1
    if (v > readable) return Decoded(null, listOf("layout version $v"))
    val list = (items as? String)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return Decoded(null, listOf("items"))
    val d = decodeItems(list)
    val s = (sub as? String)?.toIntOrNull()
    val goodSub = s?.takeIf { it == 1 || it == 2 }
    val (n, home) = pageSettings(pages, defaultPage)
    val repaired = listOfNotNull("sub $s".takeIf { s != null && goodSub == null }, "pages $pages".takeIf { pages != null && pages != n.toString() },
        "defaultPage $defaultPage".takeIf { defaultPage != null && defaultPage != home.toString() })
    return Decoded(StoredLayout(d.value!!, goodSub, n, home), d.dropped, d.repaired + repaired)
}

/**
 * A stored or restored page count and default page, held to their ranges: a default page past the last page is the last.
 * Missing (a fresh install, a hand-written backup) is one page, the first.
 */
fun pageSettings(pages: Any?, defaultPage: Any?): Pair<Int, Int> {
    val n = PAGES_CHOICE.intOf(pages as? String)
    return n to DEFAULT_PAGE_CHOICE.intOf(defaultPage as? String).coerceAtMost(n - 1)
}

/**
 * What home shows from the stored `layout` preferences [stored], in subgrid factor [sub]. [standIn] when they can't be
 * read (a bad restore, a hand edit, a newer build's after a downgrade): the newest readable of [backups] (or nothing)
 * stands in, and the stored text stays as it is until the user edits home, so a build that reads it brings it back.
 * [setAside] names the file the stored text is also copied to, with [why].
 */
class LoadedLayout(val layout: PageLayout, val standIn: Boolean, val setAside: String?, val why: List<String>, val repaired: List<String>) {
    val items get() = layout.items
}

fun loadLayout(stored: Map<String, Any?>, backups: Sequence<String>, sub: Int, readable: Int = LAYOUT_VERSION): LoadedLayout {
    fun StoredLayout.inSub() = PageLayout(layoutIn(items, this.sub, sub), pages, defaultPage)
    // Missing is an empty layout; a value of the wrong type (a bad restore) is unreadable, not empty.
    val d = decodeLayout(stored["items"] ?: "[]", stored["version"], stored["sub"], stored["pages"], stored["defaultPage"], readable)
    d.value?.let { l ->
        // An item this build can't read is dropped, and the next save loses it: the text as it was is kept first.
        return LoadedLayout(l.inSub(), false, "layout-unreadable.json".takeIf { d.dropped.isNotEmpty() }, d.dropped, d.repaired)
    }
    val newer = (stored["version"] as? String)?.toIntOrNull()?.takeIf { it > readable }
    // The backup's own page count and default page come with it.
    val l = backups.firstNotNullOfOrNull(::backupLayout)
    return LoadedLayout(l?.inSub() ?: PageLayout(emptyList(), 1, 0), true,
        if (newer != null) "layout-v$newer.json" else "layout-unreadable.json", d.dropped, emptyList())
}

enum class PageAdoption { MOVE, DROP, WAIT }

/**
 * What [Store.adoptPages] does with page keys found in `looks`, for a layout of stored [version]. Before version 3 they
 * are its page count and default page: MOVE. A version 3 layout has its own, and keys left in `looks` are stale (an
 * older build's, after a downgrade and back): DROP, unless a settings-only restore ([restored]) just put them there.
 * A layout this build can't read is a stand-in, and nothing automatic writes over it: WAIT until one it reads is stored.
 */
fun pageAdoption(version: Any?, readable: Boolean, restored: Boolean): PageAdoption = when {
    !readable -> PageAdoption.WAIT
    restored || ((version as? String)?.toIntOrNull() ?: 1) < 3 -> PageAdoption.MOVE
    else -> PageAdoption.DROP
}

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
 * 3 stores "pages" and "defaultPage" there too, in the same write as the items; before, they were settings in `looks`
 * ([Store.adoptPages]). An older build refuses a version 3 layout rather than read its pages from settings that are gone.
 */
const val LAYOUT_VERSION = 3

/** The `looks` keys that were the page count and default page before layout version 3. */
val PAGE_KEYS = listOf("pages", "defaultPage")

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
        if (fix.isNotEmpty()) { android.util.Log.w("Vulcan", "data: retyped ${fix.keys}"); edit().apply { fix.forEach { (k, v) -> putData(k, v) } }.commit() }
    }

    private val usage = context.getSharedPreferences("usage", Context.MODE_PRIVATE)
    init { adoptLaunches(); adoptPages() }

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
     * The saved layout, in subgrid factor [sub] (see [decodeLayout]). A layout that can't be read at all (a bad restore,
     * a hand edit) is kept in `files/layout-unreadable.json`, one from a newer build in `files/layout-v<version>.json`,
     * and the newest readable automatic backup is used, so the launcher never crash-loops and never saves over it unread.
     */
    fun load(sub: Int): PageLayout {
        val all = prefs.all
        val l = loadLayout(all, autoBackups.asSequence().map { f -> runCatching { f.readText() }.getOrDefault("") }, sub)
        if (l.repaired.isNotEmpty()) android.util.Log.w("Vulcan", "layout: repaired ${l.repaired}")
        l.setAside?.let { setAside(it, all["items"]?.toString() ?: "", l.why) }
        return l.layout
    }

    /**
     * Moves the page count and default page from `looks`, where they were before layout version 3, into the layout
     * ([pageAdoption]). The layout is committed before `looks` lets them go, so an interruption leaves them in both,
     * never neither. [restored]: a settings-only restore just put them in `looks`, and they replace the layout's.
     */
    fun adoptPages(restored: Boolean = false) {
        val looks = context.getSharedPreferences("looks", Context.MODE_PRIVATE)
        val old = PAGE_KEYS.associateWith { looks.getString(it, null) }.filterValues { it != null }
        if (old.isEmpty()) return
        val all = prefs.all
        when (pageAdoption(all["version"], layoutReadable, restored)) {
            PageAdoption.WAIT -> return
            PageAdoption.MOVE -> prefs.edit().apply { old.forEach { (k, v) -> putString(k, v) } }.putString("version", LAYOUT_VERSION.toString()).commit()
            PageAdoption.DROP -> {}
        }
        looks.edit().apply { old.keys.forEach(::remove) }.commit()
    }

    /**
     * False while home shows a stand-in for a stored layout it can't read (see [loadLayout]): what only tidies the layout
     * (a save on start, freeing widget IDs it doesn't hold, a daily backup) must wait, as the stored one may hold them.
     */
    val layoutReadable get() = prefs.all.let { decodeLayout(it["items"] ?: "[]", it["version"], it["sub"]).value != null }

    private fun setAside(name: String, raw: String, why: List<String>) {
        android.util.Log.w("Vulcan", "layout: set aside in $name, can't read $why")
        runCatching { File(context.filesDir, name).writeAtomically(raw) }.onFailure { android.util.Log.e("Vulcan", "layout: can't keep $name", it) }
    }

    /**
     * Saves [layout], its home positions in subgrid factor [sub], in one edit: the items, page count and default page
     * can't be stored out of step. Strings only, so backups round-trip them.
     */
    fun save(layout: PageLayout, sub: Int) = prefs.edit().apply { layoutFile(layout.items, sub, layout.pages, layout.defaultPage).forEach { (k, v) -> putString(k, v) } }.apply()

    /**
     * Writes today's automatic backup, keeping the two before it. A setup already kept moves to the front instead of
     * taking a second slot, so restoring back and forth between backups can't push out the last different one.
     */
    fun rotateAutoBackup() {
        // A stand-in isn't backed up: the backups are what it came from, and the stored text is kept in its set-aside file.
        if (!layoutReadable) return
        val old = autoBackups.mapNotNull { f -> runCatching { f.readText() to f.lastModified() }.getOrNull() }.toMap()
        val now = export()
        // Unchanged since the newest: it is dated now instead, or the daily check (MainActivity.onStop) would rerun on
        // every stop while a kept backup that moved to the front shows its old date.
        if (runCatching { autoBackups[0].readText() }.getOrNull() == now) { autoBackups[0].setLastModified(System.currentTimeMillis()); return }
        val texts = rotatedBackups(now, old.keys.toList(), autoBackups.size)
        // All written whole and synced before any replaces a file: a write cut short (a full disk) must not stand as a backup
        // or lose one. A kept backup keeps its date: the list in Settings tells them apart by it.
        val tmps = autoBackups.take(texts.size).map { File(it.path + ".tmp") }
        try {
            texts.zip(tmps).forEach { (text, tmp) -> tmp.writeSynced(text); old[text]?.let(tmp::setLastModified) }
            tmps.zip(autoBackups).forEach { (tmp, f) -> tmp.moveTo(f) }
        } catch (e: Exception) {
            throw IllegalStateException(context.getString(R.string.auto_backup_not_saved), e)
        } finally {
            tmps.forEach { it.delete() } // what was moved into place is no longer there; nothing half-written is left behind
        }
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
        // Through the decoder, as load reads it: a layout it would set aside isn't written into a backup as if it were this build's.
        val stored = prefs.all.let { requireNotNull(decodeLayout(it["items"] ?: "[]", it["version"], it["sub"], it["pages"], it["defaultPage"]).value) { "Unreadable layout" } }
        val sub = stored.sub ?: if (looks["subgrid"] == "on") 2 else 1
        // Each widget notes its provider, so a restore can offer it again once its ID is gone (an import or reset freed it).
        val awm = android.appwidget.AppWidgetManager.getInstance(context)
        val um = context.getSystemService(android.os.UserManager::class.java)
        val items = stored.items.map { i ->
            (if (i.isWidget) awm.getAppWidgetInfo(i.widgetId) else null)?.let { w ->
                i.copy(pkg = w.provider.packageName, cls = w.provider.className, user = um.getSerialNumberForUser(w.profile))
            } ?: i
        }
        // A backup keeps the launch counts where they always were, in data.
        val data = (data.all.filterKeys { it !in PASSING_STATE }.mapValues { it.value.toString() } +
            listOfNotNull(usage.getString("launches", null)?.let { "launches" to it })).toSortedMap()
        return readableBackup(looks, items, sub, data, apps.map { Triple(it.title, it.pkg, it.cls) to it.serial }, stored.pages, stored.defaultPage,
            context::getString).toString(2)
    }

    data class RestoreResult(val replacedLayout: Boolean, val rejected: List<String>)

    /**
     * Replaces the configuration from a backup, or merges the settings from an older Designer style file.
     * [cls] finds the main activity for apps written without one. Throws on an unrecognised file.
     */
    fun import(json: String, cls: (pkg: String, user: Long) -> String? = { _, _ -> null }): RestoreResult {
        // Read the whole file before writing anything, so a bad one leaves the current setup untouched.
        val backup = decodeBackup(json, cls)
        val (files, rejected) = backup.value ?: throw IllegalArgumentException(backup.dropped.firstOrNull() ?: context.getString(R.string.not_vulcan_backup))
        // ponytail: three commits, not one transaction; an I/O failure between them can mix old and new files (each
        // failed commit throws, and the auto-backup just written restores the rest). Upgrade if a mixed restore is ever reported.
        val replacedLayout = "layout" in files
        // What's about to be replaced stays restorable from Settings. It throws, and nothing is replaced, if it can't be saved.
        if (replacedLayout) rotateAutoBackup()
        for ((name, values) in files) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().apply {
            if (replacedLayout) clear()
            // data keeps its flags as booleans; the backup has them as "true"/"false"
            values.forEach { (k, v) -> if (restoredKey(name, k)) { if (name == "data") putData(k, dataValue(k, v)) else putString(k, v) } }
        }.commit().let { ok -> check(ok) { context.getString(R.string.restore_not_saved) } }
        if (replacedLayout) usage.edit().clear().commit()
        adoptLaunches()
        // A settings-only file (a style file) still sets them, now on the current layout.
        adoptPages(restored = true)
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
    "home.pages: how many home pages there are (1 to 9); home.defaultPage: the one the Home button goes to, 0 for the first. settings repeats both for older versions of Vulcan; home's win.",
    "home.items: everything on the home pages and the dock. page 0 is the first home page; the dock is page -1 (a second dock page is -2). x and y count cells from the top left; w and h are the size in cells (default 1). The grid is settings.cols by settings.rows; the dock is one row of settings.dockCols. When home.sub is 2, home positions and sizes count half cells. Don't overlap items; one that falls off the grid moves to the first free cell.",
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

/** data keys read with getInt, which throws on text too. */
val DATA_INTS = setOf(WORK_AT)

/**
 * Value [v] of data key [key], as stored or as a backup has it (always text), in the type its reader takes: a flag
 * ([DATA_FLAGS]) is a boolean, true only for true or "true"; an int ([DATA_INTS]) an int, null (the key goes) when it isn't
 * a number; anything else text. The one decoder for data's types, used on every start and by every restore.
 */
fun dataValue(key: String, v: Any?): Any? = when {
    v == null -> null
    key in DATA_FLAGS -> v == true || v == "true"
    key in DATA_INTS -> v as? Int ?: v.toString().toIntOrNull()
    else -> v as? String ?: v.toString()
}

/** Stores [v], typed by [dataValue]; null removes [key]. */
fun android.content.SharedPreferences.Editor.putData(key: String, v: Any?) {
    when (v) { null -> remove(key); is Boolean -> putBoolean(key, v); is Int -> putInt(key, v); else -> putString(key, v.toString()) }
}

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
 * data's values ([all]) stored as the wrong type, each as [dataValue] types it (null: removed). A flag kept as text (a
 * restore before 0.31) made getBoolean throw, any other key kept as a boolean (a restore before 0.35 of a hand-edited
 * "groups": false) made getString throw, on every start.
 */
fun dataTypeFixes(all: Map<String, Any?>): Map<String, Any?> = all.filter { (k, v) -> dataValue(k, v) != v }.mapValues { (k, v) -> dataValue(k, v) }

/**
 * Writes [text] to this file whole or not at all: into "<name>.tmp", synced to disk, then renamed over this file. Throws
 * when any step fails, leaving the file as it was and no temp file behind.
 */
fun File.writeAtomically(text: String) = File("$path.tmp").let { tmp -> tmp.writeSynced(text); tmp.moveTo(this) }

/** Writes [text] to this file and syncs it to disk; a failed write deletes it and throws. */
fun File.writeSynced(text: String) = try {
    java.io.FileOutputStream(this).use { it.write(text.toByteArray()); it.fd.sync() }
} catch (e: Exception) { delete(); throw e }

/** Renames this file to [to], replacing it; a failure deletes this file and throws. */
fun File.moveTo(to: File) { if (!renameTo(to)) { delete(); throw java.io.IOException("can't replace ${to.name}") } }

/** The automatic backups after saving [now] in front of [kept] (newest first): one already kept moves up, not into a second slot. */
fun rotatedBackups(now: String, kept: List<String>, slots: Int) = (listOf(now) + kept).distinct().take(slots)

fun readableBackup(looks: Map<String, String>, items: List<Item>, sub: Int, data: Map<String, String>,
                   apps: List<Pair<Triple<String, String, String>, Long>>, pages: Int = 1, defaultPage: Int = 0,
                   text: (Int) -> String): JSONObject = JSONObject()
    .put("format", "vulcan-backup-2")
    .put("howTo", JSONArray(BACKUP_HOW_TO))
    // The page settings too: a build from before layout version 3 restores them from here.
    .put("settings", JSONObject(LOOKS.associate { it.key to it.default } + (looks - DEVICE_KEYS - PAGE_KEYS) +
        mapOf("pages" to pages.toString(), "defaultPage" to defaultPage.toString())))
    .put("home", JSONObject().put("sub", sub).put("pages", pages).put("defaultPage", defaultPage).put("items", JSONArray(items.map { it.toReadable() })))
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

/** The preference files a backup restores to (file → key → value), and the "key=value" settings it leaves out. */
typealias BackupFiles = Pair<Map<String, Map<String, String>>, List<String>>

/**
 * Backup [text] as the preference files it restores to and the "key=value" settings it leaves out, whichever format it
 * is in (see [readableBackupFiles], [legacyBackupFiles]), the one decoder for backup files. Never throws: the value is
 * null for a file this build can't restore, with why in dropped (empty: not a Vulcan backup at all).
 */
fun decodeBackup(text: String, cls: (pkg: String, user: Long) -> String? = { _, _ -> null }): Decoded<BackupFiles> =
    runCatching {
        val root = JSONObject(text)
        when (root.optString("format")) {
            "vulcan-backup-2" -> Decoded(readableBackupFiles(root, cls))
            "vulcan-backup-1" -> Decoded(legacyBackupFiles(root))
            else -> Decoded<BackupFiles>(null)
        }
    }.getOrElse { Decoded(null, listOf(it.message ?: it.toString())) }

/** The layout held in backup [text], whichever format it is in, or null when it holds none this build can read. */
fun backupLayout(text: String): StoredLayout? =
    decodeBackup(text).value?.first?.get("layout")?.let { decodeLayout(it["items"], it["version"], it["sub"], it["pages"], it["defaultPage"]).value }

/** The `layout` preferences for [items], wellFormed and in subgrid factor [sub], with its page count and default page, as this build writes them. */
fun layoutFile(items: List<Item>, sub: Int, pages: Int, defaultPage: Int) = mapOf("items" to itemsToJson(items), "version" to LAYOUT_VERSION.toString(),
    "sub" to sub.toString(), "pages" to pages.toString(), "defaultPage" to defaultPage.toString())

/** The setting [key] is, the page settings included (they are stored with the layout, not in `looks`). */
fun choiceOf(key: String) = LOOKS.firstOrNull { it.key == key } ?: PAGE_CHOICES.firstOrNull { it.key == key }

/**
 * The preference files a format 1 backup restores to, and the "key=value" settings left out: its settings are held to
 * what a format 2 one accepts, so a hand-edited "pages": "100000" can't crash home on every start.
 */
fun legacyBackupFiles(root: JSONObject): BackupFiles {
    val rejected = ArrayList<String>()
    // Without a layout it's settings to merge: only the files it has, or import() would clear the ones it lacks.
    val files = Store.FILES.filter { root.has("layout") || root.has(it) }.associateWith { name ->
        val o = root.optJSONObject(name) ?: JSONObject()
        o.keys().asSequence().associateWith { o.get(it).toString() }.filter { (k, v) ->
            (name != "looks" || choiceOf(k)?.accepts(v) != false).also { if (!it) rejected += "$k=$v" }
        }
    }
    // Its layout is read now, as load would read it, and stored as this build writes one; a newer or unreadable one is refused.
    val looks = files["looks"].orEmpty()
    val layout = files["layout"]?.let { l ->
        // A backup from before version 2 is in the subgrid factor of the settings saved with it, and one from before 3
        // has its page count and default page in them.
        val stored = requireNotNull(decodeLayout(l["items"], l["version"], l["sub"], l["pages"] ?: looks["pages"],
            l["defaultPage"] ?: looks["defaultPage"]).value) { "Unreadable layout" }
        layoutFile(stored.items, stored.sub ?: if (looks["subgrid"] == "on") 2 else 1, stored.pages, stored.defaultPage)
    }
    // With a layout they went into it; without, they stay for Store.adoptPages to put on the current one.
    if (layout == null) return files to rejected
    return files + ("layout" to layout) + (if ("looks" in files) mapOf("looks" to looks - PAGE_KEYS) else emptyMap()) to rejected
}

/**
 * The preference files a readable backup restores to, and the "key=value" settings left out because the value isn't
 * allowed. A legacy Designer style file only supplies looks. [cls] fills in the main activity for apps written with
 * only a package.
 */
fun readableBackupFiles(root: JSONObject, cls: (pkg: String, user: Long) -> String?): BackupFiles {
    val rejected = ArrayList<String>()
    val looks = HashMap<String, String>()
    root.optJSONObject("settings")?.let { s ->
        for (k in s.keys()) {
            val v = when (val raw = s.get(k)) { true -> "on"; false -> "off"; else -> raw.toString() }
            if (choiceOf(k)?.accepts(v) != false) looks[k] = v else rejected += "$k=$v"
        }
    }
    if (!root.has("home") && !root.has("data")) {
        require(root.optJSONObject("settings") != null) { "Missing settings" }
        return mapOf("looks" to looks) to rejected
    }
    // Restoring replaces the layout: a home section that's missing, misspelt or the wrong shape must not read as an empty one.
    val home = requireNotNull(root.optJSONObject("home")?.takeIf { it.optJSONArray("items") != null }) { "Missing home items" }
    fun fill(i: Item): Item = i.copy(cls = if (i.isApp && i.cls.isEmpty()) cls(i.pkg, i.user).orEmpty() else i.cls, children = i.children.map(::fill))
    val items = decodeItems(home.getJSONArray("items")).value!!.map(::fill)
    // Subgrid factors are 1 and 2: any other (a hand edit) is the settings' own.
    val sub = home.optInt("sub").takeIf { it == 1 || it == 2 } ?: if (looks["subgrid"] == "on") 2 else 1
    val data = root.optJSONObject("data")?.let { d -> d.keys().asSequence().associateWith { d.get(it).toString() } } ?: emptyMap()
    // home's page settings win over settings' (a backup from before layout version 3 has only those), each read as the
    // layout decoder reads them: out of range is held to it, missing is one page, the first.
    fun page(k: String) = home.opt(k)?.takeIf { it != JSONObject.NULL }?.toString() ?: looks[k]
    val (pages, defaultPage) = pageSettings(page("pages"), page("defaultPage"))
    return mapOf("layout" to layoutFile(items, sub, pages, defaultPage),
        // A phone's own file carries these markers; one written by hand doesn't, and without them the settings it left
        // out would be filled with the defaults of an install from before 0.16 (Looks.adoptDefaults).
        "looks" to (mapOf("defaults" to "nova", "defaults032" to "on") + looks - PAGE_KEYS), "data" to data) to rejected
}
