package dev.vulcanlauncher

import android.content.Context

/**
 * One setting: stored value strings in [values], shown to the user as the string resources [names], or as the
 * untranslated [shown] (numbers, sizes) when there are none. [title] is a string resource too.
 * Gesture settings have [action] set and are picked with the action picker instead.
 */
class Choice(
    val group: String, val key: String, val title: Int, val default: String,
    val values: List<String>, val names: List<Int> = emptyList(), val shown: List<String> = values, val action: Boolean = false,
    val info: Int = 0, // a toggle's summary line
)

/** What value [i] is called on screen; [text] reads a string resource. */
fun Choice.name(i: Int, text: (Int) -> String): String = names.getOrNull(i)?.let(text) ?: shown.getOrElse(i) { "" }
fun Context.choiceName(c: Choice, i: Int) = c.name(i, ::getString)

private fun range(from: Int, to: Int, step: Int = 1) = (from..to step step).map { it.toString() }
private fun onOff(group: String, key: String, title: Int, default: Boolean, info: Int = 0) =
    Choice(group, key, title, if (default) "on" else "off", listOf("on", "off"), listOf(R.string.on, R.string.off), info = info)
private fun sizes(group: String, key: String, title: Int, default: String = "48") =
    Choice(group, key, title, default, range(32, 72, 4), shown = range(32, 72, 4).map { "$it dp" })
/** As Nova, each icon layout has its own label size; "home" follows the home screen's. */
private fun labelSize(group: String, key: String) = range(8, 24).let {
    Choice(group, key, R.string.set_labelSize, "home", listOf("home") + it, listOf(R.string.labelSize_home), shown = listOf("") + it.map { s -> "$s sp" })
}
private fun percent(group: String, key: String, title: Int, default: String) =
    Choice(group, key, title, default, range(0, 100, 10), shown = range(0, 100, 10).map { "$it%" })

val COLOR_VALUES = listOf("#FFFFFF", "#000000", "#9E9E9E", "#212121", "#E53935", "#FB8C00", "#FDD835", "#43A047", "#00897B", "#1E88E5", "#3949AB", "#8E24AA", "#D81B60")
val COLOR_NAMES = listOf(
    R.string.color_white, R.string.color_black, R.string.color_grey, R.string.color_charcoal,
    R.string.color_red, R.string.color_orange, R.string.color_yellow, R.string.color_green,
    R.string.color_teal, R.string.color_blue, R.string.color_indigo, R.string.color_purple,
    R.string.color_pink,
)
/** With [themed], "system" (the wallpaper-based Material colour) is offered first. */
private fun color(group: String, key: String, title: Int, default: String, themed: Boolean = false) =
    if (themed) Choice(group, key, title, default, listOf("system") + COLOR_VALUES, listOf(R.string.color_system) + COLOR_NAMES)
    else Choice(group, key, title, default, COLOR_VALUES, COLOR_NAMES)

const val HOME = "Home screen"
const val DOCK_GROUP = "Dock"
const val DRAWER = "App drawer"
const val FOLDERS = "Folders"
const val LOOK = "Look & feel"
const val NIGHT = "Night mode"
const val BADGES = "Notification badges"
const val GESTURES = "Gestures & inputs"
const val NOW = "Now page"
/** Settings groups are identified by their English names (kept in saved state and backups); these are their titles. */
val GROUP_TITLES = mapOf(HOME to R.string.group_home, DOCK_GROUP to R.string.group_dock, DRAWER to R.string.group_drawer,
    FOLDERS to R.string.group_folders, LOOK to R.string.group_look, NIGHT to R.string.group_night, BADGES to R.string.group_badges,
    GESTURES to R.string.group_gestures, NOW to R.string.group_now)

