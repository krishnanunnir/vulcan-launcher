package dev.vulcanlauncher

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Process
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.hypot
import kotlin.math.roundToInt

/** The long-press menu, the home overview and menu, and the actions a gesture or a menu row can run. */
class HomeMenus(private val a: MainActivity) {
    // ---- Long-press menu ----

    fun showPopup(anchor: View, payload: Any?) {
        val rows = ArrayList<View>()
        val tiles = ArrayList<Triple<String, String, () -> Unit>>()
        fun row(icon: Drawable, text: String, summary: String? = null, long: ((View) -> Unit)? = null, click: () -> Unit) {
            // As Nova: a draggable row (a shortcut) shows a grip at its end.
            val grip = if (long != null && summary == null) ImageView(a).apply { setImageDrawable(a.symbol("drag", a.m3.onSurfaceVariant, 20f)) } else null
            rows += a.listItem(icon, text, summary, trailing = grip, dense = true, summaryLines = 2) { closeMenu(); click() }.apply {
                // A drag that starts here closes the menu once it moves (see trackDrag).
                if (long != null) setOnLongClickListener { v -> long(v); if (a.drag.dragSource !== v) closeMenu(); true }
            }
        }
        fun tile(sym: String, title: String, click: () -> Unit) { tiles += Triple(sym, title, click) }
        val item = payload as? Item
        val app = a.drag.appOf(payload)
        // A pinned shortcut has no app item of its own: the app owning its package gives it the info tile and notification
        // row (not that app's shortcuts, widgets or uninstall). A shortcut inside a folder is the folder's child at its index.
        val shortcut = item?.takeIf { it.isShortcut }
            ?: (payload as? FolderChild)?.child?.takeIf { it.isShortcut }
        val owner = app ?: shortcut?.let { s -> a.repo.apps.values.firstOrNull { it.pkg == s.pkg && it.serial == s.user } }
        if (app != null && a.looks.on("popupShortcuts")) a.repo.shortcutsFor(app).forEach { s ->
            row(a.repo.shortcutIcon(s), s.shortLabel?.toString() ?: "", long = { v -> if (!a.locked) a.drag.startDrag(v, s) }) {
                a.startShortcut(s.`package`, s.id, s.userHandle, anchor)
            }
        }
        if (owner != null && a.looks.on("popupNotifications")) {
            // The newest only, as Launcher3 shows one notification at a time.
            BadgeService.byPackage[a.repo.badgeKey(owner.pkg, owner.serial)].orEmpty().sortedByDescending { it.postTime }.take(1).forEach { n ->
                val title = n.notification.extras.getCharSequence("android.title") ?: ""
                val body = n.notification.extras.getCharSequence("android.text") ?: ""
                row(a.symbol("notifications"), title.toString(), body.toString(), long = { BadgeService.instance?.cancelNotification(n.key) }) {
                    runCatching { n.notification.contentIntent?.send() }
                }
            }
        }
        when {
            app != null && (payload is App || payload is GroupChild) -> {
                if (!a.locked) tile("add", a.getString(R.string.menu_add)) { a.addToHome(app) }
                tile("edit", a.getString(R.string.edit)) { a.editApp(app) }
                val hidden = app.key in a.store.strings("hidden")
                tile(if (hidden) "show" else "hide", a.getString(if (hidden) R.string.unhide else R.string.hide)) { toggleHidden(app.key) }
                tile("tabs", a.getString(R.string.menu_group)) { a.addToGroup(app.key) }
                if (payload is GroupChild) tile("close", a.getString(R.string.remove)) { a.removeFromGroup(payload) }
            }
            item != null && !a.locked -> {
                if (!item.isBlock) tile("edit", a.getString(R.string.edit)) { a.editItem(item) }
                if (item.folder) a.folderActions(item).forEach { (sym, title, click) -> tile(sym, title, click) }
                if (item.isBlock) tile("resize", a.getString(R.string.resize)) { a.widgetFlow.resizeWidget(item) }
                if (item.isWidget && a.awm.getAppWidgetInfo(a.widgetFlow.shownWidget(item))?.configure != null) tile("settings", a.getString(R.string.settings)) {
                    runCatching { a.host.startAppWidgetConfigureActivityForResult(a, a.widgetFlow.shownWidget(item), 0, MainActivity.REQ_RECONFIG, null) }
                }
                if (item.isWidget && item.children.isNotEmpty()) tile("widgets", a.getString(R.string.unstack)) { a.widgetFlow.unstack(item) }
                if (item.isClock) {
                    val big = a.views[item]?.tag == true
                    tile("clock", a.getString(if (big) R.string.glanceStyle_date else R.string.glanceStyle_clock)) {
                        a.looks["glanceStyle"] = if (big) "date" else "clock"; a.rebuild()
                    }
                }
                tile("close", a.getString(R.string.remove)) { a.removeItem(item); a.render() }
            }
            payload is Group -> {
                if (!a.locked) tile("add", a.getString(R.string.menu_add)) { a.drag.groupItem(payload)?.let(a::addItem) }
                tile("edit", a.getString(R.string.edit)) { a.editGroup(payload) }
            }
            payload is FolderChild && !a.locked -> {
                val child = payload.child
                if (!child.isBlock) tile("edit", a.getString(R.string.edit)) {
                    a.editItem(child, swipes = false) { new ->
                        // Into the folder as it is now, at the place the child holds now.
                        a.folderOf(payload)?.let { f -> f.children.indexOf(child).takeIf { it >= 0 }?.let { j -> a.setFolderChildren(f, f.children.toMutableList().apply { set(j, new) }) } }
                    }
                }
                tile("close", a.getString(R.string.remove)) { if (a.removeFromFolder(payload)) { a.save(); a.render() } }
            }
        }
        if (owner != null) {
            tile("info", a.getString(R.string.app_info)) { a.launcherApps.startAppDetailsActivity(owner.info.componentName, owner.info.user, null, null) }
            // The optional ones last, so every app's other tiles keep their places.
            if (app != null && !a.locked && a.widgetFlow.hasWidgets(app)) tile("widgets", a.getString(R.string.widgets)) { a.widgetFlow.pickWidget(app.pkg, user = app.info.user) }
            if (app != null && !isSystemApp(app)) tile("delete", a.getString(R.string.uninstall)) { a.uninstall(app) }
        }
        if (rows.isEmpty() && tiles.isEmpty()) return
        // Lined up with the icon itself, not its cell; clear of its label.
        val at = screenRect(anchor).apply { screenRect((anchor as? IconView)?.image ?: anchor).let { left = it.left; right = it.right } }
        // An app's menu is always tiles, so it has the same shape for every app and the hand learns where each one is.
        // Others (folders, widgets) are rarer and vary: four or fewer read as rows (addActions).
        showMenu(at, narrow = app == null && tiles.size <= 4) {
            rows.forEach { addView(it) }
            if (rows.isNotEmpty() && tiles.isNotEmpty()) addView(hairline())
            if (app != null) addTiles(tiles, 4) else addActions(tiles, 4, lead = emptySet())
        }
    }

