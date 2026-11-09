package dev.vulcanlauncher

import java.text.Collator
import java.text.Normalizer
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Launcher actions, used by gestures, the home button, icon swipe actions and action shortcuts.
 * Parameterised forms: "page:N" (0-based page) and "app:pkg/cls/user".
 */
val ACTIONS = listOf("none", "drawer", "now", "notifications", "quicksettings", "togglebar", "recents", "defaultpage", "previews",
    "toggledock", "search", "voice", "assistant", "lock", "power", "settings", "widgets", "wallpaper", "torch", "locklayout", "work")
val ACTION_NAMES = listOf(
    R.string.action_none, R.string.action_drawer, R.string.action_now, R.string.action_notifications,
    R.string.action_quicksettings, R.string.action_togglebar, R.string.action_recents, R.string.action_defaultpage,
    R.string.action_previews, R.string.action_toggledock, R.string.action_search, R.string.action_voice,
    R.string.action_assistant, R.string.action_lock, R.string.action_power, R.string.action_settings,
    R.string.action_widgets, R.string.action_wallpaper, R.string.action_torch, R.string.action_locklayout, R.string.action_work,
)

/** Display name for an action code; [text] reads a string resource with format arguments, [appLabel] resolves "app:" codes. */
fun actionName(code: String, text: (Int, Array<Any>) -> String, appLabel: (String) -> String? = { null }): String = when {
    code.startsWith("page:") -> text(R.string.action_jump_to_page, arrayOf((code.removePrefix("page:").toIntOrNull() ?: 0) + 1))
    code == "folder" -> text(R.string.action_open_folder, arrayOf())
    code.startsWith("app:") -> text(R.string.action_open_app, arrayOf(appLabel(code.removePrefix("app:")) ?: text(R.string.action_app_fallback, arrayOf())))
    else -> text(ACTION_NAMES.getOrElse(ACTIONS.indexOf(code)) { ACTION_NAMES[0] }, arrayOf())
}

/**
 * Whether a folder's swipe opens it (and a tap then opens its first app). Its own swipe action wins: "folder" opens it,
 * any other action replaces it; "" follows the global Swipe to open setting.
 */
fun folderSwipeOpens(swipe: String, global: Boolean) = swipe == "folder" || swipe.isEmpty() && global

fun android.content.Context.actionName(code: String, appLabel: (String) -> String? = { null }) =
    actionName(code, { id, args -> getString(id, *args) }, appLabel)

val GESTURE_KEYS = listOf("g_swipeUp", "g_swipeDown", "g_swipeUp2", "g_swipeDown2", "g_swipeLeft", "g_swipeRight", "g_doubleTap",
    "g_doubleTapUp", "g_doubleTapDown", "g_pinchIn", "g_pinchOut", "g_rotateCw", "g_rotateCcw")
val GESTURE_NAMES = listOf(
    R.string.gesture_swipeup, R.string.gesture_swipedown, R.string.gesture_swipeup2, R.string.gesture_swipedown2,
    R.string.gesture_swipeleft, R.string.gesture_swiperight, R.string.gesture_doubletap, R.string.gesture_doubletapup,
    R.string.gesture_doubletapdown, R.string.gesture_pinchin, R.string.gesture_pinchout, R.string.gesture_rotatecw,
    R.string.gesture_rotateccw,
)
val GESTURE_DEFAULTS = listOf("drawer", "notifications", "none", "quicksettings", "none", "now", "none", "none", "none",
    "previews", "none", "none", "none")

/**
 * Classifies a finished two-finger gesture from the start and end positions of both fingers,
 * returning a gesture name without the "g_" prefix, or null when nothing clear happened.
 * Screen coordinates (y grows downward), so a positive angle change is clockwise.
 */
fun twoFingerGesture(
    ax0: Float, ay0: Float, bx0: Float, by0: Float,
    ax1: Float, ay1: Float, bx1: Float, by1: Float, minMove: Float,
): String? {
    val d0 = hypot(bx0 - ax0, by0 - ay0)
    val d1 = hypot(bx1 - ax1, by1 - ay1)
    var turn = (atan2(by1 - ay1, bx1 - ax1) - atan2(by0 - ay0, bx0 - ax0)) * 180 / PI.toFloat()
    if (turn > 180) turn -= 360
    if (turn < -180) turn += 360
    val dy = (ay1 + by1 - ay0 - by0) / 2
    return when {
        abs(turn) > 35 -> if (turn > 0) "rotateCw" else "rotateCcw"
        d1 < d0 * 0.7f -> "pinchIn"
        d1 > d0 * 1.4f -> "pinchOut"
        dy < -minMove -> "swipeUp2"
        dy > minMove -> "swipeDown2"
        else -> null
    }
}