val LOOKS = listOf(
    Choice(HOME, "cols", R.string.set_cols, "5", range(3, 12)),
    Choice(HOME, "rows", R.string.set_rows, "6", range(3, 12)),
    Choice(HOME, "pages", R.string.set_pages, "1", range(1, 9)),
    Choice(HOME, "defaultPage", R.string.set_defaultPage, "0", range(0, 8), shown = range(1, 9)),
    onOff(HOME, "subgrid", R.string.set_subgrid, false, R.string.set_subgrid_info),
    sizes(HOME, "iconSize", R.string.set_iconSize, "64"),
    onOff(HOME, "labels", R.string.set_labels, true),
    Choice(HOME, "labelSize", R.string.set_labelSize, "12", range(8, 24), shown = range(8, 24).map { "$it sp" }),
    color(HOME, "labelColor", R.string.set_labelColor, "#FFFFFF"),
    Choice(HOME, "labelFont", R.string.set_labelFont, "system", listOf("system", "sans-serif", "sans-serif-medium", "sans-serif-light", "sans-serif-condensed", "serif", "monospace"), listOf(R.string.labelFont_system, R.string.labelFont_sans_serif, R.string.labelFont_sans_serif_medium, R.string.labelFont_sans_serif_light, R.string.labelFont_sans_serif_condensed, R.string.labelFont_serif, R.string.labelFont_monospace)),
    Choice(HOME, "labelLines", R.string.set_labelLines, "1", listOf("1", "2"), listOf(R.string.labelLines_1, R.string.labelLines_2)),
    onOff(HOME, "labelShadow", R.string.set_labelShadow, true),
    Choice(HOME, "scrollEffect", R.string.set_scrollEffect, "simple", EFFECTS, EFFECT_NAMES),
    Choice(HOME, "indicator", R.string.set_indicator, "plain", listOf("plain", "signal", "none", "dots", "line"), listOf(R.string.indicator_plain, R.string.indicator_signal, R.string.indicator_none, R.string.indicator_dots, R.string.indicator_line)),
    onOff(HOME, "infiniteScroll", R.string.set_infiniteScroll, false),
    Choice(HOME, "wallpaperScroll", R.string.set_wallpaperScroll, "on", listOf("on", "off", "reverse"), listOf(R.string.wallpaperScroll_on, R.string.wallpaperScroll_off, R.string.wallpaperScroll_reverse)),
    Choice(HOME, "searchBar", R.string.set_searchBar, "dockBelow", listOf("off", "top", "bottom", "dockAbove", "dockBelow"), listOf(R.string.searchBar_off, R.string.searchBar_top, R.string.searchBar_bottom, R.string.searchBar_dockabove, R.string.searchBar_dockbelow)),
    Choice(HOME, "searchStyle", R.string.set_searchStyle, "pill", listOf("rect", "rounded", "pill"), listOf(R.string.searchStyle_rect, R.string.searchStyle_rounded, R.string.searchStyle_pill)),
    color(HOME, "searchColor", R.string.set_searchColor, "system", themed = true),
    // The clock item (long-press home → Clock). "style": a big clock with the Appliance look, the date alone otherwise.
    Choice(HOME, "glanceStyle", R.string.set_glanceStyle, "style", listOf("style", "clock", "date"), listOf(R.string.drawerSearch_style, R.string.glanceStyle_clock, R.string.glanceStyle_date)),
    onOff(HOME, "lockLayout", R.string.set_lockLayout, false),
    onOff(HOME, "widgetOverlap", R.string.set_widgetOverlap, false),
    // 0: the widget keeps its own corners.
    Choice(HOME, "widgetRadius", R.string.set_widgetRadius, "0", range(0, 32, 4), shown = range(0, 32, 4).map { "$it dp" }),
    onOff(HOME, "autoAdd", R.string.set_autoAdd, false),
    onOff(HOME, "hideClock", R.string.set_hideClock, false, R.string.set_hideClock_info),
    Choice(HOME, "statusBar", R.string.set_statusBar, "show", listOf("show", "hide"), listOf(R.string.statusBar_show, R.string.statusBar_hide)),
    onOff(HOME, "darkStatusIcons", R.string.set_darkStatusIcons, false),
    // Portrait by default: landscape is opt-in (a phone grid held sideways is cramped).
    Choice(HOME, "orientation", R.string.set_orientation, "portrait", listOf("portrait", "auto", "landscape"), listOf(R.string.orientation_portrait, R.string.orientation_auto, R.string.orientation_landscape)),

    onOff(DOCK_GROUP, "dock", R.string.set_dock, true),
    Choice(DOCK_GROUP, "dockCols", R.string.set_dockCols, "5", range(3, 9)),
    Choice(DOCK_GROUP, "dockPages", R.string.set_dockPages, "1", range(1, 3)),
    sizes(DOCK_GROUP, "dockIconSize", R.string.set_dockIconSize, "64"),
    onOff(DOCK_GROUP, "dockLabels", R.string.set_dockLabels, false),
    Choice(DOCK_GROUP, "dockBg", R.string.set_dockBg, "none", listOf("housing", "glass", "none", "rect", "rounded", "card"), listOf(R.string.dockBg_housing, R.string.dockBg_glass, R.string.dockBg_none, R.string.dockBg_rect, R.string.dockBg_rounded, R.string.dockBg_card)),
    color(DOCK_GROUP, "dockColor", R.string.set_dockColor, "system", themed = true),
    percent(DOCK_GROUP, "dockAlpha", R.string.set_dockAlpha, "50"),
    onOff(DOCK_GROUP, "dockBehindNav", R.string.set_dockBehindNav, true),
    onOff(DOCK_GROUP, "swipeIndicator", R.string.set_swipeIndicator, false),
    onOff(DOCK_GROUP, "dockShadow", R.string.set_dockShadow, false),
    onOff(DOCK_GROUP, "dockOverlay", R.string.set_dockOverlay, false, R.string.set_dockOverlay_info),

    Choice(DRAWER, "drawerStyle", R.string.set_drawerStyle, "grid", listOf("housing", "glass", "grid", "list", "pages"), listOf(R.string.drawerStyle_housing, R.string.drawerStyle_glass, R.string.drawerStyle_grid, R.string.drawerStyle_list, R.string.drawerStyle_pages)),
    // As Nova: None, Top or Bottom; "style" keeps the drawer style's own place (bottom for glass and housing).
    Choice(DRAWER, "drawerSearch", R.string.set_drawerSearch, "style", listOf("style", "off", "top", "bottom"), listOf(R.string.drawerSearch_style, R.string.searchBar_off, R.string.searchBar_top, R.string.searchBar_bottom)),
    Choice(DRAWER, "drawerCols", R.string.set_drawerCols, "4", range(3, 8)),
    // As Nova: rows size the cells in every grid style, not just pages.
    Choice(DRAWER, "drawerRows", R.string.set_drawerRows, "6", range(3, 9)),
    sizes(DRAWER, "drawerIconSize", R.string.set_drawerIconSize, "60"),
    onOff(DRAWER, "drawerLabels", R.string.set_drawerLabels, true),
    labelSize(DRAWER, "drawerLabelSize"),
    Choice(DRAWER, "drawerTheme", R.string.set_drawerTheme, "auto", listOf("auto", "dark", "light"), listOf(R.string.drawerTheme_auto, R.string.drawerTheme_dark, R.string.drawerTheme_light)),
    percent(DRAWER, "drawerAlpha", R.string.set_drawerAlpha, "100"),
    Choice(DRAWER, "drawerSort", R.string.set_drawerSort, "az", SORTS, SORT_NAMES),
    onOff(DRAWER, "frequent", R.string.set_frequent, true),
    onOff(DRAWER, "showKeyboard", R.string.set_showKeyboard, false),
    onOff(DRAWER, "pullToSearch", R.string.set_pullToSearch, false, R.string.set_pullToSearch_info),
    onOff(DRAWER, "rememberPosition", R.string.set_rememberPosition, false),
    onOff(DRAWER, "closeAfterLaunch", R.string.set_closeAfterLaunch, true),
    onOff(DRAWER, "fastScroll", R.string.set_fastScroll, true),
    onOff(DRAWER, "foldersFirst", R.string.set_foldersFirst, true),
    onOff(DRAWER, "keepInAll", R.string.set_keepInAll, true),
    onOff(DRAWER, "workTab", R.string.set_workTab, true),
    onOff(DRAWER, "webSearch", R.string.set_webSearch, true, R.string.set_webSearch_info),
    Choice(DRAWER, "searchEngine", R.string.set_searchEngine, "browser", SEARCH_ENGINES, SEARCH_ENGINE_NAMES),
    onOff(DRAWER, "contactSearch", R.string.set_contactSearch, false, R.string.set_contactSearch_info),

    Choice(FOLDERS, "folderPreview", R.string.set_folderPreview, "grid", PREVIEWS, PREVIEW_NAMES),
    Choice(FOLDERS, "folderBg", R.string.set_folderBg, "square", FOLDER_BGS, FOLDER_BG_NAMES),
    color(FOLDERS, "folderColor", R.string.set_folderColor, "system", themed = true),
    onOff(FOLDERS, "folderClip", R.string.set_folderClip, false),
    onOff(FOLDERS, "folderFirstBig", R.string.set_folderFirstBig, false),
    Choice(FOLDERS, "folderRadius", R.string.set_folderRadius, "28", range(0, 32, 4), shown = range(0, 32, 4).map { "$it dp" }),
    Choice(FOLDERS, "folderWindow", R.string.set_folderWindow, "popup", listOf("popup", "fullscreen"), listOf(R.string.folderWindow_popup, R.string.folderWindow_fullscreen)),
    onOff(FOLDERS, "folderSwipe", R.string.set_folderSwipe, false, R.string.set_folderSwipe_info),
    onOff(FOLDERS, "folderNames", R.string.set_folderNames, true),
    labelSize(FOLDERS, "folderLabelSize"),

    Choice(LOOK, "shape", R.string.set_shape, "system", SHAPES, SHAPE_NAMES),
    Choice(LOOK, "reshape", R.string.set_reshape, "off", listOf("off", "unthemed", "always"), listOf(R.string.reshape_off, R.string.reshape_unthemed, R.string.reshape_always)),
    onOff(LOOK, "autogen", R.string.set_autogen, true),
    onOff(LOOK, "normalize", R.string.set_normalize, false),
    onOff(LOOK, "iconShadow", R.string.set_iconShadow, false),
    onOff(LOOK, "wiggle", R.string.set_wiggle, false),
    Choice(LOOK, "launchAnim", R.string.set_launchAnim, "zoom", listOf("system", "zoom", "reveal", "fade", "slideUp", "slideLeft", "none"), listOf(R.string.launchAnim_system, R.string.launchAnim_zoom, R.string.launchAnim_reveal, R.string.launchAnim_fade, R.string.launchAnim_slideup, R.string.launchAnim_slideleft, R.string.launchAnim_none)),
    Choice(LOOK, "accent", R.string.set_accent, "appliance", listOf("appliance", "system", "amoled") + COLOR_VALUES, listOf(R.string.accent_appliance, R.string.color_system, R.string.accent_amoled) + COLOR_NAMES),
    Choice(LOOK, "settingsAnim", R.string.set_settingsAnim, "slide", listOf("slide", "fade", "none"), listOf(R.string.settingsAnim_slide, R.string.settingsAnim_fade, R.string.settingsAnim_none)),
    onOff(LOOK, "blur", R.string.set_blur, false, R.string.set_blur_info),
    onOff(LOOK, "popupShortcuts", R.string.set_popupShortcuts, true),
    onOff(LOOK, "popupNotifications", R.string.set_popupNotifications, true),
    onOff(LOOK, "closeApp", R.string.set_closeApp, true),
    onOff(LOOK, "undoBar", R.string.set_undoBar, true),

    Choice(NIGHT, "nightMode", R.string.set_nightMode, "system", NIGHT_MODES, NIGHT_MODE_NAMES),
    Choice(NIGHT, "nightStart", R.string.set_nightStart, "20", range(0, 23), shown = range(0, 23).map { "$it:00" }),
    Choice(NIGHT, "nightEnd", R.string.set_nightEnd, "7", range(0, 23), shown = range(0, 23).map { "$it:00" }),

    Choice(BADGES, "badges", R.string.set_badges, "off", listOf("off", "dots", "numeric", "dynamic"), listOf(R.string.badges_off, R.string.badges_dots, R.string.badges_numeric, R.string.badges_dynamic)),
    Choice(BADGES, "badgePos", R.string.set_badgePos, "topRight", listOf("topLeft", "topRight", "bottomLeft", "bottomRight"), listOf(R.string.badgePos_topleft, R.string.badgePos_topright, R.string.badgePos_bottomleft, R.string.badgePos_bottomright)),
    Choice(BADGES, "badgeSize", R.string.set_badgeSize, "medium", listOf("small", "medium", "large"), listOf(R.string.badgeSize_small, R.string.badgeSize_medium, R.string.badgeSize_large)),
    color(BADGES, "badgeColor", R.string.set_badgeColor, "#E53935"),

    onOff(NOW, "nowPage", R.string.set_nowPage, true, R.string.set_nowPage_info),
    onOff(NOW, "nowClock", R.string.set_nowClock, true),
    onOff(NOW, "nowMedia", R.string.set_nowMedia, true, R.string.set_nowMedia_info),
    onOff(NOW, "nowCalendar", R.string.set_nowCalendar, false, R.string.set_nowCalendar_info),
    onOff(NOW, "nowContacts", R.string.set_nowContacts, false, R.string.set_nowContacts_info),
    onOff(NOW, "nowApps", R.string.set_nowApps, true),
) + GESTURE_KEYS.indices.map { Choice(GESTURES, GESTURE_KEYS[it], GESTURE_NAMES[it], GESTURE_DEFAULTS[it], ACTIONS, ACTION_NAMES, action = true) } + listOf(
    Choice(GESTURES, "homeButton", R.string.set_homeButton, "defaultpage", ACTIONS, ACTION_NAMES, action = true),
    onOff(GESTURES, "homeFirst", R.string.set_homeFirst, true),
)

