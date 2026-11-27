package dev.vulcanlauncher

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
    BADGES to ("badge" to R.string.cat_badges),
    GESTURES to ("gesture" to R.string.cat_gestures),
    NOW to ("sun" to R.string.cat_now),
    SEARCH_GROUP to ("search" to R.string.cat_search),
)

// Launcher pages under settings home, beside the categories.
const val BACKUP = "Backup"
const val PERMISSIONS = "Permissions"

/** Colors sits under Look & feel and Dock under Home screen, as in Nova; Tabs and Folders under App drawer. */
private val PARENT = mapOf(COLORS to LOOK, DOCK_GROUP to HOME, TABS_GROUP to DRAWER, DRAWER_FOLDERS to DRAWER)
private fun depth(page: String) = when (page) { "" -> 0; in PARENT -> 2; else -> 1 }

/** Where each settings page was scrolled when it was left for a deeper one, so back returns to the same spot. */
private val leftAt = mutableMapOf<String, Int>()

/** Opens settings at [page] ("" for settings home). */
fun MainActivity.showSettings(page: String = "") {
    // Its own task, as in Nova and Lawnchair: in home's task the Home gesture animated home away with it (#100).
    // Cleared each time so it opens on [page], not where it was left.
    startActivity(Intent(this, SettingsActivity::class.java).putExtra("page", page)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
}

class SettingsActivity : VulcanActivity() {
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

    /**
     * A setting changed: home is built again when it shows unless [reload] says it reads the setting when used, and this
     * page now, in the new colours and where it was.
     */
    fun changed(reload: Reload = Reload.REBUILD) {
        if (reload == Reload.REBUILD) home.stale = true
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

    /**
     * A permission a setting needs was refused. Once Android stops asking (after the second refusal), the request
     * returns at once with nothing shown, so the switch looked dead: offer Vulcan's App info, where it can be allowed.
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val perm = permissions.firstOrNull() ?: return
        if (grantResults.firstOrNull() != android.content.pm.PackageManager.PERMISSION_DENIED || shouldShowRequestPermissionRationale(perm)) return
        dialog().setMessage(R.string.permission_blocked).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.app_info) { _, _ ->
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null))) }
        }.show()
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
            MainActivity.REQ_BACKUP -> runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(store.export(home.repo.apps.values.toList()).toByteArray()) } }
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
    when (page) { "" -> search = settingsHome(list); COLORS -> colorsPage(list); BACKUP -> backupPage(list); PERMISSIONS -> permissionsPage(list); TABS_GROUP -> tabsPage(list); DRAWER_FOLDERS -> foldersPage(list); CONTRIBUTORS_PAGE -> contributorsPage(list); else -> settingsCategory(page, list) }
    val scrollView = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }
    val title = text(getString(when (page) { "" -> R.string.settings; COLORS -> R.string.custom_colors; BACKUP -> R.string.backup_restore; PERMISSIONS -> R.string.permissions; CONTRIBUTORS_PAGE -> R.string.contributors; else -> GROUP_TITLES.getValue(page) }), 22f).apply { setPaddingRelative((8 * dp).toInt(), 0, 0, 0); maxLines = 1 }
    // Settings home, as One UI: a search button in the bar turns the title into the search field.
    val field = search?.let { onQuery -> EditText(this).apply {
        visibility = View.GONE
        hint = getString(R.string.settings_search)
        setSingleLine()
        textSize = 18f
        setTextColor(m3.onSurface)
        setHintTextColor(m3.onSurfaceVariant)
        background = null
        setPaddingRelative((8 * dp).toInt(), 0, 0, 0)
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
            minimumHeight = (64 * dp).toInt() // not a fixed height: a large font scale would cut the title off
            setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
            addView(iconButton("back", getString(R.string.back), m3.onSurface) { back.run() })
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            field?.let { addView(it, LinearLayout.LayoutParams(0, -1, 1f)) }
            searchButton?.let { addView(it) }
        }, LinearLayout.LayoutParams(-1, -2))
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
    // Pages slide; Android's "Remove animations" turns this off along with the system's own transitions.
    // From the end side, mirrored in a right-to-left language.
    val shift = 32 * dp * if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
    when {
        // The first page comes with the screen, in the system's own animation.
        old == null -> {}
        // A setting changed, or the page is refreshed: at once.
        restoring || samePage -> pages.removeView(old)
        // The parent stays put under it.
        backward -> {
            old.animate().alpha(0f).translationX(shift).setDuration(150).setInterpolator(EASE).withEndAction { pages.removeView(old) }.start()
        }
        else -> {
            panel.alpha = 0f
            panel.translationX = shift
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
            val hits = (LOOKS + PAGE_CHOICES).filter { c ->
                c.group in GROUP_TITLES && shows(c) && listOf(c.title, c.info, GROUP_TITLES.getValue(c.group)).any { it != 0 && getString(it).contains(q.trim(), true) }
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
    panel(list, getString(R.string.panel_style), listOf(LOOK).map(::category) +
        listItem(symbol("link", m3.onPrimaryContainer), getString(R.string.share_style_link),
            getString(R.string.share_style_info), tonalIcon = true) { shareStyle() })
    panel(list, getString(R.string.panel_launcher), listOf(
        listItem(symbol("archive", m3.onPrimaryContainer), getString(R.string.backup_restore), getString(R.string.backup_restore_info), tonalIcon = true) { showPage(BACKUP) },
        listItem(symbol("shield", m3.onPrimaryContainer), getString(R.string.permissions), getString(R.string.permissions_info), tonalIcon = true) { showPage(PERMISSIONS) },
        listItem(symbol("home", m3.onPrimaryContainer), getString(R.string.default_home), getString(if (runCatching { home.launcherApps.hasShortcutHostPermission() }.getOrDefault(false)) R.string.default_home_is else R.string.default_home_info), tonalIcon = true) {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        },
        listItem(symbol("refresh", m3.onPrimaryContainer), getString(R.string.restart), null, tonalIcon = true) { restartHome() },
        listItem(symbol("person", m3.onPrimaryContainer), getString(R.string.contributors), getString(R.string.contrib_info), tonalIcon = true) { showPage(CONTRIBUTORS_PAGE) },
        listItem(symbol("info", m3.onPrimaryContainer), getString(R.string.about), null, tonalIcon = true) {
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
    if (title != null) list.addView(legend(title, 12f).apply { setPaddingRelative((32 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt()) })
    list.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        housing(this, PANEL_RADIUS * dp)
        elevation = PANEL_REST * dp
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
    housing(this, PANEL_RADIUS * dp)
    elevation = PANEL_REST * dp
    setPadding((20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt())
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(getString(R.string.app_name), 34f).apply {
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            letterSpacing = -0.02f
        })
        addView(legend(getString(R.string.version, versionName), 11f, m3.onSurfaceVariant).apply { setPadding(0, (6 * dp).toInt(), 0, 0) })
    }, LinearLayout.LayoutParams(0, -2, 1f))
    addView(ImageView(context).apply {
        setImageResource(R.mipmap.ic_launcher)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams((64 * dp).toInt(), (64 * dp).toInt()).apply {
        marginStart = (16 * dp).toInt()
    })
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt()) }
}

/** Whether a setting has a row now. Schedule hours only where they're used: the schedule, and sunset to sunrise until a location is known. */
private fun SettingsActivity.shows(c: Choice) =
    c.key != "nightStart" && c.key != "nightEnd" || looks.str("nightMode") in listOf("schedule", "sun")

private fun SettingsActivity.settingsCategory(group: String, list: LinearLayout) {
    val choices = (if (group == LOOK) listOf(iconPackChoice()) else emptyList()) + LOOKS.filter { it.group == group && shows(it) }
    fun pickNamed(c: Choice) {
        val names = c.values.indices.map { choiceName(c, it) }
        val checked = c.values.indexOf(looks[c])
        val onPick = android.content.DialogInterface.OnClickListener { d, j -> d.dismiss(); applyChoice(c, c.values[j]); changed(c.reload) }
        val d = sheet().setTitle(c.title).setSingleChoiceItems(names.toTypedArray(), checked, onPick)
            .setNegativeButton(R.string.cancel, null).create()
        // Badge and icon shapes are easier to pick from a sample than from a name (#82): wrap the sheet's own rows to keep their style.
        if (c.key == "badgeShape" || c.key == "shape") {
            d.create()
            val rows = d.listView.adapter
            d.listView.adapter = object : BaseAdapter() {
                override fun getCount() = rows.count
                override fun getItem(i: Int) = rows.getItem(i)
                override fun getItemId(i: Int) = rows.getItemId(i)
                override fun getView(i: Int, v: View?, parent: ViewGroup) = rows.getView(i, v, parent).also {
                    (it as android.widget.TextView).apply { // the start drawable is the row's radio button
                        val radio = compoundDrawablesRelative[0]
                        setCompoundDrawablesRelative(radio, null, if (c.key == "shape") shapeSample(c.values[i]) else badgeSample(c.values[i]), null)
                    }
                }
            }
            d.listView.setItemChecked(checked, true)
        }
        d.show()
    }
    // Colours (but the theme colour) get the colour sheet, with a sample of the part.
    fun pick(c: Choice) = when {
        c.key == "accent" -> pickTheme(c) { applyChoice(c, it); changed() }
        "#FFFFFF" in c.values -> pickColor(c) { applyChoice(c, it); changed() }
        else -> pickNamed(c)
    }
    fun row(c: Choice): View = when {
        // The housing dock has its own shadow; no background has none to cast.
        c.key == "dockShadow" && looks.str("dockBg") in listOf("housing", "none") ->
            listItem(null, getString(c.title), getString(if (looks.str("dockBg") == "none") R.string.dock_shadow_no_bg else R.string.dock_shadow_housing)) {}.apply { alpha = 0.5f }
        c.values.toSet() == setOf("on", "off") -> {
            val toggle = Toggle(this, looks[c] == "on")
            listItem(null, getString(c.title), if (c.info != 0) getString(c.info) else null, trailing = toggle) {
                toggle.checked = !toggle.checked
                applyChoice(c, if (toggle.checked) "on" else "off")
            }.apply { readAsSwitch(toggle) }
        }
        c.action -> listItem(null, getString(c.title), valueName(c)) { pickAction(getString(c.title)) { looks[c] = it; changed(c.reload) } }
        // Settings the chosen dock background doesn't use: shown dimmed and saying so, still pickable.
        c.key == "dockAlpha" && looks.str("dockBg") in listOf("housing", "glass", "none") || c.key == "dockColor" && looks.str("dockBg") == "none" ->
            listItem(null, getString(c.title), if (looks.str("dockBg") == "none") getString(R.string.dock_shadow_no_bg)
                else getString(R.string.not_used_by_bg, valueName(LOOKS.first { it.key == "dockBg" }))) { pick(c) }.apply { alpha = 0.5f }
        // Badge options while badges are off: dimmed and saying so, still pickable, as the dock's.
        c.group == BADGES && c.key != "badges" && looks.str("badges") == "off" ->
            listItem(null, getString(c.title), getString(R.string.badges_off_unused)) { pick(c) }.apply { alpha = 0.5f }
        // AMOLED Black is always dark, whatever the panel theme says.
        c.key == "drawerTheme" && looks.str("accent") == "amoled" ->
            listItem(null, getString(c.title), getString(R.string.panels_amoled)) { pick(c) }.apply { alpha = 0.5f }
        // One page leaves nothing to choose between.
        c.key == "defaultPage" && home.pageLayout.pages <= 1 ->
            listItem(null, getString(c.title), getString(R.string.default_page_one)) {}.apply { alpha = 0.5f }
        c.key == "pageMargin" -> paddingRow(c)
        c.values.size > 2 && c.values.all { it.toIntOrNull() != null } -> slider(c)
        else -> listItem(null, getString(c.title), valueName(c), trailing = swatch(c)) { pick(c) }
    }.apply { tag = c.key }
    val byKey = choices.associateBy { it.key }
    fun link(key: String): View? = when (key) {
        "@pages" -> row(PAGES_CHOICE)
        "@defaultPage" -> row(DEFAULT_PAGE_CHOICE)
        "@dock" -> listItem(null, getString(R.string.group_dock), getString(R.string.cat_dock)) { showPage(DOCK_GROUP) }
        "@hidden" -> listItem(symbol("hide", m3.onPrimaryContainer), getString(R.string.hidden_apps), getString(R.string.hidden_apps_info), tonalIcon = true) { hideAppsMenu() }
        "@tabs" -> tabsLink()
        "@folders" -> foldersLink()
        // Not a setting: says how cards come and go, and how to move them.
        "@nowCards" -> text(getString(R.string.now_cards_info), 14f, m3.onSurfaceVariant).apply { setPaddingRelative((16 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), (12 * dp).toInt()) }
        "@colors" -> listItem(null, getString(R.string.custom_colors),
            resources.getQuantityString(R.plurals.custom_colors_set, looks.colors().size, looks.colors().size)) { showPage(COLORS) }
        else -> byKey[key]?.let(::row) // null: hidden for now (night hours)
    }
    val sections = SECTIONS[group] ?: return panel(list, null, choices.map(::row))
    for ((title, keys) in sections) keys.mapNotNull(::link).takeIf { it.isNotEmpty() }?.let { panel(list, getString(title), it) }
}

/** A numeric setting as an inline M3 slider, one stop per value, saved when the thumb is let go. */
/** [shape] as a filled 36 dp icon blank, for the icon shape sheet. */
private fun SettingsActivity.shapeSample(shape: String): Drawable = object : Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = m3.outline }
    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds; val inset = b.width() / 9
        canvas.drawPath(shapePath(shape, android.graphics.Rect(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)), paint)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}.apply { val px = (36 * dp).toInt(); setBounds(0, 0, px, px) }

/** A small icon wearing a badge of [shape], in the badge colour and position. */
private fun SettingsActivity.badgeSample(shape: String): Drawable {
    val color = looks.color("badgeColor"); val pos = looks.str("badgePos"); val iconColor = m3.primaryContainer
    val outline = badgeOutline(shape, pos)
    return object : Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val r = android.graphics.Rect()
        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds; val s = b.width() * 0.8f; val br = s * 0.2f
            val right = pos.endsWith("Right"); val top = pos.startsWith("top")
            val l = if (right) b.left.toFloat() else b.right - s
            val t = if (top) b.bottom - s else b.top.toFloat()
            paint.color = iconColor
            canvas.drawRoundRect(l, t, l + s, t + s, s * 0.25f, s * 0.25f, paint)
            val cx = if (right) l + s - br * 0.45f else l + br * 0.45f
            val cy = if (top) t + br * 0.45f else t + s - br * 0.45f
            r.set((cx - br).toInt(), (cy - br).toInt(), (cx + br).toInt(), (cy + br).toInt())
            paint.color = color
            canvas.drawPath(outlinePath(outline, r), paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }.apply { val px = (36 * dp).toInt(); setBounds(0, 0, px, px) }
}

private fun SettingsActivity.slider(c: Choice): View {
    val value = text(valueName(c), 14f, m3.onSurfaceVariant)
    val bar = android.widget.SeekBar(this).apply {
        max = if (c.key == "defaultPage") (home.pageLayout.pages - 1).coerceIn(1, c.values.size - 1) else c.values.size - 1
        progress = c.values.indexOf(valueOf(c)).coerceAtLeast(0)
        contentDescription = getString(c.title)
        val tint = android.content.res.ColorStateList.valueOf(m3.slider)
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
            override fun onStopTrackingTouch(b: android.widget.SeekBar) {
                dragging = false; applyChoice(c, c.values[b.progress])
                // Default page's range and its "one page" row follow Pages.
                // ponytail: not after a key or TalkBack move, where rebuilding the page would drop the focus; that row is right again when the page reopens.
                if (c.key == "pages") refresh()
            }
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

/**
 * Page padding (#96), as Nova's Padding: a row saying "24 × 16 dp" that opens a dialog with a phone over three
 * sliders, Left & right, Top and Bottom. The phone draws the home grid as it is set (columns, rows, dock) and moves
 * it in as a slider moves, the space it gives up tinted in the signal colour, so the change is seen before OK saves it.
 */
private fun SettingsActivity.paddingRow(c: Choice): View {
    fun name(v: String) = paddingOf(v).let { (x, t, b) ->
        if (x == 0 && t == 0 && b == 0) getString(R.string.none)
        else if (t == b) getString(R.string.padding_value, x, t) else getString(R.string.padding_value_ends, x, t, b)
    }
    return listItem(null, getString(c.title), name(looks[c])) {
        var (px, pt, pb) = paddingOf(looks[c])
        val steps = PADDING_STEPS
        val preview = PaddingPreview(this, looks.cols, looks.rows, looks.dockCols, looks.dockOn, looks.str("shape"), looks.on("labels"), looks.str("searchBar")).apply { set(px, pt, pb, animate = false) }
        fun bar(title: Int, start: Int, onMove: (Int) -> Unit): View {
            val value = text("$start dp", 14f, m3.onSurfaceVariant)
            val seek = android.widget.SeekBar(this).apply {
                max = steps.size - 1
                progress = steps.indexOf(start).coerceAtLeast(0)
                contentDescription = getString(title)
                val tint = android.content.res.ColorStateList.valueOf(m3.slider)
                progressTintList = tint; thumbTintList = tint; tickMarkTintList = tint
                tickMark = GradientDrawable().apply { shape = GradientDrawable.OVAL; setSize((4 * dp).toInt(), (4 * dp).toInt()) }
                if (android.os.Build.VERSION.SDK_INT >= 30) stateDescription = value.text
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(b: android.widget.SeekBar, i: Int, user: Boolean) {
                        value.text = "${steps[i]} dp"
                        if (android.os.Build.VERSION.SDK_INT >= 30) stateDescription = value.text
                        if (user) onMove(steps[i])
                    }
                    override fun onStartTrackingTouch(b: android.widget.SeekBar) {}
                    override fun onStopTrackingTouch(b: android.widget.SeekBar) {}
                })
            }
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(context).apply {
                    setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
                    addView(text(getString(title), 14f, m3.onSurface), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(value)
                })
                addView(seek, LinearLayout.LayoutParams(-1, (40 * dp).toInt()))
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), 0)
            // The phone in a recessed well, large enough to read the grid; the sliders under it, as Nova's.
            addView(android.widget.FrameLayout(context).apply {
                background = rounded(m3.well, 20 * dp)
                setPadding(0, (12 * dp).toInt(), 0, (16 * dp).toInt())
                addView(preview, android.widget.FrameLayout.LayoutParams(preview.width(), preview.height(), Gravity.CENTER_HORIZONTAL))
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins((8 * dp).toInt(), 0, (8 * dp).toInt(), (20 * dp).toInt()) })
            addView(bar(R.string.padding_sides, px) { px = it; preview.set(px, pt, pb) })
            addView(bar(R.string.padding_top, pt) { pt = it; preview.set(px, pt, pb) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
            addView(bar(R.string.padding_bottom, pb) { pb = it; preview.set(px, pt, pb) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
        }
        dialog().setTitle(c.title).setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                applyChoice(c, "${px}x${pt}x$pb")
                changed()
            }.show()
    }
}

/**
 * [paddingRow]'s phone, drawn as a spec sheet: the screen's own shape with its camera and status bar, the home grid
 * ([cols] x [rows] icons in the icon shape, label lines under them) inset by the padding, the space given up hatched
 * in the signal colour, and the dock under it, which padding leaves alone. Dimension callouts outside the body name
 * each side's padding. Eases to each new value.
 */
private class PaddingPreview(private val a: SettingsActivity, private val cols: Int, private val rows: Int,
                             private val dockCols: Int, private val dock: Boolean, shape: String,
                             private val labels: Boolean, private val search: String) : View(a) {
    private val dp = a.resources.displayMetrics.density
    private val screenW = a.resources.displayMetrics.widthPixels / dp
    private val screenH = a.resources.displayMetrics.heightPixels / dp
    // The phone, and the gutters either side of it and above it for the callouts.
    private val phoneW = 132 * dp
    private val phoneH = phoneW * screenH / screenW
    private val side = 44 * dp
    private val above = 8 * dp
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val type = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11 * a.resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val r = android.graphics.RectF()
    // The icon shape once, 100 px square; each icon scales it.
    private val icon = shapePath(shape, android.graphics.Rect(0, 0, 100, 100))
    private var x = 0f; private var yt = 0f; private var yb = 0f
    private var anim: android.animation.ValueAnimator? = null

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun width() = (phoneW + 2 * side).toInt()
    fun height() = (phoneH + above + 2 * dp).toInt()

    fun set(px: Int, pt: Int, pb: Int, animate: Boolean = true) {
        anim?.cancel()
        if (!animate) { x = px.toFloat(); yt = pt.toFloat(); yb = pb.toFloat(); invalidate(); return }
        val x0 = x; val t0 = yt; val b0 = yb
        anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = android.view.animation.DecelerateInterpolator(1.5f)
            addUpdateListener { val f = it.animatedValue as Float; x = x0 + (px - x0) * f; yt = t0 + (pt - t0) * f; yb = b0 + (pb - b0) * f; invalidate() }
            start()
        }
    }

    private fun alpha(c: Int, a: Int) = c and 0xFFFFFF or (a shl 24)

    private fun drawIcon(canvas: android.graphics.Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        paint.color = color
        canvas.save()
        canvas.translate(cx - size / 2, cy - size / 2)
        canvas.scale(size / 100f, size / 100f)
        canvas.drawPath(icon, paint)
        canvas.restore()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val m3 = a.m3
        val s = phoneW / screenW // px per screen dp
        val edge = 1.5f * dp
        val corner = 16 * dp
        val pl = side; val pt = above; val pr = pl + phoneW; val pb = pt + phoneH
        paint.style = android.graphics.Paint.Style.FILL

        // The body: the panel colour, a touch lighter at the top, as lit from above.
        paint.shader = android.graphics.LinearGradient(0f, pt, 0f, pb, m3.raised, m3.surfaceHigh, android.graphics.Shader.TileMode.CLAMP)
        r.set(pl, pt, pr, pb)
        canvas.drawRoundRect(r, corner, corner, paint)
        paint.shader = null

        // Status bar: the time on the left, the camera in the middle, two dots on the right.
        val sy = pt + 9 * dp
        paint.color = alpha(m3.onSurfaceVariant, 0x99)
        r.set(pl + 10 * dp, sy - dp, pl + 22 * dp, sy + dp); canvas.drawRoundRect(r, dp, dp, paint)
        canvas.drawCircle(pr - 12 * dp, sy, 1.6f * dp, paint); canvas.drawCircle(pr - 17 * dp, sy, 1.6f * dp, paint)
        paint.color = m3.onSurface
        canvas.drawCircle((pl + pr) / 2, sy, 2.4f * dp, paint)

        // Where the pages sit: under the status bar, over the dock (or the navigation bar without one).
        // The search bar takes a 56 dp row: over or under the pages, or over or under the dock's icons.
        val bar = 56 * s
        val dockSearch = dock && search in listOf("dockAbove", "dockBelow")
        val top = pt + 28 * s + (if (search == "top") bar else 0f)
        val dockTop = pb - (if (dock) 112 else 28) * s - (if (dockSearch) bar else 0f)
        val bottom = dockTop - (if (search == "bottom") bar else 0f)
        val il = pl + x * s; val ir = pr - x * s; val it = top + yt * s; val ib = bottom - yb * s

        // The space the padding gives up: a light wash, hatched at 45 degrees, in the signal colour.
        if (x > 0.5f || yt > 0.5f || yb > 0.5f) {
            canvas.save()
            canvas.clipRect(pl, top, pr, bottom)
            canvas.clipOutRect(il, it, ir, ib)
            paint.color = alpha(m3.signal, 0x24)
            canvas.drawRect(pl, top, pr, bottom, paint)
            paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = dp; paint.color = alpha(m3.signal, 0x8C)
            val step = 5 * dp
            var d = -(bottom - top)
            while (d < phoneW) { canvas.drawLine(pl + d, bottom, pl + d + (bottom - top), top, paint); d += step }
            canvas.restore()
            // The page's new edge, in the signal itself.
            paint.color = m3.signal; paint.strokeWidth = 1.2f * dp
            r.set(il, it, ir, ib); canvas.drawRoundRect(r, 4 * dp, 4 * dp, paint)
            paint.style = android.graphics.Paint.Style.FILL
        }

        // The grid: an icon in the icon shape per column and row, a label line under each.
        val cw = (ir - il) / cols; val ch = (ib - it) / rows
        val size = minOf(cw, ch * (if (labels) 0.72f else 1f)) * 0.62f
        for (i in 0 until cols) for (j in 0 until rows) {
            val cx = il + cw * (i + 0.5f); val cy = it + ch * (j + 0.5f) - (if (labels) size * 0.18f else 0f)
            drawIcon(canvas, cx, cy, size, alpha(m3.onSurfaceVariant, if ((i + j) % 3 == 0) 0x73 else 0x52))
            if (labels) {
                paint.color = alpha(m3.onSurfaceVariant, 0x40)
                r.set(cx - size * 0.38f, cy + size * 0.66f, cx + size * 0.38f, cy + size * 0.66f + 1.2f * dp)
                canvas.drawRoundRect(r, dp, dp, paint)
            }
        }

        // The dock, untouched by the padding: a row of icons across the full width, then the gesture bar.
        if (dock) {
            val dw = (phoneW - 2 * edge) / dockCols; val dc = minOf(dw, 64 * s) * 0.66f
            val dy = pb - 64 * s - (if (search == "dockBelow") bar else 0f)
            for (i in 0 until dockCols) drawIcon(canvas, pl + edge + dw * (i + 0.5f), dy, dc, alpha(m3.onSurfaceVariant, 0xB3))
        }
        // The search bar, a pill in its row; like the dock, the padding leaves it alone.
        val sy2 = when (search) {
            "top" -> top - bar / 2
            "bottom" -> bottom + bar / 2
            "dockAbove" -> if (dock) dockTop + bar / 2 else null
            "dockBelow" -> if (dock) pb - 28 * s - bar / 2 else null
            else -> null
        }
        if (sy2 != null) {
            paint.color = alpha(m3.onSurfaceVariant, 0x52)
            r.set(pl + 16 * s, sy2 - 18 * s, pr - 16 * s, sy2 + 18 * s); canvas.drawRoundRect(r, 18 * s, 18 * s, paint)
        }
        paint.color = alpha(m3.onSurface, 0x66)
        r.set((pl + pr) / 2 - 14 * dp, pb - 6 * dp, (pl + pr) / 2 + 14 * dp, pb - 4.5f * dp); canvas.drawRoundRect(r, dp, dp, paint)

        // The outline last, over the corners.
        paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = edge; paint.color = m3.outline
        r.set(pl + edge / 2, pt + edge / 2, pr - edge / 2, pb - edge / 2)
        canvas.drawRoundRect(r, corner, corner, paint)

        // Callouts, as on a drawing: Left & right beside the left edge at mid height, Top and Bottom beside the right
        // edge, each at its own band. A leader runs from the number to the band it names; each fades in from 0.
        fun callout(value: Float, left: Boolean, ly: Float) {
            val shown = (value / 8f).coerceIn(0f, 1f)
            if (shown == 0f) return
            val text = "${kotlin.math.round(value).toInt()} dp"
            type.color = alpha(m3.signalInk, (shown * 255).toInt())
            type.textAlign = if (left) android.graphics.Paint.Align.RIGHT else android.graphics.Paint.Align.LEFT
            val tx = if (left) pl - 10 * dp else pr + 10 * dp
            canvas.drawText(text, tx, ly - (type.ascent() + type.descent()) / 2, type)
            paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = dp; paint.color = alpha(m3.signal, (shown * 255).toInt())
            val from = if (left) pl - 7 * dp else pr + 7 * dp
            val to = if (left) pl + value * s / 2 else pr - 6 * dp
            canvas.drawLine(from, ly, to, ly, paint)
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawCircle(to, ly, 1.6f * dp, paint)
        }
        callout(x, true, (top + bottom) / 2)
        callout(yt, false, top + yt * s / 2)
        callout(yb, false, bottom - yb * s / 2)
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
            // A visible ring: a housing-coloured swatch on a housing card was otherwise invisible. Not the outline:
            // it vanished on dark cards and matched the Dividers swatch.
            setStroke((1.5f * dp).toInt(), m3.onSurfaceVariant)
        }
        layoutParams = LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt())
    }
}

