package dev.vulcanlauncher

import org.json.JSONArray
import org.json.JSONObject

// Drawer tabs and folders as data: reading, ordering and filling them, and the smart-folder categories.

/**
 * A drawer tab or drawer folder: [type] is "tab" or "folder"; [apps] are app keys; [color] a tab's own "#RRGGBB", null for
 * the theme's. A [hidden] tab is left out of the drawer as if it weren't there, its apps back in All apps; it keeps its apps.
 */
data class Group(val name: String, val type: String, val apps: List<String>, val color: String? = null, val hidden: Boolean = false)

/** The groups in [s]. A restored or hand-edited file can hold anything: what isn't a group is skipped, never thrown. */
fun groupsFromJson(s: String): List<Group> = (runCatching { JSONArray(s) }.getOrNull() ?: JSONArray()).let { a ->
    (0 until a.length()).mapNotNull {
        val o = a.optJSONObject(it) ?: return@mapNotNull null
        val name = o.opt("name") as? String ?: return@mapNotNull null
        val type = o.opt("type") as? String ?: return@mapNotNull null
        val apps = o.optJSONArray("apps") ?: JSONArray()
        val color = (o.opt("color") as? String)?.takeIf { Regex("#[0-9a-fA-F]{6}").matches(it) }
        Group(name, type, (0 until apps.length()).mapNotNull { i -> apps.opt(i) as? String }, color, o.optBoolean("hidden"))
    }
}

fun groupsToJson(groups: List<Group>) = JSONArray().apply {
    groups.forEach { put(JSONObject().put("name", it.name).put("type", it.type).put("apps", JSONArray(it.apps)).apply { it.color?.let { c -> put("color", c) }; if (it.hidden) put("hidden", true) }) }
}.toString()

/**
 * App keys shown in a drawer tab, in [ordered] order, or a tab group's own order when [manual]. [tab] is "" (all apps),
 * "work", "private", or a tab group's name.
 * [hidden]: the keys to leave out everywhere ([unlisted]). The main tab also leaves out work apps when they have their own tab, Private Space apps (always in their
 * own tab), and grouped apps unless [keepInAll].
 */
fun tabApps(ordered: List<String>, groups: List<Group>, tab: String, hidden: Set<String>,
            keepInAll: Boolean, work: Set<String>, workTab: Boolean, private: Set<String> = emptySet(), manual: Boolean = false): List<String> {
    val shown = ordered.filter { it !in hidden }
    return when (tab) {
        "" -> {
            val grouped = if (keepInAll) emptySet() else groups.flatMap { it.apps }.toSet()
            shown.filter { it !in grouped && !(workTab && it in work) && it !in private }
        }
        "work" -> shown.filter { it in work }
        "private" -> shown.filter { it in private }
        else -> groups.firstOrNull { it.name == tab && it.type == "tab" }?.apps?.let { g ->
            if (manual) shown.toSet().let { s -> g.distinct().filter { it in s } } else g.toSet().let { s -> shown.filter { it in s } }
        } ?: emptyList()
    }
}

/** [order] after arranging the apps [shown] as [arranged]: they take the front, the rest keep their order after them. */
fun arranged(order: List<String>, arranged: List<String>) = arranged + (order - arranged.toSet())

/** The row above the tabs: the [pinned] favourites when there are any, else the most [launches] apps; [shown] filters. */
fun frequentApps(pinned: List<String>, launches: Map<String, Int>, shown: (String) -> Boolean, cols: Int): List<String> =
    pinned.filter(shown).ifEmpty { launches.filter { it.value > 0 && shown(it.key) }.entries.sortedByDescending { it.value }.map { it.key } }.take(cols)

/** For each app key, the groups it's in, leaving out [except] (the group being edited). */
fun groupsOfApps(groups: List<Group>, except: String?): Map<String, List<String>> =
    groups.filter { it.name != except }.flatMap { g -> g.apps.map { it to g.name } }.groupBy({ it.first }, { it.second })

// ---- Smart folders ----

