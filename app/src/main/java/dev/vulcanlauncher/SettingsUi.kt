package dev.vulcanlauncher

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings
import android.graphics.drawable.Drawable
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// Settings: a screen of its own (SettingsActivity), as Launcher3's: categories, then one page per category. Picking a
// value opens a dialog; a change only marks home stale, and home is built again once, when it shows (MainActivity.onStart).

private val CATEGORY_INFO = mapOf(
    HOME to ("home" to R.string.cat_home),
    DRAWER to ("apps" to R.string.cat_drawer),
    FOLDERS to ("folder" to R.string.cat_folders),
    LOOK to ("palette" to R.string.cat_look),
    NIGHT to ("moon" to R.string.cat_night),
    BADGES to ("badge" to R.string.cat_badges),
    GESTURES to ("gesture" to R.string.cat_gestures),
    NOW to ("sun" to R.string.cat_now),
    SEARCH_GROUP to ("search" to R.string.cat_search),
)

// Launcher pages under settings home, beside the categories.
const val BACKUP = "Backup"
const val PERMISSIONS = "Permissions"
const val GROUPS = "Groups"

/** Colors sits under Look & feel and Dock under Home screen, as in Nova. */
private val PARENT = mapOf(COLORS to LOOK, DOCK_GROUP to HOME)
private fun depth(page: String) = when (page) { "" -> 0; in PARENT -> 2; else -> 1 }

/** Where each settings page was scrolled when it was left for a deeper one, so back returns to the same spot. */
private val leftAt = mutableMapOf<String, Int>()

/** Opens settings at [page] ("" for settings home). */
fun MainActivity.showSettings(page: String = "") {
    startActivity(Intent(this, SettingsActivity::class.java).putExtra("page", page))
    SettingsActivity.transition(this)
}

class SettingsActivity : VulcanActivity() {
    companion object {
        /** Settings fades over home and back, or just appears ("Settings animation" none). */
        @Suppress("DEPRECATION")
        fun transition(a: VulcanActivity) =
            if (a.looks.str("settingsAnim") == "none") a.overridePendingTransition(0, 0) else a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // ponytail: reads the launcher's live state (apps, drawer, widgets) from the activity under it. Brought back alone
    // after the process died, settings just closes (onCreate); a store-backed app model would let it stand alone.
    override val home get() = checkNotNull(MainActivity.running?.get())
    /** The page showing ("" for settings home), its panel and its scroll. */
    var page: String? = null
    var panel: View? = null
    var scroll: ScrollView? = null
    /** Holds the page panels, inside the system bars. */
    lateinit var pages: android.widget.FrameLayout

    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        val h = MainActivity.running?.get()
        if (h == null || h.isDestroyed) { finish(); return }
        store = h.store
        looks = h.looks
        applyOrientation()
        recolor()
        bars()
        // The system bars and the keyboard pad the pages; the window's surface colour runs on under them. Dialogs dim
        // the whole window (root), bars included.
        pages = android.widget.FrameLayout(this).apply { fitsSystemWindows = true }
        root = android.widget.FrameLayout(this).apply { addView(pages) }
        setContentView(root)
        showPage(state?.getString("page") ?: intent.getStringExtra("page").orEmpty(), state?.getInt("scroll") ?: 0)
    }

    @Suppress("DEPRECATION")
    private fun bars() {
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(m3.surface))
        window.statusBarColor = m3.surface
        window.navigationBarColor = m3.surface
        window.decorView.systemUiVisibility = if (m3.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }

    /** A setting changed: home is built again when it shows, and this page now, in the new colours and where it was. */
    fun changed() {
        home.stale = true
        applyOrientation()
        recolor()
        bars()
        refresh()
    }

    fun refresh() = page?.let { showPage(it, scroll?.scrollY ?: 0, restoring = true) }

    override fun onResume() {
        super.onResume()
        // Back from the Android page that grants one: show its new state. (Not on the first resume: it was just built.)
        if (page == PERMISSIONS && panel?.isAttachedToWindow == true) refresh()
    }

    override fun onSaveInstanceState(out: android.os.Bundle) {
        super.onSaveInstanceState(out)
        page?.let { out.putString("page", it); out.putInt("scroll", scroll?.scrollY ?: 0) }
    }

    override fun handleBack() { (panel?.tag as? Runnable)?.run() ?: finish() }

    override fun finish() {
        super.finish()
        if (page != null) transition(this) // null: closed in onCreate, before settings loaded
    }

    /** After a restore, an import or a reset: home starts again from what was stored, and settings closes. */
    fun restartHome() {
        home.recreate()
        finish()
    }

    @Deprecated("Plain Activity result API is fine without androidx")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (shortcutMade(requestCode, resultCode, data)) return
        val uri = data?.data?.takeIf { resultCode == RESULT_OK } ?: return
        when (requestCode) {
            MainActivity.REQ_BACKUP -> runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(store.export(home.apps.values.toList()).toByteArray()) } }
                .onSuccess { toast(R.string.backup_saved) }.onFailure { toast(R.string.backup_failed, it.message) }
            MainActivity.REQ_RESTORE -> restoreBackup(uri, ::restartHome)
            // The same review (and widget) steps as the first run, over home: settings closes onto them.
            MainActivity.REQ_DOC -> home.readForReview(uri)?.let { home.showImport(it); finish() }
        }
    }
}