private fun SettingsActivity.iconPackChoice(): Choice {
    val packs = iconPacks(this)
    return Choice(LOOK, "iconPack", R.string.set_iconPack, "", listOf("") + packs.map { it.first }, shown = listOf(getString(R.string.iconPack_system)) + packs.map { it.second })
}

/** [c]'s value now: the page settings are home's layout's, the rest are in [Looks]. */
private fun SettingsActivity.valueOf(c: Choice) = when (c) {
    PAGES_CHOICE -> home.pageLayout.pages.toString()
    DEFAULT_PAGE_CHOICE -> home.pageLayout.defaultPage.toString()
    else -> looks[c]
}

private fun SettingsActivity.valueName(c: Choice) = when {
    c.action -> actionName(looks[c], home.repo::appLabel)
    // Under the Appliance look, "system" on a housing-coloured part draws in the housing colour.
    m3.housing && looks[c] == "system" && c.key in listOf("dockColor", "searchColor", "folderColor") -> getString(R.string.housing_follows_theme)
    else -> valueOf(c).let { v -> c.values.indexOf(v).let { i -> if (i >= 0) choiceName(c, i) else v.ifEmpty { getString(R.string.none) } } }
}

/** Saves a setting, asking for a permission it needs first; home is built again when it shows if [Choice.reload] needs it. */
private fun SettingsActivity.applyChoice(c: Choice, value: String) {
    if (value == valueOf(c)) return
    // Pages and the default page are part of home's layout, stored with it; home is built again when it shows.
    when (c) {
        PAGES_CHOICE -> home.editLayout(redraw = false) { it.withPages(value.toInt()) }
        DEFAULT_PAGE_CHOICE -> home.editLayout(redraw = false) { it.withDefault(value.toInt()) }
        else -> looks[c] = value
    }
    if (c.reload == Reload.REBUILD) home.stale = true
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
                    runCatching { store.import(f.readText(), home.repo::mainActivity) }
                        .onSuccess { if (it.replacedLayout) { home.widgetFlow.dropForeignWidgets(); toastMissing() }; home.repinShortcuts(); restartHome() }
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
                store.reset() // first: its automatic backup notes each widget's provider, which goes with the ID
                home.items.flatMap { it.widgetIds }.forEach(home.host::deleteAppWidgetId)
                restartHome()
            }.setNegativeButton(R.string.cancel, null).show()
    }))
}

