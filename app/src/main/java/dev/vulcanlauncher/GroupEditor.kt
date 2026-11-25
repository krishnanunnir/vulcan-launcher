package dev.vulcanlauncher

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.roundToInt

// Picking and arranging apps, and the drawer tabs and folders built from them.

/**
 * What an app picker saves: the ticked apps in [shown] order, then ticked ones that aren't shown right now (a locked
 * Private Space, an app mid-update), which nobody could untick and so stay chosen.
 */
fun pickerResult(shown: List<String>, chosen: Set<String>) = shown.filter { it in chosen } + (chosen - shown.toSet())

/** [others]: each app's other groups, shown under its name so a tab's apps can be picked from those not yet sorted (#55). */
fun VulcanActivity.appSwitches(title: String, on: Collection<String>, others: Map<String, List<String>> = emptyMap(), onSave: (List<String>) -> Unit) {
    // Ticked apps first, as Supernova's app checklist: what's chosen is in view without searching.
    val all = home.repo.apps.values.sortedWith(compareBy<App> { it.key !in on }.thenBy(labelOrder()) { home.repo.label(it) })
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
            return listItem(home.repo.icon(app), if (home.repo.isWork(app)) getString(R.string.work_app, home.repo.label(app)) else home.repo.label(app),
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
    val search = searchField(getString(R.string.search_apps)).apply {
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) {
                val q = e.toString().trim()
                list = if (q.isEmpty()) all else all.filter { matchScore(home.repo.label(it), q) > 0 }
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
        .setPositiveButton(R.string.save) { _, _ -> onSave(pickerResult(all.map { it.key }, chosen)) }
        .setNegativeButton(R.string.cancel, null).show()
}

/**
 * Puts [keys] in order by dragging the rows' handles (Move up/down for accessibility); Save hands back the new order.
 * shortcut: the list doesn't scroll itself during a drag, so a long move takes a drag per screenful; add edge
 * auto-scroll to Reorder if that gets tedious.
 */
fun VulcanActivity.arrangeApps(title: String, keys: List<String>, onSave: (List<String>) -> Unit) {
    var order = keys.filter { it in home.repo.apps }
    val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (8 * dp).toInt(), 0, 0) }
    fun build() {
        rows.removeAllViews()
        val views = order.map { k ->
            val app = home.repo.apps.getValue(k)
            listItem(home.repo.icon(app), home.repo.label(app), appIcon = true, onClick = null)
                .apply { setPaddingRelative((24 * dp).toInt(), paddingTop, (12 * dp).toInt(), paddingBottom) }
        }
        lateinit var rebuild: () -> Unit
        val reorder = Reorder(this, views) { from, to -> order = moveItem(order, from, to); rebuild() }
        rebuild = ::build
        views.forEach { v -> if (views.size > 1) (v as LinearLayout).addView(reorder.handle(v)); rows.addView(v) }
    }
    build()
    val scroll = ScrollView(this).apply { addView(rows) }
    sheet().setTitle(title).setView(scroll)
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
    fun tabs(l: List<Group>) = l.filter { it.type == "tab" }.map { it.name }
    val at = store.data.workAt()
    val newAt = workAtAfter(tabs(home.drawer.groups()), tabs(groups), at)
    store.data.edit().putString("groups", groupsToJson(groups)).apply { if (newAt != at) putInt(WORK_AT, newAt) }.apply()
    home.drawer.refresh()
    (this as? SettingsActivity)?.takeIf { it.page == TABS_GROUP || it.page == DRAWER_FOLDERS }?.refresh()
}

/** "Gmail, Drive, Calendar and 5 more": [keys]' names, as many as read well on one line. */
private fun VulcanActivity.appNames(keys: List<String>, shown: Int = 3): String {
    val names = keys.mapNotNull { home.repo.apps[it] }.map { home.repo.label(it) }
    return if (names.size <= shown) names.joinToString() else getString(R.string.names_and_more, names.take(shown).joinToString(), names.size - shown)
}