    /** The open long-press or home menu, an overlay inside this window. */
    var menu: View? = null

    fun closeMenu() {
        val m = menu ?: return
        menu = null
        if (a.drag.homeState == HomeState.OVERVIEW) a.drag.setHomeState(HomeState.NORMAL)
        // Let touches through at once; it only fades out.
        m.setOnClickListener(null); m.setOnTouchListener(null); m.isClickable = false
        m.animate().alpha(0f).setDuration(120).withEndAction { a.root.removeView(m) }.start()
    }

    /** [v]'s bounds in this window. */
    fun screenRect(v: View): Rect {
        val a = IntArray(2).also { v.getLocationInWindow(it) }
        val r = IntArray(2).also { this@HomeMenus.a.root.getLocationInWindow(it) }
        return Rect(a[0] - r[0], a[1] - r[1], a[0] - r[0] + v.width, a[1] - r[1] + v.height)
    }

    /**
     * A glass popover menu next to [anchor] (window coordinates): above it if there's room, else below. It reveals
     * out of the anchor. Drawn inside this window rather than as a PopupWindow: a drag started from another window never
     * delivers its end event here. A [narrow] menu (rows only) is Launcher3's 216dp; tiles need 264dp for their legends.
     * As Nova's: it lines up with the anchor's left edge (its right edge when that doesn't fit), and an arrow points at it.
     */
    fun showMenu(anchor: Rect, narrow: Boolean = false, build: LinearLayout.() -> Unit) {
        // Wider with a larger system font size, so the tracked tile legends don't clip.
        val margin = (8 * a.dp).toInt()
        val w = minOf(((if (narrow) 216 else 264) * a.dp * maxOf(1f, a.resources.configuration.fontScale)).toInt(), a.root.width - 2 * margin)
        menuWidth = w
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            a.frost(this, 24 * a.dp)
            clipToOutline = true
            val p = (6 * a.dp).toInt()
            setPadding(0, p, 0, p)
            build()
        }
        closeMenu()
        box.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val x = menuX(anchor.left, anchor.right, w, a.root.width, margin)
        // As Launcher3: above the anchor if there's room, else below.
        val above = anchor.top - box.measuredHeight - margin
        val y = if (above >= a.insets.top) above else minOf(anchor.bottom + margin, maxOf(a.insets.top, a.root.height - a.insets.bottom - box.measuredHeight))
        val up = y > above
        // As Nova: an arrow joins the menu to its anchor, in the menu's own glass (or housing) with its hairline edge.
        // Its base overlaps the panel by the edge's width, covering the edge there so the two read as one shape.
        // ponytail: on Samsung's blur the arrow is the glass tint without the blur behind it; close, not identical.
        val glass = a.looks.on("blur")
        val fill = if (glass) a.frostTint() else a.m3.raised
        // The seam covers the glass's hairline, or the housing's 1 dp rim (housingFace).
        val aw = (20 * a.dp).toInt(); val ah = (10 * a.dp).toInt(); val seam = if (glass) maxOf(1, (a.dp / 2).roundToInt()) else a.dp.roundToInt() + 1
        val inset = (24 * a.dp).toInt() // clear of the panel's rounded corners
        val ax = (anchor.centerX() - aw / 2).coerceIn(x + inset, maxOf(x + inset, x + w - aw - inset))
        val arrow = object : View(a) {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = fill }
            val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = a.frostEdge(); style = android.graphics.Paint.Style.STROKE; strokeWidth = a.dp / 2
            }
            override fun onDraw(c: android.graphics.Canvas) {
                val wd = width.toFloat()
                val tip = if (up) 0f else height.toFloat()
                val base = if (up) ah.toFloat() else seam.toFloat()
                c.drawPath(android.graphics.Path().apply { moveTo(0f, base); lineTo(wd / 2, tip); lineTo(wd, base); close() }, paint)
                if (up) c.drawRect(0f, base, wd, height.toFloat(), paint) else c.drawRect(0f, 0f, wd, base, paint)
                if (glass) { c.drawLine(0f, base, wd / 2, tip, edge); c.drawLine(wd / 2, tip, wd, base, edge) }
            }
        }.apply { elevation = box.elevation; outlineProvider = null }
        val shade = FrameLayout(a).apply {
            setOnClickListener { closeMenu() }
            // No drag listener: a drag goes to the view under the finger, so a folder window reordering its icons keeps
            // itself open (trackDrag's keepOverlay) instead of this full-screen shade closing it on the first move (#1).
            // LEFT, not the default START: x is measured from the left edge in right-to-left languages too.
            addView(box, FrameLayout.LayoutParams(w, -2, Gravity.TOP or Gravity.LEFT).apply { leftMargin = x; topMargin = y })
            addView(arrow, FrameLayout.LayoutParams(aw, ah + seam, Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = ax; topMargin = if (up) y - ah else y + box.measuredHeight - seam
            })
        }
        a.root.addView(shade)
        menu = shade
        // As Nova: the panel reveals out of the anchor, then its contents fade in.
        // ponytail: a circular reveal; Launcher3 reveals a growing rounded rectangle.
        box.visibility = View.INVISIBLE
        arrow.alpha = 0f
        arrow.animate().alpha(1f).setDuration(100).start()
        for (i in 0 until box.childCount) box.getChildAt(i).apply { alpha = 0f; animate().alpha(1f).setStartDelay(90).setDuration(150).start() }
        box.post {
            if (!box.isAttachedToWindow) return@post
            box.visibility = View.VISIBLE
            android.view.ViewAnimationUtils.createCircularReveal(box, ax - x + aw / 2, if (up) 0 else box.height, 0f,
                hypot(box.width.toFloat(), box.height.toFloat())).apply { duration = 220; interpolator = EASE }.start()
        }
    }

    /** Width of the menu being built by [showMenu]. */
    private var menuWidth = 0

    /**
     * A menu's actions, combining Launcher3 and Nova: the [lead] ones (by symbol, Launcher3's own picks) as labelled rows,
     * the power extras as icon tiles under them. Four or fewer are all rows: a few tiles in a wide panel looked unfinished.
     */
    fun LinearLayout.addActions(actions: List<Triple<String, String, () -> Unit>>, maxCols: Int, lead: Set<String>) {
        val (rows, tiles) = if (actions.size <= 4) actions to emptyList() else actions.partition { it.first in lead }
        rows.forEach { (sym, title, click) ->
            addView(a.listItem(a.symbol(sym, actionInk(sym, a.m3.onSurfaceVariant)), title, dense = true) { closeMenu(); click() })
        }
        if (rows.isNotEmpty() && tiles.isNotEmpty()) addView(hairline())
        addTiles(tiles, maxCols)
    }

    /** Removing and uninstalling read as destructive at a glance. */
    private fun actionInk(sym: String, normal: Int) = if (sym == "close" || sym == "delete") a.m3.error else normal

    /** Icon-over-label action tiles for a menu: up to [maxCols] to a row, fewer when a large font makes the legends wide. */
    private fun LinearLayout.addTiles(tiles: List<Triple<String, String, () -> Unit>>, maxCols: Int) {
        if (tiles.isEmpty()) return
        val pad = (6 * a.dp).toInt()
        val views = tiles.map { (sym, title, click) ->
            (if (a.m3.housing) a.legend(title, 10f) else a.text(title, 11f, a.m3.onSurfaceVariant)).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                compoundDrawablePadding = (6 * a.dp).toInt()
                setCompoundDrawablesWithIntrinsicBounds(null, a.symbol(sym, actionInk(sym, a.m3.onSurface), 22f), null, null)
                setPadding(0, (12 * a.dp).toInt(), 0, (10 * a.dp).toInt())
                background = a.pressable(null, 16 * a.dp)
                setOnClickListener { closeMenu(); click() }
            }
        }
        val widest = views.maxOfOrNull { it.measure(0, 0); it.measuredWidth } ?: 1
        // Balanced lines (5 tiles as 3 + 2, not 4 + 1), so no tile sits alone under a full row.
        val fit = ((menuWidth - 2 * pad) / maxOf(1, widest)).coerceIn(1, maxCols)
        val lines = (tiles.size + fit - 1) / fit
        val cols = (tiles.size + lines - 1) / lines
        views.chunked(cols).forEach { chunk ->
            addView(LinearLayout(context).apply {
                setPadding(pad, 0, pad, 0)
                chunk.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
                // Keep tiles the same width in a short last row.
                repeat(cols - chunk.size) { addView(View(context), LinearLayout.LayoutParams(0, 0, 1f)) }
            })
        }
    }

    /** A hairline between menu sections. */
    private fun hairline() = View(a).apply {
        setBackgroundColor(a.m3.onSurface and 0xFFFFFF or 0x1F000000)
        layoutParams = LinearLayout.LayoutParams(-1, maxOf(1, (a.dp / 2).toInt())).apply { setMargins(0, (6 * a.dp).toInt(), 0, (6 * a.dp).toInt()) }
    }

    private fun isSystemApp(a: App) = a.info.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0

    private fun toggleHidden(key: String) {
        val hidden = a.store.strings("hidden")
        if (!hidden.remove(key)) hidden += key
        a.store.putStrings("hidden", hidden)
        a.drawer.refresh()
    }

    // ---- Home menu, widgets and shortcuts ----

    /**
     * Nova's hold on empty space: the page shrinks into its outline (as while dragging), the dock steps aside, and the
     * main home actions sit at the bottom as large buttons; More has the rest ([homeMore]). Tap anywhere or Back to leave.
     */
    fun homeOverview(animate: Boolean = true) {
        closeMenu()
        val row = LinearLayout(a)
        homeButtons(row)
        val shade = FrameLayout(a).apply {
            // Off the buttons, touches go to the shrunk pages: swipe between them, tap one to open it, hold one to move it.
            @SuppressLint("ClickableViewAccessibility")
            setOnTouchListener { _, ev -> a.drag.toWorkspace(ev); true }
            addView(row, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                val p = (16 * a.dp).toInt()
                setMargins(p, 0, p, a.insets.bottom + (24 * a.dp).toInt())
            })
        }
        a.root.addView(shade)
        menu = shade
        row.measure(View.MeasureSpec.makeMeasureSpec(a.root.width - (32 * a.dp).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        a.drag.springBottom = row.measuredHeight + a.insets.bottom + (24 * a.dp).toInt()
        a.root.tick()
        a.drag.setHomeState(HomeState.OVERVIEW, animate)
        a.drag.springBottom = 0
    }

    /** [homeOverview]'s main buttons, into [row]. */
    private fun homeButtons(row: LinearLayout): Unit = fillHomeRow(row, listOf(
        Triple("image", a.getString(R.string.wallpaper)) { closeMenu(); perform("wallpaper") },
        Triple("widgets", a.getString(R.string.widgets)) { closeMenu(); a.widgetFlow.pickWidget() },
        Triple("carousel", a.getString(R.string.pages)) { closeMenu(); a.showOverview() },
        Triple("settings", a.getString(R.string.settings)) { closeMenu(); a.showSettings() },
        Triple("more", a.getString(R.string.home_more)) { homeMore(row) },
    ).filter { !a.locked || it.first != "widgets" })

    /**
     * More: the same row turns into Back, Shortcuts, Clock and Lock, in the same style and place, so nothing new lands
     * on the page. Back turns it into the main buttons again; an action, a tap outside or the Back key closes it all.
     */
    private fun homeMore(row: LinearLayout): Unit = fillHomeRow(row, listOf(
        Triple("back", a.getString(R.string.back)) { homeButtons(row) },
        Triple("bolt", a.getString(R.string.shortcuts)) { closeMenu(); addShortcutMenu() },
        // As a clock widget: one row across the page, sized and moved like one.
        Triple("clock", a.getString(R.string.clock)) { closeMenu(); a.addItem(Item(0, 0, 0, a.looks.cols * a.sub, a.sub, action = "clock")); Unit },
        Triple(if (a.locked) "unlock" else "lock", a.getString(if (a.locked) R.string.unlock else R.string.lock)) {
            closeMenu(); a.looks["lockLayout"] = if (a.locked) "off" else "on"; a.toast(if (a.locked) R.string.layout_locked else R.string.layout_unlocked)
        },
    ).filter { !a.locked || it.first !in listOf("bolt", "clock") })

    /** Fills [row] with [entries] as large buttons: fading in when it's new, cross-fading when it had some. */
    private fun fillHomeRow(row: LinearLayout, entries: List<Triple<String, String, () -> Unit>>) {
        val ink = a.looks.color("labelColor")
        val fill = {
            row.removeAllViews()
            entries.forEach { (sym, title, click) -> row.addView(homeButton(sym, title, ink, click), LinearLayout.LayoutParams(0, -2, 1f)) }
            row.animate().alpha(1f).setDuration(150).start()
        }
        if (row.childCount == 0) { row.alpha = 0f; fill() }
        else row.animate().alpha(0f).setDuration(100).withEndAction(fill).start()
    }

    /** One of [homeOverview]'s buttons: its symbol over its title, in the labels' colour. */
    private fun homeButton(sym: String, title: String, ink: Int, click: () -> Unit) = a.text(title, 13f, ink).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        compoundDrawablePadding = (8 * a.dp).toInt()
        setCompoundDrawablesWithIntrinsicBounds(null, a.symbol(sym, ink, 32f), null, null)
        setPadding(0, (12 * a.dp).toInt(), 0, (12 * a.dp).toInt())
        background = a.pressable(null, 16 * a.dp, ink)
        setOnClickListener { click() }
    }

    fun homeMenu() {
        val entries = listOf(
            Triple("image", a.getString(R.string.wallpaper)) { perform("wallpaper") },
            Triple("widgets", a.getString(R.string.widgets)) { a.widgetFlow.pickWidget() },
            Triple("bolt", a.getString(R.string.shortcuts)) { addShortcutMenu() },
            Triple("carousel", a.getString(R.string.pages)) { a.showOverview() },
            // As a clock widget: one row across the page, sized and moved like one.
            Triple("clock", a.getString(R.string.clock)) { a.addItem(Item(0, 0, 0, a.looks.cols * a.sub, a.sub, action = "clock")); Unit },
            Triple(if (a.locked) "unlock" else "lock", a.getString(if (a.locked) R.string.unlock else R.string.lock)) {
                a.looks["lockLayout"] = if (a.locked) "off" else "on"; a.toast(if (a.locked) R.string.layout_locked else R.string.layout_unlocked)
            },
            Triple("settings", a.getString(R.string.settings)) { a.showSettings() },
        ).filter { !a.locked || it.first !in listOf("widgets", "bolt", "clock") }
        val at = Rect(a.lastDown.x.toInt(), a.lastDown.y.toInt(), a.lastDown.x.toInt(), a.lastDown.y.toInt())
        showMenu(at, narrow = entries.size <= 4) { addActions(entries, 4, lead = setOf("image", "widgets", "settings")) }
    }

    /**
     * The widget picker, as Launcher3's: a row per app (icon, name, how many widgets), A to Z; tapping one opens its
     * widgets under it, A to Z, and closes the last one opened (#18). For one app ([pkg], or only one has widgets), its
     * widgets alone.
     */
    private fun addShortcutMenu() {
        val options = listOf(R.string.launcher_action, R.string.app_shortcut, R.string.activity).map(a::getString).toTypedArray()
        a.sheet().setTitle(R.string.add_to_home).setItems(options) { _, which ->
            when (which) {
                0 -> a.pickAction(a.getString(R.string.launcher_action), shortcut = false) { a.addItem(Item(0, 0, 0, a.sub, a.sub, action = it)) }
                1 -> {
                    if (!a.launcherApps.hasShortcutHostPermission()) {
                        a.toast(R.string.shortcut_requires_home)
                        return@setItems
                    }
                    val list = a.packageManager.queryIntentActivities(Intent(Intent.ACTION_CREATE_SHORTCUT), 0)
                        .sortedWith(compareBy(labelOrder()) { it.loadLabel(a.packageManager).toString() })
                    // Each with its app's icon and name: shortcut names alone ("Contact", "Direct dial") don't say whose they are.
                    a.pickList(a.getString(R.string.app_shortcut), list.map { it.loadIcon(a.packageManager) to it.loadLabel(a.packageManager) }, appIcon = true,
                        summaries = list.map { it.activityInfo.applicationInfo.loadLabel(a.packageManager) }) { i ->
                        val a = list[i].activityInfo
                        runCatching { this@HomeMenus.a.startActivityForResult(Intent(Intent.ACTION_CREATE_SHORTCUT).setComponent(ComponentName(a.packageName, a.name)), MainActivity.REQ_SHORTCUT) }
                    }
                }
                // PackageManager sees the personal profile only: a work app would offer no activities (#103).
                else -> a.pickApp(a.getString(R.string.activity), { it.info.user == Process.myUserHandle() }) { app ->
                    val acts = runCatching { a.packageManager.getPackageInfo(app.pkg, PackageManager.GET_ACTIVITIES).activities }
                        .getOrNull().orEmpty().filter { it.exported }
                    // An activity without a label of its own shows the app's ("Settings" a hundred times): name it from its class.
                    val appLabel = acts.firstOrNull()?.applicationInfo?.loadLabel(a.packageManager)?.toString()
                    val named = acts.map { a -> a to a.loadLabel(this@HomeMenus.a.packageManager).toString().trim().let { if (it.isEmpty() || it == appLabel) activityName(a.name) else it } }
                        .sortedWith(compareBy(labelOrder()) { it.second })
                    // Only an activity's own icon: without one (or naming the app's again) the header's app icon says it.
                    // Loaded directly, in the activity's theme, so one that fails comes back null: loadIcon would quietly
                    // give the app's icon instead. ponytail: icons built from the app's own drawable classes (Settings'
                    // homepage ones) can't inflate without loading its code into ours, which we won't; they get a blank slot.
                    val res = runCatching { a.packageManager.getResourcesForApplication(app.pkg) }.getOrNull()
                    val icons = named.map { (a, _) ->
                        if (a.icon == 0 || a.icon == a.applicationInfo.icon || res == null) null
                        else runCatching { res.getDrawable(a.icon, res.newTheme().apply { applyStyle(a.applicationInfo.theme, true); applyStyle(a.themeResource, true) }) }.getOrNull()
                    }
                    // An empty slot keeps the names lined up; no slot at all when no row has an icon.
                    val anyIcon = icons.any { it != null }
                    val rows = named.mapIndexed { i, (_, name) -> (icons[i] ?: if (anyIcon) android.graphics.drawable.ColorDrawable(0) else null) to name }
                    a.pickList(a.repo.label(app), rows, appIcon = anyIcon, summaries = named.map { it.first.name.substringAfterLast('.').substringAfterLast('$') },
                        search = true, keys = named.map { it.first.name }, headerIcon = a.repo.icon(app), count = { n ->
                            if (n == rows.size) a.resources.getQuantityString(R.plurals.screens, n, n)
                            else a.resources.getQuantityString(R.plurals.screens_matching, rows.size, n, rows.size)
                        }) { i ->
                        a.addItem(Item(0, 0, 0, a.sub, a.sub, pkg = app.pkg, cls = named[i].first.name, action = "activity"))
                    }
                }
            }
        }.show()
    }

    // ---- Actions ----

    fun perform(action: String) {
        when {
            action.startsWith("page:") -> a.workspace.snapTo(action.removePrefix("page:").toIntOrNull() ?: 0)
            action.startsWith("app:") -> a.repo.apps[action.removePrefix("app:")]?.let { a.launch(it, null) } ?: a.toast(R.string.app_not_installed)
            action.startsWith("shortcut:") -> shortcutAction(action)?.let { (p, id, u) -> a.startShortcut(p, id, a.repo.userOf(u), null) }
            else -> when (action) {
                "drawer" -> a.drawer.show(false)
                "now" -> if (a.looks.on("nowPage")) a.showNow()
                "search" -> a.drawer.show(true)
                "notifications" -> statusBar("expandNotificationsPanel", AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                "quicksettings" -> statusBar("expandSettingsPanel", AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
                "togglebar" -> { a.barHidden = !a.barHidden; a.applyBar() }
                "recents" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
                "lock" -> if (android.os.Build.VERSION.SDK_INT >= 28) global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) else a.toast(R.string.lock_needs_9)
                "power" -> global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
                "defaultpage" -> a.workspace.snapTo(a.looks.int("defaultPage"))
                "previews" -> a.showOverview()
                // Keep an enabled dock's space when the gesture hides it so the home grid doesn't move (#84).
                "toggledock" -> {
                    a.dockBox.visibility = if (a.dockBox.visibility != View.VISIBLE) View.VISIBLE else if (a.looks.dockOn) View.INVISIBLE else View.GONE
                    a.coverDock()
                }
                "voice" -> startAny(Intent(RecognizerIntent.ACTION_WEB_SEARCH), Intent(Intent.ACTION_VOICE_COMMAND))
                "assistant" -> startAny(Intent(Intent.ACTION_ASSIST), Intent(Intent.ACTION_VOICE_COMMAND))
                "settings" -> a.showSettings()
                "widgets" -> a.widgetFlow.pickWidget()
                "wallpaper" -> a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), a.getString(R.string.wallpaper)))
                "torch" -> toggleTorch()
                "locklayout" -> { a.looks["lockLayout"] = if (a.locked) "off" else "on"; a.toast(if (a.locked) R.string.layout_locked else R.string.layout_unlocked) }
                "work" -> toggleWork()
            }
        }
    }

    /** Pauses or resumes every work profile; Android lets the default home app do this (API 28+). */
    private fun toggleWork() {
        val work = a.repo.workUsers
        if (work.isEmpty() || android.os.Build.VERSION.SDK_INT < 28) return a.toast(R.string.no_work_profile)
        val pause = work.any { !a.um.isQuietModeEnabled(it) }
        // A word on what happened, as the switch has no other sign until an app is opened (#73).
        runCatching { work.map { a.um.requestQuietModeEnabled(pause, it) } }.onSuccess { done -> if (done.any { it }) a.toast(if (pause) R.string.work_paused else R.string.work_resumed) }
            .onFailure { a.toast(R.string.no_work_profile) }
    }

    private var torchOn = false
    /** Keeps [torchOn] current, also when the torch is switched elsewhere (quick settings). Registered on first use. */
    var torchWatch: android.hardware.camera2.CameraManager.TorchCallback? = null

    /** Needs no permission: setTorchMode doesn't open the camera. */
    private fun toggleTorch() {
        val cm = a.getSystemService(android.hardware.camera2.CameraManager::class.java)
        val id = runCatching {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        }.getOrNull() ?: return a.toast(R.string.no_flashlight)
        // A camera app holding the camera makes this fail.
        fun switch() { runCatching { cm.setTorchMode(id, !torchOn) }.onSuccess { torchOn = !torchOn }.onFailure { a.toast(R.string.no_flashlight) } }
        if (torchWatch != null) return switch()
        // The first report after registering is the torch as it is now, perhaps already on (quick settings): the first switch waits for it.
        var first = true
        torchWatch = object : android.hardware.camera2.CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId != id) return
                torchOn = enabled
                if (first) { first = false; switch() }
            }
            override fun onTorchModeUnavailable(cameraId: String) { if (cameraId == id && first) { first = false; a.toast(R.string.no_flashlight) } }
        }.also { cm.registerTorchCallback(it, null) }
    }

    private fun startAny(vararg intents: Intent) {
        for (i in intents) try { a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: ActivityNotFoundException) {}
        a.toast(R.string.no_app_handles)
    }

    /** Hidden StatusBarManager API (needs EXPAND_STATUS_BAR); falls back to the accessibility service. */
    @SuppressLint("WrongConstant")
    private fun statusBar(method: String, fallback: Int) {
        val ok = runCatching {
            val sbm = a.getSystemService("statusbar")
            sbm.javaClass.getMethod(method).invoke(sbm)
        }.isSuccess
        if (!ok) global(fallback)
    }

    private fun global(action: Int) {
        val svc = ActionService.instance
        if (svc != null) { svc.performGlobalAction(action); return }
        a.requestActionAccess()
    }
}