/** [focus]: a setting's key (from a search); the page opens with that row centred and briefly highlighted. */
fun SettingsActivity.showPage(page: String, scroll: Int = 0, restoring: Boolean = false, focus: String? = null) {
    // Page to page, the old page stays until the new one covers it.
    val old = panel
    val backward = old != null && !restoring && depth(page) < depth(this.page!!)
    val samePage = old != null && page == this.page
    if (old != null && !restoring && !backward && !samePage) this.scroll?.let { leftAt[this.page!!] = it.scrollY }
    val list = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, (16 * dp).toInt())
    }
    var search: ((String) -> Unit)? = null
    when (page) { "" -> search = settingsHome(list); COLORS -> colorsPage(list); BACKUP -> backupPage(list); PERMISSIONS -> permissionsPage(list); GROUPS -> groupsPage(list); CONTRIBUTORS_PAGE -> contributorsPage(list); else -> settingsCategory(page, list) }
    val scrollView = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }
    val title = text(getString(when (page) { "" -> R.string.settings; COLORS -> R.string.custom_colors; BACKUP -> R.string.backup_restore; PERMISSIONS -> R.string.permissions; GROUPS -> R.string.drawer_groups; CONTRIBUTORS_PAGE -> R.string.contributors; else -> GROUP_TITLES.getValue(page) }), 22f).apply { setPadding((8 * dp).toInt(), 0, 0, 0); maxLines = 1 }
    // Settings home, as One UI: a search button in the bar turns the title into the search field.
    val field = search?.let { onQuery -> EditText(this).apply {
        visibility = View.GONE
        hint = getString(R.string.settings_search)
        setSingleLine()
        textSize = 18f
        setTextColor(m3.onSurface)
        setHintTextColor(m3.onSurfaceVariant)
        background = null
        setPadding((8 * dp).toInt(), 0, 0, 0)
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { onQuery(s.toString()); scrollView.scrollTo(0, 0) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    } }
    val ime = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
    val searchButton = field?.let { f -> iconButton("search", getString(R.string.settings_search), m3.onSurface) { v ->
        v.visibility = View.GONE; title.visibility = View.GONE; f.visibility = View.VISIBLE
        f.requestFocus(); ime.showSoftInput(f, 0)
    } }
    // Back closes an open search before it leaves the page.
    val back = Runnable {
        if (field?.visibility == View.VISIBLE) {
            ime.hideSoftInputFromWindow(field.windowToken, 0)
            field.setText(""); field.visibility = View.GONE; title.visibility = View.VISIBLE; searchButton?.visibility = View.VISIBLE
        } else if (page == "") finish() else PARENT[page].orEmpty().let { showPage(it, leftAt[it] ?: 0) }
    }
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(m3.surface)
        isClickable = true
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
            addView(iconButton("back", getString(R.string.back), m3.onSurface) { back.run() })
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            field?.let { addView(it, LinearLayout.LayoutParams(0, -1, 1f)) }
            searchButton?.let { addView(it) }
        }, LinearLayout.LayoutParams(-1, (64 * dp).toInt()))
        addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))
        tag = back
    }
    // Back: the parent page goes under the current one, which slides away to uncover it.
    pages.addView(panel, if (old == null) 0 else pages.indexOfChild(old) + if (backward) 0 else 1)
    this.panel = panel
    this.page = page
    this.scroll = scrollView
    // Before the first frame, not after it (post): drawn once at the top, a rebuilt page flashed other rows.
    if (scroll > 0 || focus != null) scrollView.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            scrollView.viewTreeObserver.removeOnPreDrawListener(this)
            val row = focus?.let { list.findViewWithTag<View>(it) }
            if (row == null) scrollView.scrollTo(0, scroll) else {
                val r = android.graphics.Rect().also(row::getDrawingRect)
                list.offsetDescendantRectToMyCoords(row, r)
                scrollView.scrollTo(0, r.centerY() - scrollView.height / 2)
                // As Android's settings: the found row lights up, then fades.
                row.background?.setHotspot(row.width / 2f, row.height / 2f)
                row.isPressed = true
                row.postDelayed({ row.isPressed = false }, 700)
            }
            return true
        }
    })
    val anim = looks.str("settingsAnim")
    val shift = 32 * dp
    when {
        // The first page comes with the screen (SettingsActivity.transition).
        old == null -> {}
        // A setting changed, or the page is refreshed: at once.
        restoring || samePage || anim == "none" -> pages.removeView(old)
        // The parent stays put under it.
        backward -> {
            old.animate().alpha(0f).translationX(if (anim == "slide") shift else 0f).setDuration(150).setInterpolator(EASE).withEndAction { pages.removeView(old) }.start()
        }
        else -> {
            panel.alpha = 0f
            if (anim == "slide") panel.translationX = shift
            panel.animate().alpha(1f).translationX(0f).setDuration(150).setInterpolator(EASE).withEndAction { pages.removeView(old) }.start()
        }
    }
}

/** Builds the settings home into [page]; returns the search: given a query, its matches replace the page. */
private fun SettingsActivity.settingsHome(page: LinearLayout): (String) -> Unit {
    val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    list.addView(nameplate())
    page.addView(results)
    page.addView(list)
    val search = { q: String ->
        results.removeAllViews()
        list.visibility = if (q.isBlank()) View.VISIBLE else View.GONE
        if (q.isNotBlank()) {
            val hits = LOOKS.filter { c ->
                c.group in GROUP_TITLES && listOf(c.title, c.info, GROUP_TITLES.getValue(c.group)).any { it != 0 && getString(it).contains(q.trim(), true) }
            }
            if (hits.isEmpty()) results.addView(text(getString(R.string.settings_no_match), 15f, m3.onSurfaceVariant).apply {
                gravity = Gravity.CENTER; setPadding(0, (32 * dp).toInt(), 0, 0)
            })
            else panel(results, null, hits.map { c -> listItem(null, getString(c.title), getString(GROUP_TITLES.getValue(c.group))) { showPage(c.group, focus = c.key) } })
        }
    }
    fun category(name: String) = CATEGORY_INFO.getValue(name).let { (icon, info) ->
        listItem(symbol(icon, m3.onPrimaryContainer), getString(GROUP_TITLES.getValue(name)), getString(info), tonalIcon = true) { showPage(name) }
    }
    // As Nova 8.9.4's two tabs: what goes where, then how it looks.
    panel(list, getString(R.string.settings), listOf(HOME, DRAWER, FOLDERS, SEARCH_GROUP, GESTURES, NOW, BADGES).map(::category))
    panel(list, getString(R.string.panel_style), listOf(LOOK, NIGHT).map(::category) +
        listItem(symbol("link", m3.onPrimaryContainer), getString(R.string.share_style_link),
            getString(R.string.share_style_info), tonalIcon = true) { shareStyle() })
    panel(list, getString(R.string.panel_launcher), listOf(
        listItem(symbol("archive", m3.onPrimaryContainer), getString(R.string.backup_restore), getString(R.string.backup_restore_info), tonalIcon = true) { showPage(BACKUP) },
        listItem(symbol("shield", m3.onPrimaryContainer), getString(R.string.permissions), getString(R.string.permissions_info), tonalIcon = true) { showPage(PERMISSIONS) },
        listItem(symbol("home", m3.onPrimaryContainer), getString(R.string.default_home), getString(R.string.default_home_info), tonalIcon = true) {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        },
        listItem(symbol("refresh", m3.onPrimaryContainer), getString(R.string.restart), null, tonalIcon = true) { restartHome() },
        listItem(symbol("person", m3.onPrimaryContainer), getString(R.string.contributors), getString(R.string.contrib_info), tonalIcon = true) { showPage(CONTRIBUTORS_PAGE) },
        listItem(symbol("info", m3.onPrimaryContainer), getString(R.string.about), getString(R.string.version, versionName), tonalIcon = true) {
            dialog().setTitle(R.string.app_name).setIcon(R.mipmap.ic_launcher)
                .setMessage(R.string.about_text)
                .setNeutralButton(R.string.privacy_policy) { _, _ -> showPrivacyPolicy() }
                .setPositiveButton(R.string.ok, null).show()
        },
    ))
    return search
}

private val SettingsActivity.versionName get() = packageManager.getPackageInfo(packageName, 0).versionName