/** Smart-folder categories Android's own list (ApplicationInfo.CATEGORY_*, 0..8) lacks. */
const val CAT_FINANCE = 100
const val CAT_SHOPPING = 101
const val CAT_HEALTH = 102
const val CAT_TOOLS = 103

/**
 * Words in an app's package name or label that place it when its developer declared no category, as most don't.
 * First match wins, so the more specific come first: "Amazon Music" is music, "Prime Video" video, not shopping.
 */
private val CATEGORY_WORDS: List<Pair<Int, Set<String>>> = listOf(
    android.content.pm.ApplicationInfo.CATEGORY_GAME to setOf("game", "games", "puzzle", "chess", "solitaire", "sudoku",
        "crossword", "casino", "poker", "arcade", "racing", "supercell", "rovio", "miniclip", "gameloft", "zynga", "ludo"),
    android.content.pm.ApplicationInfo.CATEGORY_AUDIO to setOf("music", "audio", "radio", "podcast", "podcasts", "spotify",
        "soundcloud", "audible", "saavn", "jiosaavn", "gaana", "deezer", "tidal", "shazam", "pandora", "audiobooks"),
    android.content.pm.ApplicationInfo.CATEGORY_VIDEO to setOf("video", "videos", "tv", "movies", "youtube", "netflix",
        "primevideo", "hotstar", "disney", "hulu", "twitch", "vlc", "mxplayer", "plex", "jellyfin", "kodi", "stremio", "crunchyroll"),
    android.content.pm.ApplicationInfo.CATEGORY_IMAGE to setOf("camera", "photo", "photos", "gallery", "image", "images",
        "pictures", "snapseed", "lightroom", "picsart", "pinterest", "vsco", "scanner", "lens"),
    CAT_FINANCE to setOf("bank", "banking", "pay", "wallet", "finance", "money", "upi", "paytm", "phonepe", "paypal",
        "venmo", "revolut", "wise", "crypto", "coinbase", "binance", "invest", "trading", "stocks", "zerodha", "groww",
        "kite", "gpay", "credit", "loan", "insurance", "splitwise", "budget", "expense", "expenses"),
    CAT_SHOPPING to setOf("shopping", "amazon", "flipkart", "ebay", "myntra", "meesho", "etsy", "aliexpress", "temu",
        "shein", "ikea", "nykaa", "ajio", "swiggy", "zomato", "food", "grocery", "groceries", "instamart", "zepto",
        "blinkit", "bigbasket", "doordash", "ubereats", "deliveroo", "dunzo", "walmart", "target"),
    CAT_HEALTH to setOf("health", "fit", "fitness", "workout", "gym", "running", "strava", "steps", "sleep",
        "meditation", "headspace", "yoga", "period", "medical", "doctor", "pharmacy", "practo", "cult", "wearable", "shealth"),
    android.content.pm.ApplicationInfo.CATEGORY_MAPS to setOf("maps", "map", "navigation", "gps", "transit", "metro",
        "rail", "train", "trains", "bus", "uber", "ola", "lyft", "rapido", "waze", "citymapper", "flight", "flights",
        "airline", "airlines", "booking", "airbnb", "travel", "trip", "irctc", "makemytrip", "goibibo", "redbus", "skyscanner"),
    android.content.pm.ApplicationInfo.CATEGORY_SOCIAL to setOf("phone", "dialer", "chat", "messenger", "messages", "messaging", "sms",
        "whatsapp", "telegram", "signal", "discord", "facebook", "instagram", "twitter", "threads", "snapchat", "reddit",
        "linkedin", "tiktok", "mastodon", "bluesky", "wechat", "viber", "social", "dating", "tinder", "bumble", "hinge", "contacts"),
    android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY to setOf("mail", "email", "gmail", "outlook", "calendar",
        "notes", "note", "docs", "drive", "office", "word", "excel", "sheets", "slides", "powerpoint", "notion", "todo",
        "todoist", "tasks", "keep", "onenote", "evernote", "dropbox", "onedrive", "pdf", "translate", "authenticator",
        "password", "passwords", "bitwarden", "keepass", "slack", "teams", "zoom", "meet", "skype", "obsidian", "trello"),
    android.content.pm.ApplicationInfo.CATEGORY_NEWS to setOf("news", "weather", "magazine", "magazines", "rss", "feedly",
        "inshorts", "kindle", "books", "comics"),
    CAT_TOOLS to setOf("calculator", "clock", "alarm", "files", "file", "settings", "flashlight", "torch", "recorder",
        "compass", "keyboard", "launcher", "backup", "cleaner", "battery", "browser", "chrome", "firefox", "brave",
        "opera", "duckduckgo", "vpn", "termux", "tools", "utilities", "store", "vending", "finder", "remote"),
)