/** A group's icon: its first four apps on a rounded tile, 40 dp. Copies of the icons, so the drawer's keep their bounds. */
fun VulcanActivity.groupIcon(keys: List<String>): Drawable {
    val icons = keys.mapNotNull { home.repo.apps[it] }.take(4).map { home.repo.icon(it).let { d -> d.constantState?.newDrawable(resources)?.mutate() ?: d } }
    val size = (40 * dp).toInt(); val cell = (15 * dp).toInt(); val pad = (4 * dp).toInt(); val gap = size - 2 * pad - 2 * cell
    return android.graphics.drawable.LayerDrawable((listOf(rounded(m3.surfaceHighest, 12 * dp)) + icons).toTypedArray()).apply {
        setLayerSize(0, size, size)
        icons.indices.forEach { i ->
            setLayerSize(i + 1, cell, cell)
            setLayerInsetLeft(i + 1, pad + (i % 2) * (cell + gap)); setLayerInsetTop(i + 1, pad + (i / 2) * (cell + gap))
            setLayerGravity(i + 1, Gravity.START or Gravity.TOP)
        }
    }
}

/** M3 segmented buttons: one choice of a few, the chosen one filled. */
private fun SettingsActivity.segmented(options: List<String>, chosen: Int, onPick: (Int) -> Unit) = LinearLayout(this).apply {
    background = rounded(0, 20 * dp, m3.outline)
    setPadding(dp.toInt(), dp.toInt(), dp.toInt(), dp.toInt())
    // A label that wraps grows its segment instead of being clipped, and no baseline shift pushes the others off (#85).
    isBaselineAligned = false
    gravity = Gravity.CENTER_VERTICAL
    options.forEachIndexed { i, label ->
        // Filled as the drawer's selected tab chip: secondaryContainer barely stood out on the Appliance panels.
        addView(text(label, 14f, if (i == chosen) onColor(tabFill()) else m3.onSurface, medium = true).apply {
            gravity = Gravity.CENTER
            minimumHeight = (40 * dp).toInt()
            setPaddingRelative((4 * dp).toInt(), (2 * dp).toInt(), (4 * dp).toInt(), (2 * dp).toInt())
            background = pressable(if (i == chosen) rounded(tabFill(), 20 * dp) else null, 20 * dp)
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(v: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(v, info)
                    info.className = android.widget.RadioButton::class.java.name; info.isCheckable = true; info.isChecked = i == chosen
                }
            }
            setOnClickListener { if (i != chosen) onPick(i) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }
}

/** A checkbox row inside a panel, indented under the row it belongs to. */
fun VulcanActivity.checkRow(title: String, on: Boolean, onChange: (Boolean) -> Unit): View {
    val box = android.widget.CheckBox(this).apply {
        isChecked = on; isClickable = false; isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        buttonTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(m3.on, m3.onSurfaceVariant))
    }
    return LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = (48 * dp).toInt()
        setPaddingRelative((8 * dp).toInt(), 0, (24 * dp).toInt(), 0)
        background = pressable(null)
        addView(box)
        addView(text(title, 14f, m3.onSurfaceVariant), LinearLayout.LayoutParams(0, -2, 1f))
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(v: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(v, info)
                info.className = android.widget.CheckBox::class.java.name; info.isCheckable = true; info.isChecked = box.isChecked
            }
        }
        setOnClickListener { box.isChecked = !box.isChecked; onChange(box.isChecked) }
    }
}

/** An eye button: shows or hides [name] in the drawer's tab bar. */
private fun SettingsActivity.eye(name: String, shown: Boolean, onToggle: () -> Unit) =
    iconButton(if (shown) "show" else "hide", getString(if (shown) R.string.tab_hide else R.string.tab_show, name), if (shown) m3.onSurfaceVariant else m3.outline) { onToggle() }