/** Rows on one raised face, divided by hairlines, under a printed legend: one panel of the appliance. */
fun SettingsActivity.panel(list: LinearLayout, title: String?, rows: List<View>) {
    if (title != null) list.addView(legend(title, 12f).apply { setPadding((32 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt()) })
    list.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        housing(this, 24 * dp)
        elevation = 2 * dp
        clipToOutline = true
        setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
        rows.forEachIndexed { i, row ->
            // The hairline starts under the text, past a row's icon well.
            if (i > 0) addView(View(context).apply { setBackgroundColor(m3.outlineVariant) }, LinearLayout.LayoutParams(-1, maxOf(1, (dp / 2).roundToInt())).apply {
                marginStart = ((if ((rows[i - 1] as? LinearLayout)?.getChildAt(0) is ImageView) 72 else 16) * dp).toInt()
                marginEnd = (16 * dp).toInt()
            })
            addView(row)
        }
    }, LinearLayout.LayoutParams(-1, -2).apply { setMargins((16 * dp).toInt(), if (title == null) (8 * dp).toInt() else 0, (16 * dp).toInt(), (8 * dp).toInt()) })
}

/** The rating plate at the top of settings: the name and version, printed on a raised face. */
private fun SettingsActivity.nameplate() = LinearLayout(this).apply {
    gravity = Gravity.CENTER_VERTICAL
    housing(this, 24 * dp)
    elevation = 2 * dp
    setPadding((20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt())
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(getString(R.string.app_name), 34f).apply {
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            letterSpacing = -0.02f
        })
        addView(legend(getString(R.string.version, versionName), 11f, m3.outline).apply { setPadding(0, (6 * dp).toInt(), 0, 0) })
    }, LinearLayout.LayoutParams(0, -2, 1f))
    addView(ImageView(context).apply {
        setImageResource(R.mipmap.ic_launcher)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams((64 * dp).toInt(), (64 * dp).toInt()).apply {
        marginStart = (16 * dp).toInt()
    })
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt()) }
}

private fun SettingsActivity.settingsCategory(group: String, list: LinearLayout) {
    // Schedule hours only where they're used: the schedule, and sunset to sunrise until a location is known.
    val hours = looks.str("nightMode") in listOf("schedule", "sun")
    val choices = (if (group == LOOK) listOf(iconPackChoice()) else emptyList()) +
        LOOKS.filter { it.group == group && (hours || it.key != "nightStart" && it.key != "nightEnd") }
    fun pick(c: Choice) = sheet().setTitle(c.title).setSingleChoiceItems(c.values.indices.map { choiceName(c, it) }.toTypedArray(), c.values.indexOf(looks[c])) { d, j ->
        d.dismiss()
        applyChoice(c, c.values[j])
        changed()
    }.setNegativeButton(R.string.cancel, null).show()
    fun row(c: Choice): View = when {
        // The housing dock has its own shadow; no background has none to cast.
        c.key == "dockShadow" && looks.str("dockBg") in listOf("housing", "none") ->
            listItem(null, getString(c.title), getString(if (looks.str("dockBg") == "none") R.string.dock_shadow_no_bg else R.string.dock_shadow_housing)) {}.apply { alpha = 0.5f }
        c.values.toSet() == setOf("on", "off") -> {
            val toggle = Toggle(this, looks[c] == "on")
            listItem(null, getString(c.title), if (c.info != 0) getString(c.info) else null, trailing = toggle) {
                toggle.checked = !toggle.checked
                applyChoice(c, if (toggle.checked) "on" else "off")
            }
        }
        c.action -> listItem(null, getString(c.title), valueName(c)) { pickAction(getString(c.title)) { looks[c] = it; changed() } }
        // Settings the chosen dock background doesn't use: shown dimmed and saying so, still pickable.
        c.key == "dockAlpha" && looks.str("dockBg") in listOf("housing", "glass", "none") || c.key == "dockColor" && looks.str("dockBg") == "none" ->
            listItem(null, getString(c.title), if (looks.str("dockBg") == "none") getString(R.string.dock_shadow_no_bg)
                else getString(R.string.not_used_by_bg, valueName(LOOKS.first { it.key == "dockBg" }))) { pick(c) }.apply { alpha = 0.5f }
        // AMOLED Black is always dark, whatever the panel theme says.
        c.key == "drawerTheme" && looks.str("accent") == "amoled" ->
            listItem(null, getString(c.title), getString(R.string.panels_amoled)) { pick(c) }.apply { alpha = 0.5f }
        c.values.size > 2 && c.values.all { it.toIntOrNull() != null } -> slider(c)
        else -> listItem(null, getString(c.title), valueName(c), trailing = swatch(c)) { pick(c) }
    }.apply { tag = c.key }
    val byKey = choices.associateBy { it.key }
    fun link(key: String): View? = when (key) {
        "@dock" -> listItem(null, getString(R.string.group_dock), getString(R.string.cat_dock)) { showPage(DOCK_GROUP) }
        "@hidden" -> listItem(null, getString(R.string.hidden_apps), getString(R.string.hidden_apps_info)) { hideAppsMenu() }
        "@groups" -> listItem(null, getString(R.string.drawer_groups), getString(R.string.drawer_groups_info)) { showPage(GROUPS) }
        // Not a setting: says how cards come and go, and how to move them.
        "@nowCards" -> text(getString(R.string.now_cards_info), 14f, m3.onSurfaceVariant).apply { setPadding((16 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), (12 * dp).toInt()) }
        "@colors" -> listItem(null, getString(R.string.custom_colors),
            resources.getQuantityString(R.plurals.custom_colors_set, looks.colors().size, looks.colors().size)) { showPage(COLORS) }
        else -> byKey[key]?.let(::row) // null: hidden for now (night hours)
    }
    val sections = SECTIONS[group] ?: return panel(list, null, choices.map(::row))
    for ((title, keys) in sections) keys.mapNotNull(::link).takeIf { it.isNotEmpty() }?.let { panel(list, getString(title), it) }
}

/** A numeric setting as an inline M3 slider, one stop per value, saved when the thumb is let go. */
private fun SettingsActivity.slider(c: Choice): View {
    val value = text(valueName(c), 14f, m3.onSurfaceVariant)
    val bar = android.widget.SeekBar(this).apply {
        max = c.values.size - 1
        progress = c.values.indexOf(looks[c]).coerceAtLeast(0)
        contentDescription = getString(c.title)
        val tint = android.content.res.ColorStateList.valueOf(m3.on)
        progressTintList = tint; thumbTintList = tint; tickMarkTintList = tint
        if (c.values.size <= 12) tickMark = GradientDrawable().apply { shape = GradientDrawable.OVAL; setSize((4 * dp).toInt(), (4 * dp).toInt()) }
        setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            var dragging = false
            override fun onProgressChanged(b: android.widget.SeekBar, i: Int, user: Boolean) {
                value.text = choiceName(c, i)
                if (android.os.Build.VERSION.SDK_INT >= 30) stateDescription = value.text
                // Keys and accessibility moves have no let go: save them as they come.
                if (user && !dragging) applyChoice(c, c.values[i])
            }
            override fun onStartTrackingTouch(b: android.widget.SeekBar) { dragging = true }
            override fun onStopTrackingTouch(b: android.widget.SeekBar) { dragging = false; applyChoice(c, c.values[b.progress]) }
        })
        if (android.os.Build.VERSION.SDK_INT >= 30) stateDescription = value.text
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
        addView(listItem(null, getString(c.title), trailing = value, onClick = null).apply { minimumHeight = 0; setPadding(paddingLeft, 0, paddingRight, 0) })
        addView(bar, LinearLayout.LayoutParams(-1, (40 * dp).toInt()))
    }
}