/** Defaults before 0.16, for settings whose default changed then. "glance" on becomes a clock item ([MainActivity.build]). */
val PRE_NOVA_DEFAULTS = mapOf("cols" to "4", "pages" to "3", "searchBar" to "off", "glance" to "on", "dockCols" to "4", "drawerStyle" to "housing")

/** Settings in their own SharedPreferences. Changing one rebuilds home (MainActivity.rebuild). */
class Looks(context: Context) {
    private val prefs = context.getSharedPreferences("looks", Context.MODE_PRIVATE).apply {
        // Before 0.16 the home button had its own values; "default" is now the "defaultpage" action.
        if (getString("homeButton", null) == "default") edit().putString("homeButton", "defaultpage").apply()
    }

    /**
     * 0.16 took Nova's fresh-install defaults. An install from before ([upgrade]) keeps the defaults it was set up
     * with, so its layout doesn't change under it. Runs once.
     */
    fun adoptDefaults(upgrade: Boolean) {
        if (prefs.contains("defaults")) return
        val e = prefs.edit().putString("defaults", "nova")
        if (upgrade) PRE_NOVA_DEFAULTS.forEach { (k, v) -> if (!prefs.contains(k)) e.putString(k, v) }
        e.apply()
    }

    operator fun get(c: Choice): String = prefs.getString(c.key, c.default)!!
    operator fun set(c: Choice, value: String) = prefs.edit().putString(c.key, value).apply()
    operator fun set(key: String, value: String) = prefs.edit().putString(key, value).apply()