/** Slide a row toward the start edge to delete it; a short slide springs back. A tap still taps. */
@android.annotation.SuppressLint("ClickableViewAccessibility")
private fun VulcanActivity.swipeToDelete(row: View, onDelete: () -> Unit) {
    val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
    val toward = if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) 1 else -1
    var x0 = 0f; var y0 = 0f; var swiping = false
    // The row stays put: its content slides and a red Delete area fills the gap it leaves.
    val icon = symbol("delete", m3.onErrorContainer)
    val red = object : android.graphics.drawable.Drawable() {
        val paint = android.graphics.Paint().apply { color = m3.errorContainer }
        var shown = 0f
        override fun draw(c: android.graphics.Canvas) {
            if (shown <= 0f) return
            val w = row.width.toFloat(); val h = row.height.toFloat()
            val l = if (toward < 0) w - shown else 0f
            c.drawRect(l, 0f, l + shown, h, paint)
            val s = icon.intrinsicWidth; val pad = (24 * dp).toInt()
            if (shown < s + 2 * pad) return
            val x = if (toward < 0) (w - pad - s).toInt() else pad
            icon.setBounds(x, ((h - s) / 2).toInt(), x + s, ((h + s) / 2).toInt()); icon.draw(c)
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(f: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
    fun slide(v: View, by: Float) {
        red.shown = by; red.setBounds(0, 0, v.width, v.height); red.invalidateSelf(); v.invalidate()
        (v as ViewGroup).let { g -> for (i in 0 until g.childCount) g.getChildAt(i).translationX = by * toward }
    }
    row.overlay.add(red)
    row.setOnTouchListener { v, ev ->
        val dx = (ev.rawX - x0) * toward // how far toward the start, positive
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { x0 = ev.rawX; y0 = ev.rawY; swiping = false; false }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (!swiping && dx > slop && dx > 2 * kotlin.math.abs(ev.rawY - y0)) {
                    swiping = true; v.parent.requestDisallowInterceptTouchEvent(true); v.isPressed = false; v.cancelLongPress()
                }
                if (swiping) slide(v, maxOf(0f, dx))
                swiping
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> swiping.also {
                if (!swiping) return@also
                swiping = false
                val del = ev.actionMasked == android.view.MotionEvent.ACTION_UP && dx > v.width * 0.35f
                android.animation.ValueAnimator.ofFloat(maxOf(0f, dx), if (del) v.width.toFloat() else 0f).apply {
                    duration = 150
                    addUpdateListener { slide(v, it.animatedValue as Float) }
                    if (del) addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) = onDelete() })
                }.start()
            }
            else -> swiping
        }
    }
}

/** App drawer › Apps: Tabs and Folders, each saying what's in it. */
fun SettingsActivity.tabsLink(): View {
    val tabs = home.drawer.groups().filter { it.type == "tab" }
    val hasWork = home.repo.apps.values.any { home.repo.isWork(it) }
    val names = listOfNotNull(getString(R.string.tab_all), getString(R.string.tab_private).takeIf { home.repo.privateUser != null }) + withWorkSlot(tabs.map { it.name }, store.data.workAt())
        .filter { it != WORK_SLOT || hasWork }.map { if (it == WORK_SLOT) getString(R.string.tab_work) else it }
    val summary = if (names.size <= 3) names.joinToString() else getString(R.string.names_and_more, names.take(3).joinToString(), names.size - 3)
    return listItem(symbol("tabs", m3.onPrimaryContainer), getString(R.string.group_tabs), summary, tonalIcon = true) { showPage(TABS_GROUP) }
}

fun SettingsActivity.foldersLink(): View {
    val folders = home.drawer.groups().filter { it.type == "folder" }
    return listItem(folders.firstOrNull()?.let { groupIcon(it.apps) } ?: symbol("folder", m3.onPrimaryContainer), getString(R.string.group_drawer_folders),
        if (folders.isEmpty()) getString(R.string.folders_none) else resources.getQuantityString(R.plurals.folders_count, folders.size, folders.size),
        tonalIcon = folders.isEmpty(), appIcon = folders.isNotEmpty()) { showPage(DRAWER_FOLDERS) }
}

/**
 * The drawer's tabs: a preview of the tab bar with its Scroll or Wrap choice, then every tab in bar order. Each can be
 * shown or hidden; user tabs open their editor, drag to reorder and slide away to delete. All apps carries Keep in all.
 */