/** A colour setting's current colour as a small circle, or null for other settings. */
private fun SettingsActivity.swatch(c: Choice): View? {
    if (!c.values.any { it.startsWith("#") }) return null
    return View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            // "system" on a housing-coloured part shows the housing it really draws in.
            setColor(when (looks[c]) {
                "system" -> if (m3.housing && c.key in listOf("dockColor", "searchColor", "folderColor")) m3.surface else m3.primary
                "appliance" -> SIGNAL
                "amoled" -> 0xFF000000.toInt()
                else -> looks.color(c.key)
            })
            setStroke(dp.toInt(), m3.outlineVariant)
        }
        layoutParams = LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt())
    }
}

private fun SettingsActivity.iconPackChoice(): Choice {
    val packs = iconPacks(this)
    return Choice(LOOK, "iconPack", R.string.set_iconPack, "", listOf("") + packs.map { it.first }, shown = listOf(getString(R.string.iconPack_system)) + packs.map { it.second })
}

private fun SettingsActivity.valueName(c: Choice) = when {
    c.action -> actionName(looks[c], home::appLabel)
    // Under the Appliance look, "system" on a housing-coloured part draws in the housing colour.
    m3.housing && looks[c] == "system" && c.key in listOf("dockColor", "searchColor", "folderColor") -> getString(R.string.housing_follows_theme)
    else -> c.values.indexOf(looks[c]).let { i -> if (i >= 0) choiceName(c, i) else looks[c].ifEmpty { getString(R.string.none) } }
}

/** Saves a setting, asking for a permission it needs first; home is built again when it shows. */
private fun SettingsActivity.applyChoice(c: Choice, value: String) {
    val old = looks[c]
    if (value == old) return
    looks[c] = value
    home.stale = true
    val perm = when {
        value == "on" && c.key in listOf("contactSearch", "nowContacts") -> android.Manifest.permission.READ_CONTACTS
        value == "on" && c.key == "nowCalendar" -> android.Manifest.permission.READ_CALENDAR
        c.key == "nightMode" && value == "sun" -> android.Manifest.permission.ACCESS_COARSE_LOCATION
        else -> null
    }
    when {
        c.key == "hideClock" && value == "on" && !granted(android.Manifest.permission.WRITE_SECURE_SETTINGS) -> dialog()
            .setTitle(R.string.setup_needed)
            .setMessage(getString(R.string.hide_clock_help, packageName))
            .setPositiveButton(R.string.ok, null).show()
        perm != null && !granted(perm) -> requestPermissions(arrayOf(perm), MainActivity.REQ_PERMISSION)
    }
}

/**
 * Label, icon and (for home items) swipe-up action. Empty label or icon means "use the default".
 * [preview] draws an icon value ("" is the default icon).
 */
fun MainActivity.editDialog(title: String, label: String, defaultLabel: String, icon: String?, swipe: String?,
                            preview: (String) -> Drawable?, folder: Boolean = false, onSave: (String, String, String) -> Unit) {
    var iconV = icon ?: ""
    var swipeV = swipe ?: ""
    val pad = (24 * dp).toInt()
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, (8 * dp).toInt(), pad, 0) }
    val name = field(label, defaultLabel.ifEmpty { getString(R.string.label) })
    // As Nova: the icon sits beside the name; tap it to change it.
    box.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        if (icon != null) addView(ImageView(context).apply {
            fun show() = setImageDrawable(preview(iconV))
            show()
            contentDescription = getString(R.string.icon)
            val p = (8 * dp).toInt()
            setPadding(p, p, p, p)
            background = pressable(rounded(m3.surfaceHighest, 16 * dp), 16 * dp)
            setOnClickListener { pickIcon { iconV = it; show() } }
        }, LinearLayout.LayoutParams((56 * dp).toInt(), (56 * dp).toInt()).apply { marginEnd = (16 * dp).toInt() })
        addView(name, LinearLayout.LayoutParams(0, -2, 1f))
    })
    if (swipe != null) {
        val holder = android.widget.FrameLayout(this)
        fun row() {
            holder.removeAllViews()
            // A folder keeps "none", so it can opt out of the global Swipe to open; "" follows that setting.
            val shown = swipeV.ifEmpty { if (folder && looks.on("folderSwipe")) "folder" else "none" }
            holder.addView(listItem(symbol("gesture"), getString(R.string.swipe_up_action), actionName(shown, ::appLabel)) {
                pickAction(getString(R.string.swipe_up_action), folder) { swipeV = if (it == "none" && !folder) "" else it; row() }
            })
        }
        row()
        // The row's own 16 dp inset lines its icon up with the dialog's 24 dp edge.
        box.addView(holder, LinearLayout.LayoutParams(-1, -2).apply { setMargins((-16 * dp).toInt(), (8 * dp).toInt(), -pad, 0) })
    }
    sheet().setTitle(title).setView(box)
        .setPositiveButton(R.string.save) { _, _ -> onSave(name.text.toString().trim(), iconV, swipeV) }
        .setNegativeButton(R.string.cancel, null).show()
}

fun MainActivity.editItem(item: Item) {
    val default = when {
        item.folder -> getString(R.string.folder)
        item.isApp -> appLabel(item.appKey) ?: ""
        else -> resolve(item.copy(label = ""))?.first?.toString() ?: ""
    }
    editDialog(getString(if (item.folder) R.string.edit_folder else R.string.edit), item.label, default, item.icon, item.swipe,
        { ic -> resolve(item.copy(icon = ic))?.second }, item.folder) { l, ic, sw ->
        val i = items.indexOf(item)
        if (i < 0) return@editDialog
        items[i] = item.copy(label = if (item.folder) l.ifEmpty { getString(R.string.folder) } else l, icon = ic, swipe = sw)
        save()
        render()
    }
}

/** Drawer edits apply to the app everywhere it has no home-screen override. */
fun MainActivity.editApp(app: App) {
    val c = customs.optJSONObject(app.key)
    editDialog(getString(R.string.edit_app), c?.optString("label") ?: "", app.info.label.toString(), c?.optString("icon") ?: "", null,
        { ic -> icons.get(app.pkg, app.cls, ic) { app.info.getBadgedIcon(0) } }) { l, ic, _ ->
        customs.put(app.key, JSONObject().put("label", l).put("icon", ic))
        store.putObj("custom", customs)
        loadApps(listOf(app.pkg))
        drawer.refresh()
        render()
    }
}

fun MainActivity.pickIcon(onPicked: (String) -> Unit) {
    val packs = iconPacks(this)
    val rows = listOf(symbol("refresh") to getString(R.string.icon_default_option)) +
        packs.map { (pkg, name) -> runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull() to getString(R.string.icon_from_pack, name) } +
        (symbol("apps") to getString(R.string.icon_from_app)) + (symbol("image") to getString(R.string.icon_from_gallery))
    pickList(getString(R.string.icon), rows) { i ->
        when {
            i == 0 -> onPicked("")
            i <= packs.size -> pickPackIcon(packs[i - 1].first, onPicked)
            i == packs.size + 1 -> pickApp(getString(R.string.icon_from_app)) { onPicked("app:${it.pkg}/${it.cls}") }
            else -> {
                onPickedImage = { onPicked("uri:$it") }
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), MainActivity.REQ_ICON)
            }
        }
    }
}