/** Says how many restored icons home is about to drop because their apps aren't installed; the backup file still has them. */
fun VulcanActivity.toastMissing() {
    val n = missingApps(store.load(looks.sub).items, home.repo::uninstalled)
    if (n > 0) toast(resources.getQuantityString(R.plurals.restore_missing, n, n))
}

/** Restores a backup or applies an older style file, then [restart] rebuilds home. */
fun VulcanActivity.restoreBackup(uri: android.net.Uri, restart: () -> Unit) {
    runCatching { store.import(contentResolver.openInputStream(uri)!!.use { it.readAtMost(MAX_BACKUP_BYTES, getString(R.string.backup_too_large)) }.decodeToString(), home.repo::mainActivity) }
        .onSuccess { result ->
            if (result.rejected.isNotEmpty()) toast(R.string.skipped_settings, result.rejected.joinToString())
            // Widget IDs belong to the install that made them; drop the ones this install doesn't own.
            if (result.replacedLayout) { home.widgetFlow.dropForeignWidgets(); toastMissing() }
            // Android opens a shortcut only for the launcher that pinned it: after a fresh install, none are.
            home.repinShortcuts()
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
    // The home this replaces stays restorable from Settings; like Store.import, nothing is replaced if that can't be saved.
    runCatching { store.rotateAutoBackup() }.onFailure { toast(R.string.import_failed, it.message); return }
    home.items.flatMap { it.widgetIds }.forEach(home.host::deleteAppWidgetId)
    // The page count goes with the items, below.
    settings.forEach { (k, v) -> if (k !in PAGE_KEYS) looks[k] = v }
    looks["subgrid"] = if (layout.subgrid) "on" else "off"
    store.editLayout(if (layout.subgrid) 2 else 1) { importedLayout(layout.items, settings["pages"]?.toIntOrNull(), it.defaultPage) }
    if (layout.widgets > 0) store.data.edit().putBoolean(RESTORING_WIDGETS, true).commit()
    // The same pins as these, from what was just stored; it waits for onResume when Vulcan isn't the default home app yet.
    home.repinShortcuts()
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
            addView(text(getString(p.onText), 14f, m3.onSurfaceVariant).apply { setPaddingRelative((4 * dp).toInt(), 0, 0, 0) })
        } else tonalButton(getString(p.offText), open)
        listItem(symbol(p.icon, m3.onPrimaryContainer), getString(p.title), getString(p.info), trailing = state, tonalIcon = true) { open() }
    })
}