fun SettingsActivity.tabsPage(list: LinearLayout) {
    val groups = home.drawer.groups()
    val tabs = groups.filter { it.type == "tab" }
    val hasVisibleTab = tabs.any { !it.hidden }
    val allShown = !store.data.getBoolean(ALL_HIDDEN, false) || !hasVisibleTab
    val hasWork = home.repo.apps.values.any { home.repo.isWork(it) }
    val workShown = workTabShown()
    val wrap = looks.str("tabBar") == "wrap"
    fun choice(key: String) = LOOKS.first { it.key == key }

    // The bar as the drawer will show it, selected tab first.
    val workAt = store.data.workAt()
    val shownNames = listOfNotNull(getString(R.string.tab_all).takeIf { allShown }, getString(R.string.tab_private).takeIf { home.repo.privateUser != null }) +
        withWorkSlot(tabs.map { it.name }, workAt).filter { if (it == WORK_SLOT) hasWork && workShown else tabs.none { t -> t.name == it && t.hidden } }
            .map { if (it == WORK_SLOT) getString(R.string.tab_work) else it }
    val bar = DrawerTabs(this, (6 * dp).toInt(), (32 * dp).toInt(), wrap).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        // On the drawer's own surface: the card's tone can match the selected chip's fill.
        background = rounded(m3.surface, 12 * dp)
        val p = (8 * dp).toInt(); setPadding(p, p, p, p)
        shownNames.forEachIndexed { i, n ->
            val on = i == 0
            addView(text(n, 13f, if (on) onColor(tabFill()) else m3.onSurfaceVariant, medium = true).apply {
                gravity = Gravity.CENTER; setSingleLine()
                val p = (12 * dp).toInt(); setPadding(p, 0, p, 0)
                background = if (on) rounded(tabFill(), 8 * dp) else null
            })
        }
    }
    val preview = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tag = "tabBar"
        setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        // Scroll: one row, cut at the edge as the drawer's scrolls; wrap: every tab.
        addView(if (wrap) bar else android.widget.HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(bar) },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (16 * dp).toInt() })
        addView(segmented(listOf(getString(R.string.tab_bar_scroll), getString(R.string.tab_bar_wrap)), if (wrap) 1 else 0) {
            looks[choice("tabBar")] = if (it == 1) "wrap" else "scroll"; changed()
        }.apply { contentDescription = getString(R.string.set_tabBar) })
    }
    panel(list, null, listOf(preview))

    fun row(title: CharSequence, summary: String?, shown: Boolean, onEye: () -> Unit, onClick: ((View) -> Unit)?) =
        listItem(null, title, if (shown) summary else getString(R.string.tab_hidden), trailing = eye(title.toString(), shown, onEye), onClick = onClick)
            .apply { if (!shown) (getChildAt(0) as? LinearLayout)?.alpha = 0.6f }
    val allRow = row(getString(R.string.tab_all), null, allShown, {
        if (allShown && !hasVisibleTab) toast(getString(R.string.tab_all_needs_tab))
        else { store.data.edit().putBoolean(ALL_HIDDEN, allShown).apply(); home.drawer.refresh(); refresh() }
    }, null)
    val keep = checkRow(getString(R.string.set_keepInAll), looks.on("keepInAll")) { looks[choice("keepInAll")] = if (it) "on" else "off"; changed() }.apply { tag = "keepInAll" }
    // Work profile: a briefcase after its name marks it as the system's, not a tab of your own.
    val workTitle = android.text.SpannableString(getString(R.string.tab_work) + "  \u200b").apply {
        val s = (16 * dp).roundToInt()
        setSpan(android.text.style.ImageSpan(symbol("briefcase", m3.onSurfaceVariant, 16f).apply { setBounds(0, 0, s, s) }, android.text.style.DynamicDrawableSpan.ALIGN_CENTER),
            length - 1, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    val workRow = if (hasWork) row(workTitle, null, workShown, {
        store.data.edit().putBoolean(WORK_HIDDEN, workShown).apply(); home.drawer.refresh(); refresh()
    }, null) else null
    val tabRows = tabs.map { g ->
        row(g.name, resources.getQuantityString(R.plurals.group_apps, g.apps.size, g.apps.size), !g.hidden, {
            saveGroups(home.drawer.groups().map { if (it.name == g.name) it.copy(hidden = !g.hidden) else it })
        }) { editGroup(g) }.also { swipeToDelete(it) { deleteGroup(g) } }
    }
    // Work moves among your tabs like one of them: its place is how many of them come before it (WORK_AT).
    val movable = if (workRow == null) tabRows else tabRows.toMutableList().apply { add(workAt.coerceIn(0, size), workRow) }
    val reorder = Reorder(this, movable) { from, to ->
        val now = home.drawer.groups()
        if (workRow == null) saveGroups(reorderGroups(now, "tab", from, to))
        else moveWithWork(now.filter { it.type == "tab" }.map { it.name }, workAt, from, to).let { (order, at) ->
            store.data.edit().putInt(WORK_AT, at).apply()
            saveGroups(withTabOrder(now, order))
        }
    }
    if (movable.size > 1) movable.forEach { (it as LinearLayout).addView(reorder.handle(it)) }
    val newTab = listItem(symbol("add", m3.primary), getString(R.string.new_tab)) { newGroup("tab") }
    // Keep in all only means something while All apps shows.
    // Private Space is the system's: always right after All apps, as the preview shows, and not hidden from here.
    val privateRow = if (home.repo.privateUser != null) listItem(null, getString(R.string.tab_private), getString(R.string.tab_private_fixed), onClick = null) else null
    panel(list, getString(R.string.tabs_in_bar), listOfNotNull(allRow, keep.takeIf { allShown }, privateRow) + movable + newTab)
    if (tabs.isNotEmpty()) list.addView(text(getString(R.string.tabs_hint), 13f, m3.onSurfaceVariant).apply {
        setPadding((32 * dp).toInt(), (12 * dp).toInt(), (32 * dp).toInt(), 0)
    })
}

/**
 * The drawer's folders: a preview of where they sit in the grid with its First or Sorted with apps choice, then each
 * folder (tap to edit, drag to reorder, slide away to delete), and New folder or Suggest folders.
 */
fun SettingsActivity.foldersPage(list: LinearLayout) {
    val folders = home.drawer.groups().filter { it.type == "folder" }
    val first = looks.on("foldersFirst")
    // A row of the drawer: a few apps and folders, in the chosen order.
    val apps = home.repo.apps.values.filterNot { home.repo.isWork(it) }.sortedWith(compareBy(labelOrder()) { home.repo.label(it) }).take(if (folders.isEmpty()) 4 else 6)
    data class Cell(val name: String, val icon: Drawable)
    val folderCells = folders.take(2).map { Cell(it.name, groupIcon(it.apps)) }
    val appCells = apps.map { Cell(home.repo.label(it), home.repo.icon(it)) }
    val cells = if (first) folderCells + appCells else (folderCells + appCells).sortedWith(compareBy(labelOrder()) { it.name })
    val grid = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        cells.chunked(4).forEach { rowCells ->
            addView(LinearLayout(context).apply {
                (0 until 4).forEach { i ->
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                        rowCells.getOrNull(i)?.let { c ->
                            addView(ImageView(context).apply { setImageDrawable(c.icon) }, LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()))
                            addView(text(c.name, 11f, m3.onSurfaceVariant).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER })
                        }
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { bottomMargin = (8 * dp).toInt() })
                }
            })
        }
    }
    panel(list, null, listOf(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tag = "foldersFirst"
        setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        addView(grid)
        addView(text(getString(R.string.folders_in_grid), 14f, m3.onSurfaceVariant).apply { setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt()) })
        addView(segmented(listOf(getString(R.string.folders_first), getString(R.string.folders_sorted)), if (first) 0 else 1) {
            looks[LOOKS.first { c -> c.key == "foldersFirst" }] = if (it == 0) "on" else "off"; changed()
        }.apply { contentDescription = getString(R.string.folders_in_grid) })
    }))

    val rows = folders.map { g ->
        listItem(groupIcon(g.apps), g.name, resources.getQuantityString(R.plurals.group_apps, g.apps.size, g.apps.size), appIcon = true) { editGroup(g) }
            .also { swipeToDelete(it) { deleteGroup(g) } }
    }
    val reorder = Reorder(this, rows) { from, to -> saveGroups(reorderGroups(home.drawer.groups(), "folder", from, to)) }
    if (rows.size > 1) rows.forEach { (it as LinearLayout).addView(reorder.handle(it)) }
    // Two ways to add, side by side.
    fun add(icon: String, label: Int, onClick: () -> Unit) = text(getString(label), 14f, m3.primary, medium = true).apply {
        gravity = Gravity.CENTER_VERTICAL
        minHeight = (56 * dp).toInt()
        setPaddingRelative((16 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        compoundDrawablePadding = (12 * dp).toInt()
        setCompoundDrawablesRelative(symbol(icon, m3.primary, 20f).apply { setBounds(0, 0, intrinsicWidth, intrinsicHeight) }, null, null, null)
        background = pressable(null)
        setOnClickListener { onClick() }
    }
    val adds = LinearLayout(this).apply {
        addView(add("add", R.string.new_folder) { newGroup("folder") }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(View(context).apply { setBackgroundColor(m3.outlineVariant) }, LinearLayout.LayoutParams(maxOf(1, (dp / 2).roundToInt()), (32 * dp).toInt()).apply { gravity = Gravity.CENTER_VERTICAL })
        addView(add("sparkle", R.string.suggest_folders) { suggestFolders() }, LinearLayout.LayoutParams(0, -2, 1f))
    }
    panel(list, getString(R.string.your_folders), rows + adds)
    list.addView(text(getString(if (folders.isEmpty()) R.string.folders_empty else R.string.folders_hint), 13f, m3.onSurfaceVariant).apply {
        setPadding((32 * dp).toInt(), (12 * dp).toInt(), (32 * dp).toInt(), 0)
    })
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
    if (nameTaken(groups, name)) { toast(getString(R.string.group_exists)); return@askName }
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

/**
 * A tab's or folder's sheet: its name (saved when the sheet closes), its apps, a tab's order and colour, turning it into
 * the other kind, and Delete beside Done.
 */
fun VulcanActivity.editGroup(g: Group) {
    val name = field(g.name, getString(R.string.name))
    var color = g.color
    lateinit var d: android.app.AlertDialog
    /** The group's name now: the typed one once [keep] has saved it. */
    var now = g.name
    fun current() = home.drawer.groups().firstOrNull { it.name == now }
    /** Saves the typed name and the colour. Run before anything else reads the groups, and when the sheet closes. */
    fun keep() {
        val groups = home.drawer.groups()
        val cur = groups.firstOrNull { it.name == now } ?: return
        val typed = name.text.toString().trim()
        val newName = when {
            typed.isEmpty() || typed == cur.name -> cur.name
            nameTaken(groups, typed) -> { toast(getString(R.string.group_exists)); cur.name }
            else -> typed
        }
        if (newName != cur.name || color != cur.color) saveGroups(groups.map { if (it.name == now) it.copy(name = newName, color = color) else it })
        now = newName
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((16 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
    }
    box.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), (12 * dp).toInt())
        addView(ImageView(context).apply { setImageDrawable(groupIcon(g.apps)) }, LinearLayout.LayoutParams((52 * dp).toInt(), (52 * dp).toInt()).apply { marginEnd = (16 * dp).toInt() })
        addView(name, LinearLayout.LayoutParams(0, -2, 1f))
    })
    val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(m3.surfaceHighest, 18 * dp); clipToOutline = true }
    rows.addView(listItem(symbol("apps"), getString(R.string.choose_apps), appNames(g.apps).ifEmpty { null }) {
        d.dismiss(); keep()
        chooseApps(now, g.apps, home.drawer.groups(), now) { apps, rest -> saveGroups(rest.map { if (it.name == now) it.copy(apps = apps) else it }) }
    })
    if (g.type == "tab") rows.addView(listItem(symbol("drag"), getString(R.string.arrange_apps)) { d.dismiss(); keep(); arrangeTab(now, g.apps) })
    // A tab's own colour, as Nova's: it marks the tab while it's selected (Drawer.buildTabs). No setting of its own (#49).
    // It opens the same colour sheet as every other colour, with the tab bar as its sample; saved when this sheet closes.
    if (g.type == "tab") {
        val dot = View(this).apply { layoutParams = LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt()) }
        fun show() { dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color?.let(Color::parseColor) ?: tabFill()); setStroke(dp.toInt(), m3.outlineVariant) } }
        show()
        rows.addView(listItem(symbol("palette"), getString(R.string.tab_color), trailing = dot) {
            pickTabColor(g.name, color) { color = it; show() }
        })
    }
    val other = if (g.type == "tab") "folder" else "tab"
    rows.addView(listItem(symbol(if (other == "tab") "tabs" else "folder"), getString(if (other == "tab") R.string.convert_to_tab else R.string.convert_to_folder)) {
        current()?.let { cur -> saveGroups(home.drawer.groups().map { if (it === cur || it.name == cur.name) it.copy(type = other) else it }) }
        d.dismiss()
    })
    box.addView(rows)
    // Delete is quiet text on the left, away from Done.
    box.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, (16 * dp).toInt(), 0, 0)
        addView(text(getString(R.string.delete), 14f, m3.error, medium = true).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = (44 * dp).toInt()
            setPadding((12 * dp).toInt(), 0, (16 * dp).toInt(), 0)
            compoundDrawablePadding = (8 * dp).toInt()
            setCompoundDrawablesRelative(symbol("delete", m3.error, 20f).apply { setBounds(0, 0, intrinsicWidth, intrinsicHeight) }, null, null, null)
            background = pressable(null, 22 * dp, m3.error)
            // Saved first: Delete can still be cancelled, and it deletes by the name as saved.
            setOnClickListener { keep(); d.dismiss(); current()?.let { deleteGroup(it) } }
        })
        addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        addView(text(getString(R.string.done), 14f, m3.onPrimary, medium = true).apply {
            gravity = Gravity.CENTER
            minHeight = (44 * dp).toInt()
            setPadding((24 * dp).toInt(), 0, (24 * dp).toInt(), 0)
            background = pressable(rounded(m3.primary, 22 * dp), 22 * dp, m3.onPrimary)
            setOnClickListener { d.dismiss() }
        })
    })
    d = sheet().setView(ScrollView(this).apply { addView(box) }).create()
    // Closing the sheet any way keeps the new name and colour.
    d.setOnDismissListener { keep() }
    d.show()
}