/** A full-screen grid of a pack's icons under a search bar. Icons load off the main thread, so scrolling stays smooth. */
private fun MainActivity.pickPackIcon(pkg: String, onPicked: (String) -> Unit) {
    val pack = loadIconPack(this, pkg) ?: run { toast(getString(R.string.icon_pack_unreadable)); return }
    val all = pack.names
    var shown = all
    val cell = (72 * dp).toInt()
    val pad = (10 * dp).toInt()
    // ponytail: ~150 icons kept (a few screens' worth, ~10 MB drawn at cell size); a pack's thousands are redrawn as they scroll back in.
    val cache = android.util.LruCache<String, Drawable>(150)
    val loader = java.util.concurrent.Executors.newSingleThreadExecutor()
    val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup) = ((convert as? ImageView) ?: ImageView(this@pickPackIcon).apply {
            layoutParams = android.widget.AbsListView.LayoutParams(-1, cell)
            setPadding(pad, pad, pad, pad)
            background = pressable(null, 16 * dp)
        }).apply {
            val n = shown[p]
            tag = n
            contentDescription = n
            setImageDrawable(cache.get(n))
            if (cache.get(n) == null) loader.execute {
                if (tag != n) return@execute // scrolled past before its turn
                val d = pack.drawable(n)?.let { flatIcon(this@pickPackIcon, it, cell - 2 * pad) } ?: return@execute
                cache.put(n, d)
                post { if (tag == n) setImageDrawable(d) }
            }
        }
    }
    lateinit var d: android.app.Dialog
    val grid = GridView(this).apply {
        numColumns = GridView.AUTO_FIT
        columnWidth = cell
        this.adapter = adapter
        val pad = (12 * dp).toInt()
        setPadding(pad, 0, pad, pad)
        clipToPadding = false
        setOnItemClickListener { _, _, p, _ -> d.dismiss(); onPicked("pack:$pkg:${shown[p]}") }
    }
    val filter = EditText(this).apply {
        hint = resources.getQuantityString(R.plurals.filter_icons, all.size, all.size)
        setSingleLine()
        textSize = 16f
        setTextColor(m3.onSurface)
        setHintTextColor(m3.onSurfaceVariant)
        background = null
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { shown = all.filter { it.contains(s.toString(), true) }; adapter.notifyDataSetChanged() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(m3.surface)
        // M3 search bar: back, then the filter.
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(m3.surfaceHighest, 28 * dp)
            setPadding((4 * dp).toInt(), 0, (16 * dp).toInt(), 0)
            addView(iconButton("back", getString(R.string.back), m3.onSurface) { d.dismiss() })
            addView(filter, LinearLayout.LayoutParams(0, -1, 1f))
        }, LinearLayout.LayoutParams(-1, (56 * dp).toInt()).apply { setMargins((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt()) })
        addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        @Suppress("DEPRECATION")
        setOnApplyWindowInsetsListener { v, i -> v.setPadding(i.systemWindowInsetLeft, i.systemWindowInsetTop, i.systemWindowInsetRight, i.systemWindowInsetBottom); i }
    }
    d = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar).apply {
        setContentView(panel)
        window?.apply { setLayout(-1, -1); setBackgroundDrawable(android.graphics.drawable.ColorDrawable(m3.surface)) }
        setOnDismissListener { loader.shutdownNow(); dialogs -= d }
        dialogs += d
        show()
    }
}

/** Every app with a checkbox, for hiding apps or filling a drawer group; [onSave] gets the apps checked. */
/** [others]: each app's other groups, shown under its name so a tab's apps can be picked from those not yet sorted (#55). */
fun VulcanActivity.appSwitches(title: String, on: Collection<String>, others: Map<String, List<String>> = emptyMap(), onSave: (List<String>) -> Unit) {
    val all = home.apps.values.sortedWith(compareBy(labelOrder()) { home.label(it) })
    var list = all
    val chosen = on.toMutableSet()
    val adapter = object : BaseAdapter() {
        override fun getCount() = list.size
        override fun getItem(p: Int) = list[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val app = list[p]
            // M3: checkboxes, not switches, for picking several from a list. The row takes the tap.
            val box = android.widget.CheckBox(this@appSwitches).apply {
                isChecked = app.key in chosen; isClickable = false; isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                buttonTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(m3.on, m3.onSurfaceVariant))
            }
            return listItem(home.icon(app), if (home.isWork(app)) getString(R.string.work_app, home.label(app)) else home.label(app),
                others[app.key]?.let { getString(R.string.in_groups, it.joinToString()) }, trailing = box, appIcon = true) {
                box.isChecked = !box.isChecked
                if (box.isChecked) chosen += app.key else chosen -= app.key
            }.apply {
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(v: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(v, info)
                        info.className = android.widget.CheckBox::class.java.name; info.isCheckable = true; info.isChecked = box.isChecked
                    }
                }
            }.apply { setPaddingRelative((24 * dp).toInt(), paddingTop, (24 * dp).toInt(), paddingBottom) } // in line with the title
        }
    }
    val lv = android.widget.ListView(this).apply { this.adapter = adapter; divider = null }
    // A long app list is quicker to search than to scroll. Ticks survive the filter: they're kept by app, not by row.
    val search = field("", getString(R.string.search_apps)).apply {
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) {
                val q = e.toString().trim()
                list = if (q.isEmpty()) all else all.filter { matchScore(home.label(it), q) > 0 }
                adapter.notifyDataSetChanged()
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins((24 * dp).toInt(), (4 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt()) })
        // A fixed height: inside a dialog a weighted list can measure to nothing. Most of the screen, as the list was.
        addView(lv, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.62f).toInt()))
    }
    sheet().setTitle(title).setView(box)
        .setPositiveButton(R.string.save) { _, _ -> onSave(all.map { it.key }.filter { it in chosen }) }
        .setNegativeButton(R.string.cancel, null).show()
}

/**
 * Puts [keys] in order with up and down buttons; Save hands back the new order.
 * ponytail: buttons, not drag handles; a ListView drag-reorder if long lists make this tedious.
 */