    /** Every stored value, to tell whether any changed. */
    fun all(): Map<String, *> = prefs.all

    /** A stored value that isn't a [Choice] (cached location), or null. */
    fun raw(key: String): String? = prefs.getString(key, null)
    fun remove(keys: List<String>) = prefs.edit().apply { keys.forEach(::remove) }.apply()

    /** The user's colours ([COLOR_ROLES]) that are set. */
    fun colors() = COLOR_ROLES.mapNotNull { (k, _) -> raw("c_$k")?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() }?.let { k to it } }.toMap()

    fun str(key: String) = prefs.getString(key, null) ?: LOOKS.first { it.key == key }.default
    fun int(key: String) = str(key).toIntOrNull() ?: LOOKS.first { it.key == key }.default.toInt()
    fun on(key: String) = str(key) == "on"
    /** A label size setting in sp; "home" (or a missing key) is the home screen's. */
    fun labelSize(key: String) = prefs.getString(key, null)?.toFloatOrNull() ?: int("labelSize").toFloat()
    fun color(key: String) = runCatching { android.graphics.Color.parseColor(str(key)) }.getOrDefault(android.graphics.Color.WHITE)

    val cols get() = int("cols")
    val rows get() = int("rows")
    val pages get() = int("pages")
    val dockOn get() = on("dock")
    val dockCols get() = int("dockCols")
    val dockPages get() = int("dockPages")
    val sub get() = if (on("subgrid")) 2 else 1
    val iconPack get() = prefs.getString("iconPack", "")!!
}