/** Asks, showing what's in [g], then deletes it and puts its apps back in the drawer. */
fun VulcanActivity.deleteGroup(g: Group) {
    fun go() = saveGroups(home.drawer.groups().filterNot { it.name == g.name })
    val icons = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, (16 * dp).toInt(), 0, 0)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        g.apps.mapNotNull { home.repo.apps[it] }.take(6).forEach { app ->
            addView(ImageView(context).apply { setImageDrawable(home.repo.icon(app)) }, LinearLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt()).apply { marginEnd = (6 * dp).toInt() })
        }
        if (g.apps.size > 6) addView(text("+${g.apps.size - 6}", 12f, m3.onSurfaceVariant).apply {
            gravity = Gravity.CENTER; setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0); background = rounded(m3.surfaceHigh, 14 * dp)
        }, LinearLayout.LayoutParams(-2, (28 * dp).toInt()))
    }
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0)
        addView(text(resources.getQuantityString(if (g.type == "tab") R.plurals.delete_tab_message else R.plurals.delete_folder_message, g.apps.size, g.apps.size), 14f, m3.onSurfaceVariant))
        addView(icons)
    }
    // Cancelled from a slide, the row springs back with the page.
    val back = { (this as? SettingsActivity)?.refresh(); Unit }
    val d = dialog().setIcon(symbol("delete", m3.error)).setTitle(getString(R.string.delete_group_title, g.name)).setView(body)
        .setPositiveButton(R.string.delete) { _, _ -> go() }
        .setNegativeButton(R.string.cancel) { _, _ -> back() }
        .setOnCancelListener { back() }
        .show()
    d.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(m3.error)
}