fun VulcanActivity.arrangeApps(title: String, keys: List<String>, onSave: (List<String>) -> Unit) {
    var order = keys.filter { it in home.apps }
    val adapter = object : BaseAdapter() {
        override fun getCount() = order.size
        override fun getItem(p: Int) = order[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val app = home.apps.getValue(order[p])
            fun step(sym: String, label: Int, by: Int) = iconButton(sym, getString(label)) { order = moved(order, p, by); notifyDataSetChanged() }
                .apply { isEnabled = p + by in order.indices; alpha = if (isEnabled) 1f else 0.38f }
            val buttons = LinearLayout(this@arrangeApps).apply { addView(step("up", R.string.move_up, -1)); addView(step("down", R.string.move_down, 1)) }
            return listItem(home.icon(app), home.label(app), trailing = buttons, appIcon = true, onClick = null)
                .apply { setPaddingRelative((24 * dp).toInt(), paddingTop, (12 * dp).toInt(), paddingBottom) }
        }
    }
    val lv = android.widget.ListView(this).apply { this.adapter = adapter; divider = null }
    sheet().setTitle(title).setView(lv)
        .setPositiveButton(R.string.save) { _, _ -> onSave(order) }
        .setNegativeButton(R.string.cancel, null).show()
}

/**
 * Arranges [shown], the apps of drawer tab [tab] ("" for all apps): a tab group keeps the order in its apps list, the
 * other tabs share data.drawerOrder. Saving switches the drawer to the manual sort, where that order shows.
 */
fun VulcanActivity.arrangeTab(tab: String, shown: List<String>) {
    val groups = home.drawer.groups()
    val group = groups.firstOrNull { it.name == tab && it.type == "tab" }
    arrangeApps(getString(R.string.arrange_apps), shown) { order ->
        looks["drawerSort"] = "manual"
        if (group != null) saveGroups(groups.map { if (it === group) it.copy(apps = arranged(it.apps, order)) else it })
        else { store.putStrings("drawerOrder", arranged(store.strings("drawerOrder").toList(), order)); home.drawer.refresh() }
    }
}

/** Picks the apps pinned above the drawer's tabs, then their order; none pinned, the row shows the most used apps. */
fun VulcanActivity.chooseFavourites() {
    val pinned = store.strings("favourites").toList()
    appSwitches(getString(R.string.favourites), pinned) { chosen ->
        val keep = pinned.filter { it in chosen } + chosen.filter { it !in pinned }
        fun save(keys: List<String>) { store.putStrings("favourites", keys); home.drawer.refresh() }
        if (keep.size > 1) arrangeApps(getString(R.string.favourites), keep, ::save) else save(keep)
    }
}

fun VulcanActivity.hideAppsMenu() = appSwitches(getString(R.string.hide_apps), store.strings("hidden")) {
    store.putStrings("hidden", it)
    home.drawer.refresh()
}

// ---- Drawer groups (tabs and folders) ----

fun VulcanActivity.saveGroups(groups: List<Group>) {
    store.data.edit().putString("groups", groupsToJson(groups)).apply()
    home.drawer.refresh()
    (this as? SettingsActivity)?.takeIf { it.page == GROUPS }?.refresh()
}

fun MainActivity.groupsMenu() = showSettings(GROUPS)

/** The drawer's tabs and folders, each opening its edit menu, then the ways to add more. */
private fun SettingsActivity.groupsPage(list: LinearLayout) {
    val groups = home.drawer.groups()
    fun row(icon: String, title: String, info: String, onClick: (View) -> Unit) =
        listItem(symbol(icon, m3.onPrimaryContainer), title, info, tonalIcon = true, onClick = onClick)
    // Each tab and folder has a handle to drag it into a new place in its list, as Nova's (and Move up/down for TalkBack).
    fun groupRows(type: String, icon: String): List<View> {
        val ofType = groups.filter { it.type == type }
        val rows = ofType.map { g -> row(icon, g.name, resources.getQuantityString(R.plurals.group_apps, g.apps.size, g.apps.size)) { editGroup(g) } }
        val reorder = Reorder(this, rows) { from, to -> saveGroups(reorderGroups(home.drawer.groups(), type, from, to)) }
        if (rows.size > 1) rows.forEach { (it as LinearLayout).addView(reorder.handle(it), LinearLayout.LayoutParams(-2, -2).apply { marginStart = (8 * dp).toInt() }) }
        return rows
    }
    // All apps leads the tabs; it can be hidden while there's another tab for the drawer to open on.
    val hasTabs = groups.any { it.type == "tab" }
    val allShown = !store.data.getBoolean(ALL_HIDDEN, false) || !hasTabs
    val allToggle = Toggle(this, allShown)
    val allRow = listItem(symbol("apps", m3.onPrimaryContainer), getString(R.string.tab_all),
        getString(if (!hasTabs) R.string.tab_all_needs_tab else if (allShown) R.string.tab_all_shown else R.string.tab_all_hidden),
        trailing = allToggle, tonalIcon = true) {
        if (!hasTabs) return@listItem
        allToggle.checked = !allToggle.checked
        store.data.edit().putBoolean(ALL_HIDDEN, !allToggle.checked).apply()
        home.drawer.refresh()
        refresh()
    }.apply { if (!hasTabs) allToggle.alpha = 0.38f }
    val tabs = groupRows("tab", "tabs")
    val folders = groupRows("folder", "folder")
    if (groups.isEmpty()) list.addView(text(getString(R.string.groups_empty), 14f, m3.onSurfaceVariant).apply {
        setPadding((32 * dp).toInt(), (8 * dp).toInt(), (32 * dp).toInt(), 0)
    })
    panel(list, getString(R.string.groups_panel_tabs), listOf(allRow) + tabs)
    if (folders.isNotEmpty()) panel(list, getString(R.string.groups_panel_folders), folders)
    panel(list, getString(R.string.groups_panel_add), listOf(
        row("tabs", getString(R.string.new_tab), getString(R.string.new_tab_info)) { newGroup("tab") },
        row("folder", getString(R.string.new_folder), getString(R.string.new_folder_info)) { newGroup("folder") },
        row("sparkle", getString(R.string.smart_folders), getString(R.string.smart_folders_info)) { smartFolders() },
    ))
}

fun VulcanActivity.askName(title: String, current: String, onName: (String) -> Unit) {
    val input = field(current, getString(R.string.name))
    val box = android.widget.FrameLayout(this).apply { setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0); addView(input) }
    sheet().setTitle(title).setView(box)
        .setPositiveButton(R.string.ok) { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onName) }
        .setNegativeButton(R.string.cancel, null).show()
    // Ready to type, as Nova's name dialogs: the field focused and the keyboard up.
    input.requestFocus()
    input.postDelayed({ getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(input, 0) }, 150)
}

private fun SettingsActivity.newGroup(type: String) = askName(getString(if (type == "tab") R.string.new_tab else R.string.new_folder), "") { name ->
    val groups = home.drawer.groups()
    if (groups.any { it.name == name }) { toast(getString(R.string.group_exists)); return@askName }
    chooseApps(name, emptyList(), groups, name) { apps, rest -> saveGroups(rest + Group(name, type, apps)) }
}

/**
 * Group [name]'s apps: those still ticked keep their place (a tab's arranged order), new ones go at the end. New ones that
 * are in other groups too can be moved here in one go, leaving those groups ([onDone] gets [groups] with them taken out).
 */