val NIGHT_MODES = listOf("off", "on", "system", "schedule", "sun")
val NIGHT_MODE_NAMES = listOf(
    R.string.night_off, R.string.night_on, R.string.night_system, R.string.night_schedule,
    R.string.night_sun,
)

/**
 * Local sunrise and sunset in hours (e.g. 6.5 = 06:30) for a day of the year, computed offline with the NOAA
 * approximation. Null during polar day or night.
 */
fun sunTimes(lat: Double, lon: Double, dayOfYear: Int, utcOffsetHours: Double): Pair<Double, Double>? {
    val g = 2 * Math.PI / 365 * (dayOfYear - 1 + 0.5)
    val eq = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g) - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g))
    val decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g) +
        0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g)
    val la = Math.toRadians(lat)
    val cosHa = Math.cos(Math.toRadians(90.833)) / (Math.cos(la) * Math.cos(decl)) - Math.tan(la) * Math.tan(decl)
    if (cosHa < -1 || cosHa > 1) return null
    val ha = Math.toDegrees(Math.acos(cosHa))
    fun local(min: Double) = ((min / 60 + utcOffsetHours) % 24 + 24) % 24
    return local(720 - 4 * (lon + ha) - eq) to local(720 - 4 * (lon - ha) - eq)
}

/** Night between sunset and sunrise; [hour] is fractional local time. */
fun isNightSun(hour: Double, sun: Pair<Double, Double>) = hour < sun.first || hour >= sun.second

val SEARCH_ENGINES = listOf("browser", "google", "duckduckgo", "bing", "startpage", "ecosia", "brave")
val SEARCH_ENGINE_NAMES = listOf(
    R.string.engine_browser, R.string.engine_google, R.string.engine_duckduckgo, R.string.engine_bing,
    R.string.engine_startpage, R.string.engine_ecosia, R.string.engine_brave,
)

/** URL for a web search, or null for "hand the query to the default web search app". */
fun searchUrl(engine: String, q: String): String? {
    val e = java.net.URLEncoder.encode(q, "UTF-8")
    return when (engine) {
        "google" -> "https://www.google.com/search?q=$e"
        "duckduckgo" -> "https://duckduckgo.com/?q=$e"
        "bing" -> "https://www.bing.com/search?q=$e"
        "startpage" -> "https://www.startpage.com/do/search?q=$e"
        "ecosia" -> "https://www.ecosia.org/search?q=$e"
        "brave" -> "https://search.brave.com/search?q=$e"
        else -> null
    }
}

/** Is it night? A schedule from [start] to [end] (hours) may wrap past midnight. */
fun isNight(mode: String, hour: Int, start: Int, end: Int, systemNight: Boolean) = when (mode) {
    "on" -> true
    "system" -> systemNight
    "schedule" -> if (start <= end) hour in start until end else hour >= start || hour < end
    else -> false
}

/** "manual": the order the user arranged (data.drawerOrder for the drawer, each tab's own apps list for a tab). */
val SORTS = listOf("az", "za", "installed", "used", "manual")
val SORT_NAMES = listOf(
    R.string.sort_az, R.string.sort_za, R.string.sort_installed, R.string.sort_used, R.string.sort_manual,
)

/** One drawer entry's sort facts. [rank]: its place in the arranged order; apps never arranged go after, A–Z. */
class SortKey(val label: String, val installed: Long, val launches: Int, val rank: Int = Int.MAX_VALUE)

/**
 * Label order in the system language: Ä sorts with A in German, Ё with Е in Russian; case and accents are ignored.
 * Labels in the language's own script come first (Russian: Банк before Catima).
 */
fun labelOrder(locale: Locale = Locale.getDefault()): Comparator<String> {
    val c = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
    fun script(s: String) = s.firstOrNull { it.isLetter() }?.let { Character.UnicodeScript.of(it.code) }
    val own = script(locale.getDisplayLanguage(locale))
    fun foreign(s: String) = own != null && script(s).let { it != null && it != own }
    return compareBy<String> { foreign(it) }.thenComparator { x, y -> c.compare(x, y) }
}

/** The drawer's index letter for a label: its first letter without accents (Ä → A), or "#". */
fun indexLetter(label: String): String {
    val c = label.firstOrNull()?.takeIf { it.isLetter() } ?: return "#"
    return Normalizer.normalize(c.toString(), Normalizer.Form.NFD).first().uppercase()
}

fun drawerComparator(sort: String, locale: Locale = Locale.getDefault()): Comparator<SortKey> {
    val byLabel = compareBy(labelOrder(locale)) { k: SortKey -> k.label }
    return when (sort) {
        "za" -> byLabel.reversed()
        "installed" -> compareByDescending<SortKey> { it.installed }.then(byLabel)
        "used" -> compareByDescending<SortKey> { it.launches }.then(byLabel)
        "manual" -> compareBy<SortKey> { it.rank }.then(byLabel)
        else -> byLabel
    }
}