/**
 * The smart-folder category for one app, or null to leave it out: what its developer declared, else a game flag, else
 * what its intent filters say it is ([role], e.g. a camera), else words in its [pkg] or [label], else Tools for a
 * system app nothing else placed.
 */
fun smartCategory(declared: Int, game: Boolean, role: Int?, pkg: String, label: String, system: Boolean): Int? {
    if (declared != android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED) return declared
    if (game) return android.content.pm.ApplicationInfo.CATEGORY_GAME
    if (role != null) return role
    val words = "$pkg $label".lowercase().split(Regex("[^\\p{L}\\p{N}]+")).toSet()
    CATEGORY_WORDS.firstOrNull { (_, w) -> w.any { it in words } }?.let { return it.first }
    return if (system) CAT_TOOLS else null
}

/**
 * [groups] with smart folders added: [byName] maps each category's folder name to its apps' keys. An app already in
 * a drawer folder stays where it is. An existing folder of that name takes the new apps; a new one needs two.
 * Returns the groups, the folders made and the apps added to existing ones.
 */
fun smartGroups(groups: List<Group>, byName: Map<String, List<String>>): Triple<List<Group>, Int, Int> {
    val foldered = groups.filter { it.type == "folder" }.flatMap { it.apps }.toSet()
    var made = 0
    var added = 0
    val out = groups.toMutableList()
    for ((name, keys) in byName) {
        val fresh = keys.filter { it !in foldered }.distinct()
        val i = out.indexOfFirst { it.name == name && it.type == "folder" }
        when {
            i >= 0 && fresh.isNotEmpty() -> { out[i] = out[i].copy(apps = out[i].apps + fresh); added += fresh.size }
            i < 0 && out.none { it.name == name } && fresh.size >= 2 -> { out += Group(name, "folder", fresh); made++ }
        }
    }
    return Triple(out, made, added)
}

/** data key: All apps is left out of the drawer's tabs. */
const val ALL_HIDDEN = "hideAllTab"

/** Work apps have their own tab unless it's hidden. Before 0.32 that was the "workTab" setting; its value still counts until the eye is used. */
fun VulcanActivity.workTabShown() = !store.data.getBoolean(WORK_HIDDEN, looks.raw("workTab") == "off")
const val WORK_HIDDEN = "hideWorkTab"
/** data key: how many of the user's tabs (hidden ones too) come before the Work profile tab; 0 puts it right after All apps. */
const val WORK_AT = "workTabAt"

/** A new group's [name] can't be used: another group has it, or it's the key of the Work or Private tab. */
fun nameTaken(groups: List<Group>, name: String) = name == "work" || name == "private" || groups.any { it.name == name }

/** [WORK_AT] ([at]: how many tabs come before Work) after the tabs changed from [old] to [new]: tabs taken out before Work pull it forward. */
fun workAtAfter(old: List<String>, new: List<String>, at: Int) =
    if (new.size >= old.size) at else at - old.take(at.coerceAtLeast(0)).count { it !in new }
/** [WORK_AT]'s value. Stored as an int, but a restored backup brings every value back as text, and getInt throws on text. */
fun android.content.SharedPreferences.workAt() = runCatching { getInt(WORK_AT, 0) }.getOrElse { getString(WORK_AT, null)?.toIntOrNull() ?: 0 }