private fun VulcanActivity.chooseApps(title: String, current: List<String>, groups: List<Group>, name: String, onDone: (List<String>, List<Group>) -> Unit) {
    val others = groupsOfApps(groups, name)
    appSwitches(title, current, others) { chosen ->
        val apps = current.filter { it in chosen } + chosen.filter { it !in current }
        val shared = chosen.filter { it !in current && it in others }.toSet()
        if (shared.isEmpty()) return@appSwitches onDone(apps, groups)
        sheet().setTitle(R.string.move_apps_title)
            .setMessage(resources.getQuantityString(R.plurals.move_apps_message, shared.size, shared.size))
            .setPositiveButton(R.string.move_apps_move) { _, _ -> onDone(apps, withoutApps(groups, shared, name)) }
            .setNegativeButton(R.string.move_apps_keep) { _, _ -> onDone(apps, groups) }
            .show()
    }
}

fun VulcanActivity.editGroup(g: Group) {
    val groups = home.drawer.groups()
    fun replace(n: Group?) = saveGroups(groups.mapNotNull { if (it.name == g.name) n else it })
    val other = if (g.type == "tab") "folder" else "tab"
    // Tabs move along the drawer's tab row, folders along the grid: left is earlier, except in right-to-left languages.
    val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    fun move(sym: String, title: Int, by: Int) = moveGroup(groups, g.name, if (rtl) -by else by)
        ?.let { Triple(symbol(sym), getString(title)) { saveGroups(it) } }
    val actions = listOfNotNull(
        Triple(symbol("edit"), getString(R.string.rename)) { askName(getString(R.string.rename), g.name) { replace(g.copy(name = it)) } },
        Triple(symbol("apps"), getString(R.string.choose_apps)) {
            chooseApps(g.name, g.apps, groups, g.name) { apps, rest -> saveGroups(rest.map { if (it.name == g.name) it.copy(apps = apps) else it }) }
        },
        if (g.type == "tab") Triple(symbol("drag"), getString(R.string.arrange_apps)) { arrangeTab(g.name, g.apps) } else null,
        // A tab's own colour, as Nova's: it fills the tab while it's selected (Drawer.buildTabs). No setting of its own (#49).
        if (g.type == "tab") Triple(colorDot(g.color?.let(Color::parseColor) ?: tabFill()), getString(R.string.tab_color)) {
            pickList(getString(R.string.tab_color), listOf(colorDot(tabFill()) to getString(R.string.color_from_theme)) +
                COLOR_VALUES.zip(COLOR_NAMES).map { (v, n) -> colorDot(Color.parseColor(v)) to getString(n) }) { i ->
                replace(g.copy(color = COLOR_VALUES.getOrNull(i - 1)))
            }
        } else null,
        move("left", R.string.move_left, -1),
        move("right", R.string.move_right, 1),
        Triple(symbol(other.replace("tab", "tabs")), getString(if (other == "tab") R.string.convert_to_tab else R.string.convert_to_folder)) { replace(g.copy(type = other)) },
        Triple(symbol("delete"), getString(R.string.delete)) { replace(null) },
    )
    pickList(g.name, actions.map { it.first to it.second }) { actions[it].third() }
}

/** The selected tab's fill when the tab has no colour of its own. */
fun VulcanActivity.tabFill() = if (m3.housing) m3.raised else m3.secondaryContainer

private fun VulcanActivity.colorDot(c: Int) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL; setColor(c); setStroke(dp.toInt(), m3.outlineVariant); setSize((24 * dp).toInt(), (24 * dp).toInt())
}

fun VulcanActivity.addToGroup(key: String) {
    val groups = home.drawer.groups()
    // A checklist: the app's groups start ticked, so unticking one takes it out.
    val checked = BooleanArray(groups.size) { key in groups[it].apps }
    fun save(extra: List<Group> = emptyList()) = saveGroups(groups.mapIndexed { i, g -> when {
        checked[i] && key !in g.apps -> g.copy(apps = g.apps + key)
        !checked[i] -> g.copy(apps = g.apps - key)
        else -> g
    } } + extra)
    sheet().setTitle(R.string.add_to_group)
        .setMultiChoiceItems(groups.map { it.name }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
        .setPositiveButton(R.string.ok) { _, _ -> save() }
        .setNegativeButton(R.string.cancel, null)
        .setNeutralButton(R.string.new_folder) { _, _ -> askName(getString(R.string.new_folder), "") { save(listOf(Group(it, "folder", listOf(key)))) } }
        .show()
}

/** Groups apps into drawer folders by category (see [smartCategory]); run again, it tops up the folders it made. */
private fun SettingsActivity.smartFolders() {
    // Apps whose intent filters say what they are, for those that declare no category.
    fun role(cat: Int, vararg intents: Intent) = intents.flatMap { packageManager.queryIntentActivities(it, 0) }.map { it.activityInfo.packageName to cat }
    fun main(c: String) = Intent(Intent.ACTION_MAIN).addCategory(c)
    val roles = (role(CAT_TOOLS, main(Intent.CATEGORY_APP_CALCULATOR), main(Intent.CATEGORY_APP_FILES)) +
        role(ApplicationInfo.CATEGORY_NEWS, main(Intent.CATEGORY_APP_WEATHER)) +
        role(ApplicationInfo.CATEGORY_PRODUCTIVITY, main(Intent.CATEGORY_APP_CALENDAR), main(Intent.CATEGORY_APP_EMAIL),
            Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:"))) +
        role(ApplicationInfo.CATEGORY_SOCIAL, main(Intent.CATEGORY_APP_MESSAGING), main(Intent.CATEGORY_APP_CONTACTS),
            Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:")), Intent(Intent.ACTION_DIAL)) +
        role(ApplicationInfo.CATEGORY_MAPS, main(Intent.CATEGORY_APP_MAPS)) +
        role(ApplicationInfo.CATEGORY_AUDIO, main(Intent.CATEGORY_APP_MUSIC)) +
        role(CAT_HEALTH, main(Intent.CATEGORY_APP_FITNESS)) +
        role(ApplicationInfo.CATEGORY_IMAGE, main(Intent.CATEGORY_APP_GALLERY), Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE))
        ).reversed().toMap() // the first role listed wins
    val names = mapOf(CAT_FINANCE to R.string.cat_finance, CAT_SHOPPING to R.string.cat_shopping, CAT_HEALTH to R.string.cat_health, CAT_TOOLS to R.string.cat_tools)
    @Suppress("DEPRECATION") val byName = home.apps.values.mapNotNull { a ->
        val ai = a.info.applicationInfo
        val cat = smartCategory(ai.category, ai.flags and ApplicationInfo.FLAG_IS_GAME != 0, roles[a.pkg], a.pkg, a.title,
            ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) ?: return@mapNotNull null
        val name = names[cat]?.let(::getString) ?: ApplicationInfo.getCategoryTitle(this, cat)?.toString() ?: return@mapNotNull null
        name to a
    }.groupBy({ it.first }, { it.second }).mapValues { (_, l) -> l.sortedBy { it.title.lowercase() }.map { it.key } }
    val (groups, made, added) = smartGroups(home.drawer.groups(), byName)
    when {
        made > 0 -> toast(resources.getQuantityString(R.plurals.created_folders, made, made))
        added > 0 -> toast(resources.getQuantityString(R.plurals.added_to_folders, added, added))
        else -> { toast(getString(R.string.no_new_categories)); return }
    }
    saveGroups(groups)
}

// ---- Backup, permissions ----

@Suppress("DEPRECATION")
private fun SettingsActivity.backupPage(list: LinearLayout) {
    fun row(icon: String, title: Int, info: CharSequence, onClick: ((View) -> Unit)?) =
        listItem(symbol(icon, m3.onPrimaryContainer), getString(title), info, tonalIcon = true, onClick = onClick)
    fun pickFile(req: Int) = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), req)
    panel(list, getString(R.string.backup_panel_save), listOf(row("download", R.string.backup_to_file, getString(R.string.backup_to_file_info)) {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, "vulcan-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.json"), MainActivity.REQ_BACKUP)
    }))
    val autos = store.autoBackups.filter { it.exists() }
    val autoRows = autos.map { f ->
        val saved = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(f.lastModified()))
        row("clock", R.string.restore_auto, getString(R.string.restore_auto_info, saved)) {
            dialog().setMessage(getString(R.string.restore_auto_confirm, saved))
                .setPositiveButton(R.string.restore) { _, _ ->
                    runCatching { store.import(f.readText(), home::mainActivity) }
                        .onSuccess { if (it.replacedLayout) home.dropForeignWidgets(); restartHome() }
                        .onFailure { toast(getString(R.string.restore_failed, it.message)) }
                }.setNegativeButton(R.string.cancel, null).show()
        }
    }.ifEmpty { listOf(row("clock", R.string.restore_auto_none, getString(R.string.restore_auto_none_info), null)) }
    panel(list, getString(R.string.backup_panel_restore), listOf(
        row("refresh", R.string.restore_from_file, getString(R.string.restore_from_file_info)) { pickFile(MainActivity.REQ_RESTORE) },
        row("link", R.string.apply_style_link, getString(R.string.apply_style_info)) { pasteStyle() },
    ) + autoRows)
    panel(list, getString(R.string.backup_panel_other), listOf(row("archive", R.string.import_launcher_backup, getString(R.string.import_launcher_backup_info)) {
        pickFile(MainActivity.REQ_DOC)
    }))
    panel(list, getString(R.string.backup_panel_reset), listOf(row("delete", R.string.reset_defaults, getString(R.string.reset_defaults_info)) {
        dialog().setMessage(R.string.reset_confirm)
            .setPositiveButton(R.string.reset) { _, _ ->
                home.items.flatMap { it.widgetIds }.forEach(home.host::deleteAppWidgetId)
                store.reset()
                restartHome()
            }.setNegativeButton(R.string.cancel, null).show()
    }))
}