/** The selected tab's fill when the tab has no colour of its own: solid ink, so it stands out from the plain ones (0.32). */
fun VulcanActivity.tabFill() = m3.onSurface

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
        .setNeutralButton(R.string.new_folder) { _, _ -> askName(getString(R.string.new_folder), "") {
            // Groups are found by name: a second one of the same name would be edited and deleted along with the first.
            if (nameTaken(groups, it)) toast(R.string.group_exists) else save(listOf(Group(it, "folder", listOf(key))))
        } }
        .show()
}

/** Suggested folders, one per app category: each ticked to start, with the apps it would take. Create makes the ticked ones. */
private fun SettingsActivity.suggestFolders() {
    val byName = smartCategories()
    val groups = home.drawer.groups()
    val foldered = groups.filter { it.type == "folder" }.flatMap { it.apps }.toSet()
    // Only what Create would change: a new folder of two or more, or apps for a folder of that name.
    val ideas = byName.filter { (n, keys) -> smartGroups(groups, mapOf(n to keys)).let { it.second + it.third > 0 } }
        .entries.sortedByDescending { (_, keys) -> keys.count { it !in foldered } }
    if (ideas.isEmpty()) { toast(getString(R.string.no_new_categories)); return }
    val on = ideas.map { it.key }.toMutableSet()
    lateinit var d: android.app.AlertDialog
    fun cta() = d.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
        text = if (on.isEmpty()) getString(R.string.create) else getString(R.string.create_n, on.size); isEnabled = on.isNotEmpty()
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        addView(text(getString(R.string.suggest_info), 14f, m3.onSurfaceVariant).apply { setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), (8 * dp).toInt()) })
        for ((n, keys) in ideas) {
            val fresh = keys.filter { it !in foldered }
            val check = android.widget.CheckBox(context).apply {
                isChecked = true; isClickable = false; isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                buttonTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(m3.on, m3.onSurfaceVariant))
            }
            addView(listItem(groupIcon(fresh), n, appNames(fresh, 4), trailing = check, appIcon = true, summaryLines = 1) {
                check.isChecked = !check.isChecked
                if (check.isChecked) on += n else on -= n
                cta()
            }.apply {
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(v: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(v, info)
                        info.className = android.widget.CheckBox::class.java.name; info.isCheckable = true; info.isChecked = check.isChecked
                    }
                }
            })
        }
    }
    d = sheet().setTitle(R.string.suggest_title).setView(ScrollView(this).apply { addView(box) })
        .setPositiveButton(R.string.create) { _, _ ->
            val (out, count, added) = smartGroups(home.drawer.groups(), byName.filterKeys { it in on })
            if (count > 0) toast(resources.getQuantityString(R.plurals.created_folders, count, count))
            else if (added > 0) toast(resources.getQuantityString(R.plurals.added_to_folders, added, added))
            saveGroups(out)
        }
        .setNegativeButton(R.string.cancel, null).show()
    cta()
}

/** Each app category's folder name and its apps' keys (see [smartCategory]), A to Z. */
private fun SettingsActivity.smartCategories(): Map<String, List<String>> {
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
    // Hidden and Private Space apps stay out of suggested folders, as they do out of All apps.
    val hidden = store.strings("hidden")
    @Suppress("DEPRECATION") val byName = home.repo.apps.values.filter { it.key !in hidden && !home.repo.isPrivate(it) }.mapNotNull { a ->
        val ai = a.info.applicationInfo
        val cat = smartCategory(ai.category, ai.flags and ApplicationInfo.FLAG_IS_GAME != 0, roles[a.pkg], a.pkg, a.title,
            ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) ?: return@mapNotNull null
        val name = names[cat]?.let(::getString) ?: ApplicationInfo.getCategoryTitle(this, cat)?.toString() ?: return@mapNotNull null
        name to a
    }.groupBy({ it.first }, { it.second }).mapValues { (_, l) -> l.sortedBy { it.title.lowercase() }.map { it.key } }
    return byName.toSortedMap()
}