/** Restores a backup or applies an older style file, then [restart] rebuilds home. */
fun VulcanActivity.restoreBackup(uri: android.net.Uri, restart: () -> Unit) {
    runCatching { store.import(contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }, home::mainActivity) }
        .onSuccess { result ->
            if (result.rejected.isNotEmpty()) toast(R.string.skipped_settings, result.rejected.joinToString())
            // Widget IDs belong to the install that made them; drop the ones this install doesn't own.
            if (result.replacedLayout) home.dropForeignWidgets()
            restart()
        }.onFailure { toast(R.string.restore_failed, it.message) }
}

/** One plain paragraph: what replaces home, and what comes back differently or not at all. */
fun VulcanActivity.importNote(layout: ImportedLayout, lead: Boolean = true, separator: String = " ") = listOfNotNull(
    if (lead) resources.getQuantityString(R.plurals.import_items, layout.items.size, layout.items.size) else null,
    if (layout.widgets > 0) resources.getQuantityString(R.plurals.import_widgets, layout.widgets, layout.widgets) else null,
    if (layout.skipped > 0) resources.getQuantityString(R.plurals.import_skipped, layout.skipped, layout.skipped) else null,
).joinToString(separator)

/** Stores an imported layout and its grid settings in place of the current ones; home is rebuilt after (restart). */
fun VulcanActivity.applyImport(layout: ImportedLayout, settings: Map<String, String>) {
    home.items.flatMap { it.widgetIds }.forEach(home.host::deleteAppWidgetId)
    settings.forEach { (k, v) -> looks[k] = v }
    looks["subgrid"] = if (layout.subgrid) "on" else "off"
    val pages = (layout.items.filter { !isDock(it.page) }.maxOfOrNull { it.page } ?: 0) + 1
    // The backup's own page count when it has one, else just enough for its items: never this home's old empty pages.
    looks["pages"] = maxOf(pages, settings["pages"]?.toIntOrNull() ?: 1).coerceAtMost(9).toString()
    store.save(layout.items, if (layout.subgrid) 2 else 1)
    if (layout.widgets > 0) store.data.edit().putBoolean(RESTORING_WIDGETS, true).commit()
    pinsByApp(layout.items, settings.values).forEach { (key, ids) ->
        runCatching { home.launcherApps.pinShortcuts(key.first, ids, home.userOf(key.second)) }
    }
}

/** What Vulcan may reach outside itself, each with its state; a row opens the Android page that changes it. */
private fun SettingsActivity.permissionsPage(list: LinearLayout) {
    val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
    val isHome = runCatching { home.launcherApps.hasShortcutHostPermission() }.getOrDefault(false)
    data class Perm(val icon: String, val title: Int, val info: Int, val on: Boolean, val action: String, val onText: Int = R.string.perm_allowed, val offText: Int = R.string.perm_allow)
    val perms = listOf(
        Perm("notifications", R.string.perm_notifications, R.string.perm_notifications_info,
            ComponentName(this, BadgeService::class.java).flattenToString() in listeners, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        Perm("lock", R.string.perm_accessibility, R.string.perm_accessibility_info, ActionService.instance != null, Settings.ACTION_ACCESSIBILITY_SETTINGS),
        Perm("home", R.string.perm_default, R.string.perm_default_info, isHome, Settings.ACTION_HOME_SETTINGS, R.string.perm_is_default, R.string.perm_set_default),
    )
    list.addView(text(getString(R.string.perm_intro), 14f, m3.onSurfaceVariant).apply {
        setPadding((32 * dp).toInt(), (8 * dp).toInt(), (32 * dp).toInt(), 0)
    })
    panel(list, getString(R.string.perm_count, perms.count { it.on }, perms.size), perms.map { p ->
        val open = {
            if (p.action == Settings.ACTION_ACCESSIBILITY_SETTINGS && !p.on) requestActionAccess()
            else startActivity(Intent(p.action))
        }
        // Granted: a quiet check and a word. Not granted: the one button on the row.
        val state = if (p.on) LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply { setImageDrawable(symbol("check", m3.onSurfaceVariant, 18f)) })
            addView(text(getString(p.onText), 14f, m3.onSurfaceVariant).apply { setPadding((4 * dp).toInt(), 0, 0, 0) })
        } else tonalButton(getString(p.offText), open)
        listItem(symbol(p.icon, m3.onPrimaryContainer), getString(p.title), getString(p.info), trailing = state, tonalIcon = true) { open() }
    })
}
