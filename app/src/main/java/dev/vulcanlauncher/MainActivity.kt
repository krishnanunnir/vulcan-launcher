package dev.vulcanlauncher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.app.WallpaperManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Drag payload for an item dragged out of a home-screen folder. */
/**
 * An item inside the home folder on cell ([page], [x], [y]). The child itself and the folder's cell, not a copy of the
 * folder and a position in it: a rename, sort or uninstall since would leave those pointing at the wrong thing.
 */
class FolderChild(val page: Int, val x: Int, val y: Int, val child: Item)

/** Drag payload for an app in the drawer folder named [group]: it reorders there; dropped on home, it's a copy. */
class GroupChild(val group: String, val app: App)

/** How far the page shrinks while dragging (Launcher3's spring-loaded state is about 0.8). */
const val SPRING_SCALE = 0.8f

/** The shrunk page's scale: [SPRING_SCALE], or less so a [height]-tall page whose top lands at [top] ends by [bottom]. */
fun springScale(height: Int, top: Float, bottom: Float) =
    if (height <= 0) SPRING_SCALE else ((bottom - top) / height).coerceIn(0.5f, SPRING_SCALE)

/** What home shows: itself, the hold-home buttons over the shrunk page, or a drag over the shrunk page. */
enum class HomeState { NORMAL, OVERVIEW, DRAG }

/** A [w]-wide menu's left edge: on the anchor's left edge, or its right edge when that runs off a [width]-wide screen. */
fun menuX(left: Int, right: Int, w: Int, width: Int, margin: Int) =
    (if (left + w <= width - margin) left else right - w).coerceIn(margin, maxOf(margin, width - w - margin))

class MainActivity : VulcanActivity() {
    companion object {
        const val HOST_ID = 1024
        const val REQ_BIND = 1
        const val REQ_CONFIG = 2
        const val REQ_SHORTCUT = 3
        const val REQ_BACKUP = 4
        const val REQ_RESTORE = 5
        const val REQ_ICON = 6
        const val REQ_RECONFIG = 7
        const val REQ_DOC = 8
        const val REQ_PERMISSION = 9
        const val REQ_HOME = 10
        const val REQ_ACTION_SHORTCUT = 11
        /** The live launcher activity, for the on-device check (androidTest/DeviceCheck). */
        var running: java.lang.ref.WeakReference<MainActivity>? = null
    }

    override val home get() = this
    /**
     * A setting changed in SettingsActivity: home is built again a moment later, while settings still covers it, so
     * pressing Home doesn't wait for it (#100: ~200 ms in onStart). onStart still catches one that hasn't run yet.
     */
    var stale = false
        set(v) { field = v; mainHandler.removeCallbacks(rebuildStale); if (v) mainHandler.postDelayed(rebuildStale, 400) }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val rebuildStale = Runnable { if (stale && !isDestroyed) { stale = false; rebuild() } }
    lateinit var icons: Icons
    lateinit var items: MutableList<Item>
    lateinit var host: AppWidgetHost
    lateinit var awm: AppWidgetManager
    lateinit var launcherApps: LauncherApps
    lateinit var um: UserManager
    /** Apps, labels, icons and shortcuts. */
    lateinit var repo: AppRepo
    /** Home pages, indicator and dock: blurred while a panel is open. */
    lateinit var content: LinearLayout
    lateinit var workspace: Workspace
    lateinit var pages: List<CellLayout>
    lateinit var dock: Workspace
    lateinit var dockPages: List<CellLayout>
    lateinit var dockBox: LinearLayout
    lateinit var indicator: Indicator
    lateinit var drawer: Drawer
    /** The open folder window, desktop previews or resize frame, and what to do when it closes. */
    var overlay: View? = null
    var overlayClosed: () -> Unit = {}
    /** Plays the open overlay's exit animation, then calls its argument; null: the overlay just goes. */
    var overlayExit: ((done: () -> Unit) -> Unit)? = null
    /** Shows the open folder window's current apps after one is taken out; null when no folder is open. */
    var refillFolder: (() -> Unit)? = null
    val views = HashMap<Item, View>()
    val viewItems = HashMap<View, Item>()
    val folderFx = FolderFx(this)
    val homeMenus = HomeMenus(this)
    val drag = DragController(this)
    var barHidden = false
    val widgetFlow = WidgetFlow(this)
    val iconViews = ArrayList<IconView>()
    var onPickedImage: ((Uri) -> Unit)? = null
    var onDocument: ((Uri) -> Unit)? = null

    /** Subgrid factor of the grid, set when home is built (onCreate, [rebuild]). */
    var sub = 1
        private set
    val accent get() = m3.primary
    val locked get() = looks.on("lockLayout")
    val landscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    /** Housing, card and glass docks float above the bottom edge instead of running under the navigation bar. */
    val floatingDock get() = looks.str("dockBg") in listOf("housing", "card", "glass")
    /** How far home sits behind a panel (0–1), and its running animation (see setDepth, blurHome). */
    var depth = 0f
    var depthAnim: android.animation.ValueAnimator? = null
    /** The Now page built ahead and when, and the delayed build that makes it (see prepareNow). */
    var nowReady: Pair<Long, NowPanel>? = null
    val nowBuilder = Runnable { prebuildNow() }
    /** Where the last touch went down, in window coordinates; menus opened by a long-press appear there. */
    val lastDown = PointF()

    /** A picker's result reached a launcher that was restarted meanwhile, so what it was for is gone. */
    private fun restarted() = toast(R.string.picker_restarted)

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (handOffToHome()) return
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Debug builds only: log main-thread disk and network access, and leaked activities and registrations.
            android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            android.os.StrictMode.setVmPolicy(android.os.StrictMode.VmPolicy.Builder().detectActivityLeaks().detectLeakedRegistrationObjects()
                .detectLeakedClosableObjects().penaltyLog().build())
        }
        running = java.lang.ref.WeakReference(this)
        store = Store(this)
        looks = Looks(this)
        looks.adoptDefaults(upgrade = !store.isNew)
        markWelcome()
        awm = AppWidgetManager.getInstance(this)
        host = AppWidgetHost(this, HOST_ID)
        // The application's LauncherApps, not this activity's: the system keeps its listener binder (and so its context)
        // alive after unregisterCallback, which leaked every recreated activity (~20 MB each).
        launcherApps = applicationContext.getSystemService(LauncherApps::class.java)
        um = getSystemService(UserManager::class.java)
        repo = AppRepo(this, store, launcherApps, um)
        build()
        store.data.getString("pendingWidget", "")!!.split(" ").takeIf { it.size == 2 }?.let { (id, provider) ->
            val c = ComponentName.unflattenFromString(provider)
            awm.installedProviders.firstOrNull { it.provider == c }?.let { widgetFlow.pendingWidget = id.toInt() to it }
        }
        // A fresh start gets no result for a setup begun before it (a reboot or crash mid-setup): let that widget go,
        // or its spot would catch the next widget added and an import's restore would never resume.
        if (savedInstanceState == null && widgetFlow.pendingWidget != null) {
            widgetFlow.pendingWidget?.let { host.deleteAppWidgetId(it.first) }
            widgetFlow.pendingWidget = null; widgetFlow.pendingSlot = null
        }
        // A widget whose ID is gone (its app uninstalled while Vulcan wasn't running) has no view, but would hold its cells.
        runCatching { host.appWidgetIds.toSet() }.getOrNull()?.let { ids ->
            val kept = keepWidgets(items) { it in ids }
            if (kept != items) { items.clear(); items += kept; save(); render() }
        }
        // Widget IDs no item holds (the process died mid-flow, a pin failed, an undo bar was cut short) still get
        // updates from their providers: free them.
        val held = items.flatMap { it.widgetIds } + nowCards().flatMap { it.widgetIds } + listOfNotNull(widgetFlow.pendingWidget?.first)
        host.appWidgetIds.filter { it !in held }.forEach(host::deleteAppWidgetId)
        launcherApps.registerCallback(appsCallback)
        val dateFilter = android.content.IntentFilter(Intent.ACTION_DATE_CHANGED).apply {
            addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED) }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(dateReceiver, dateFilter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(dateReceiver, dateFilter)
        // 34 and later report every kind of profile; before that only the work profile's own broadcasts exist.
        val profileFilter = android.content.IntentFilter(Intent.ACTION_MANAGED_PROFILE_ADDED).apply {
            addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
            if (android.os.Build.VERSION.SDK_INT >= 34) { addAction(Intent.ACTION_PROFILE_AVAILABLE); addAction(Intent.ACTION_PROFILE_UNAVAILABLE) }
            else { addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE); addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE) }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(profileReceiver, profileFilter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(profileReceiver, profileFilter)
        BadgeService.onChange = { pkgs -> runOnUiThread { if (pkgs.isNotEmpty()) { updateBadges(pkgs); drawer.refreshBadges() }; updateGlance(); prepareNow() } }
        // Rotation recreates the activity: stay on the page the user was on.
        savedInstanceState?.getInt("page", -1)?.takeIf { it in 0 until looks.pages }?.let { workspace.snapTo(it, animate = false) }
        // Just imported: put the widgets back (not when a restore was already under way and this is its restart).
        // While the welcome shows, its widgets step starts this instead, after saying what Android will ask.
        if (widgetFlow.pendingWidget == null && !welcoming) root.post { widgetFlow.restoreNextWidget() }
    }

    /**
     * Applies changed settings by building home again in this window, as Launcher3 does for a grid change (onIdpChanged).
     * recreate() tears the window down, and on older Android versions the wallpaper or a blank frame showed in between.
     * Settings changed in SettingsActivity only mark home [stale]; it's built once, before it shows again.
     */
    fun rebuild() {
        val page = workspace.current
        depthAnim?.cancel()
        // Everything below belongs to the old views; none of it is shown again.
        // setDepth, not the field alone: the window's blur outlives the old views.
        overlay = null; overlayClosed = {}; overlayExit = null; refillFolder = null; setDepth(0f); homeMenus.menu = null
        drag.reset()
        badgeIcons.clear(); widgetFlow.keepViews(emptySet()) // build() reloads the apps, which drops the cached icons
        build()
        workspace.snapTo(page.coerceAtMost(looks.pages - 1), animate = false)
        if (dialogs.any { it.isShowing }) dialogScrim(true)
    }

    /** Everything the settings shape: palette, icons, layout and the views. At creation, and again in [rebuild]. */
    @Suppress("DEPRECATION")
    private fun build() {
        loadItems()
        styleWindow()

        val gesture = { g: String -> homeMenus.perform(looks.str("g_$g")) }
        pages = List(looks.pages) { drag.newPage(it) }
        indicator = Indicator(this, looks.pages, looks.str("indicator"), looks.color("labelColor"), m3.signal)
        workspace = Workspace(this).apply {
            paddingOf(looks.str("pageMargin")).let { (x, t, b) -> marginX = (x * dp).toInt(); marginTop = (t * dp).toInt(); marginBottom = (b * dp).toInt() }
            pages.forEach { addView(it) }
            onGesture = gesture
            effect = looks.str("scrollEffect")
            infinite = looks.on("infiniteScroll")
            onScroll = { indicator.position = it; wallpaperScroll(it) }
            snapTo(looks.int("defaultPage").coerceAtMost(looks.pages - 1), animate = false)
        }
        val search = looks.str("searchBar")
        buildDock(gesture, search)
        val pagesBox = pagesBox(search)
        content = if (!landscape) pagesBox else LinearLayout(this).apply {
            addView(pagesBox, LinearLayout.LayoutParams(0, -1, 1f))
            addView(dockBox, LinearLayout.LayoutParams(-2, -1).apply {
                if (floatingDock) setMargins((4 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt())
            })
        }
        content.forceHasOverlappingRendering(false) // faded as the drawer opens: no full-screen offscreen buffer (see Drawer.root)
        drawer = Drawer(this)
        // Swipe up opens the drawer under the finger instead of after the gesture ends.
        if (looks.str("g_swipeUp") == "drawer") { workspace.onPull = drawer::pull; dock.onPull = drawer::pull }
        // Likewise swipe right on the first page pulls the Now page in under the finger.
        if (looks.str("g_swipeRight") == "now") workspace.onEdgePull = ::pullNow
        drag.dropBar = dropBar()
        // The window keeps a root's listener after the root is replaced: without this each rebuild added one more.
        listening?.viewTreeObserver?.removeOnGlobalLayoutListener(coverDockListener)
        root = homeRoot()
        listening = root
        setContentView(root)
        root.viewTreeObserver.addOnGlobalLayoutListener(coverDockListener)
        render()
        showWelcome()
    }

    /** The stored items, fitted to the grid the settings give. */
    private fun loadItems() {
        sub = looks.sub
        icons = Icons(this, looks)
        repo.restyle(icons)
        recolor()
        applyOrientation()
        repo.loadApps()
        // Items of apps uninstalled while the launcher wasn't running (no callback came) would hold their cells unseen.
        items = fit(if (store.isNew) defaultLayout() else removeApps(store.load(sub), repo::uninstalled))
        // Before 0.24 the clock was a strip fixed above the first page ("glance"); now it's an item on the first free row.
        if (looks.raw("glance") == "on") {
            looks["glance"] = "item"
            firstFreeSpot(items.filter { !isDock(it.page) }, looks.pages, looks.cols * sub, looks.rows * sub, looks.cols * sub, sub)
                ?.let { items += it.copy(action = "clock") }
        }
        save()
    }

    /** Transparent system bars, home drawn behind them and into the notch. */
    @Suppress("DEPRECATION")
    private fun styleWindow() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            (if (looks.on("darkStatusIcons")) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0)
        // No grey scrim behind three-button navigation, and a hidden status bar gives its space (notch included) to home.
        // After decorView: installing the decor resets these from the theme.
        if (android.os.Build.VERSION.SDK_INT >= 29) { window.isNavigationBarContrastEnforced = false; window.isStatusBarContrastEnforced = false }
        if (android.os.Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        barHidden = looks.str("statusBar") == "hide"
        applyBar()
    }

    /** The dock's pages and the box around them, with its background and its search bar. */
    private fun buildDock(gesture: (String) -> Unit, search: String) {
        dockPages = List(looks.dockPages) { CellLayout(this, looks.dockCols, 1, -1 - it, landscape).apply { setOnDragListener(drag.dropListener(this)) } }
        dock = Workspace(this).apply { dockPages.forEach { addView(it) }; onGesture = gesture }
        dockBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (looks.dockOn) View.VISIBLE else View.GONE
            // Landscape: the dock is a column down the right edge; its search bar moves under the pages.
            if (search == "dockAbove" && !landscape) addView(searchBar())
            val labels = if (looks.on("dockLabels")) 22 else 0
            addView(dock, if (landscape) LinearLayout.LayoutParams(((looks.int("dockIconSize") + 28) * dp).toInt(), -1)
                else LinearLayout.LayoutParams(-1, ((looks.int("dockIconSize") + 28 + labels) * dp).toInt()))
            if (search == "dockBelow" && !landscape) addView(searchBar())
            val bg = looks.str("dockBg")
            // Glass: a light frost that's a little brighter at the top, with a hairline edge; on Samsung it really
            // blurs the wallpaper behind it.
            // Housing: an Appliance body standing off the wallpaper, a pill in the housing colour (or the chosen one)
            // with a recessed channel that the icons sit in.
            if (bg == "housing") {
                val c = themed("dockColor", m3.surface)
                housing(this, 1000 * dp, c)
                background = android.graphics.drawable.LayerDrawable(arrayOf(housingFace(c, 1000 * dp), well(1000 * dp, blend(c, Color.BLACK, if (m3.dark) 0.3f else 0.06f))))
                    .apply { val i = (5 * dp).toInt(); setLayerInset(1, i, i, i, i) }
            }
            else if (bg == "glass") {
                val c = if (looks.str("dockColor") == "system") 0xFFFFFF else looks.color("dockColor") and 0xFFFFFF
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(c or 0x3D000000, c or 0x24000000)).apply {
                    cornerRadius = 32 * dp
                    setStroke(maxOf(1, (dp / 2).toInt()), 0x2EFFFFFF)
                }
                if (viewBlur) semBlur((24 * dp).toInt(), c or 0x33000000, 32 * dp)
            } else if (bg != "none") background = GradientDrawable().apply {
                setColor(themed("dockColor", m3.surfaceContainer) and 0xFFFFFF or ((looks.int("dockAlpha") * 255 / 100) shl 24))
                cornerRadius = if (bg == "rect") 0f else 28 * dp
            }
            if (bg != "none" && bg != "housing" && looks.on("dockShadow")) {
                elevation = 8 * dp
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            }
        }
    }

    /** The pages with what sits under them: indicator, swipe arrow, and in portrait the dock. */
    private fun pagesBox(search: String): LinearLayout {
        val dockHeight = ((looks.int("dockIconSize") + 28 + (if (looks.on("dockLabels")) 22 else 0)) * dp).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (search == "top") addView(searchBar())
            // As an overlay, later children (indicator, dock) are pulled up over the bottom of the pages.
            val overlay = looks.on("dockOverlay") && looks.dockOn && !landscape
            addView(workspace, LinearLayout.LayoutParams(-1, 0, 1f).apply { if (overlay) bottomMargin = -dockHeight })
            // Even for one page (it's gone then): a page added in the overview brings it in without a rebuild.
            // The strips between pages and dock pass their touches on to the pages, so swipes and double taps start there too (#68).
            val toPages = View.OnTouchListener { v, e ->
                val at = IntArray(2).also(v::getLocationInWindow)
                val pages = IntArray(2).also(workspace::getLocationInWindow)
                val ev = MotionEvent.obtain(e).apply { offsetLocation((at[0] - pages[0]).toFloat(), (at[1] - pages[1]).toFloat()) }
                workspace.dispatchTouchEvent(ev).also { ev.recycle() }
            }
            if (looks.str("indicator") != "none") addView(indicator.apply { setOnTouchListener(toPages) }, LinearLayout.LayoutParams(-1, (14 * dp).toInt()))
            if (looks.on("swipeIndicator")) addView(FrameLayout(context).apply {
                setOnTouchListener(toPages)
                // Only the arrow itself opens the drawer; it passes its touches on as well, so a swipe can start on it.
                addView(TextView(context).apply {
                    text = "︿"
                    gravity = Gravity.CENTER
                    setTextColor(looks.color("labelColor"))
                    contentDescription = getString(R.string.action_drawer)
                    setOnClickListener { homeMenus.perform("drawer") }
                    setOnTouchListener { v, e -> toPages.onTouch(v, e); false }
                }, FrameLayout.LayoutParams((64 * dp).toInt(), -1, Gravity.CENTER))
            }, LinearLayout.LayoutParams(-1, (20 * dp).toInt()))
            if (landscape && search in listOf("dockAbove", "dockBelow")) addView(searchBar())
            if (!landscape) addView(dockBox, LinearLayout.LayoutParams(-1, -2).apply {
                if (floatingDock) setMargins((14 * dp).toInt(), (4 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
            })
            if (search == "bottom") addView(searchBar())
            // Spring-loaded, the page is a card: holding a drag beside it turns the page, as the edge of a full page does.
            setOnDragListener { v, e ->
                if (e.localState !is PageMove) drag.trackDrag(v, e)
                if (e.action == DragEvent.ACTION_DRAG_LOCATION && drag.spring > 0f) {
                    val r = Rect().also(workspace::getHitRect)
                    if (e.y >= r.top && e.y <= r.bottom && (e.x < r.left || e.x > r.right))
                        drag.edgeFlip(workspace, if (e.x < r.left) 0f else workspace.width.toFloat(), workspace.width)
                }
                e.action != DragEvent.ACTION_DROP
            }
        }
    }

    /** Remove and Uninstall, shown at the top while an item is dragged. */
    private fun dropBar(): LinearLayout {
        return LinearLayout(this).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            alpha = 0f
            val p = (12 * dp).toInt()
            setPadding(p, p, p, p)
            // As in Launcher3: Remove (Cancel from the drawer), and Uninstall for apps that can be. App info is in the menu.
            listOf("remove", "uninstall").forEach {
                addView(drag.dropTarget(it), LinearLayout.LayoutParams(-2, (48 * dp).toInt()).apply { setMargins(p / 2, 0, p / 2, 0) })
            }
        }
    }

    /** Home, the drawer and the drop bar stacked, each kept clear of the system bars. */
    @Suppress("DEPRECATION")
    private fun homeRoot(): FrameLayout {
        return FrameLayout(this).apply {
            addView(content)
            addView(drawer.root)
            addView(drag.dropBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            setOnApplyWindowInsetsListener { _, ins ->
                // LAYOUT_STABLE keeps reporting the status bar while it's hidden: only stay clear of a notch then.
                val top = if (!barHidden) ins.systemWindowInsetTop
                    else if (android.os.Build.VERSION.SDK_INT >= 28) ins.displayCutout?.safeInsetTop ?: 0 else 0
                val behind = looks.on("dockBehindNav") && looks.dockOn && !floatingDock && !landscape
                // The keyboard (renaming a folder) mustn't squash home: only the drawer and the folder card move for it.
                // Ignoring visibility: another app's popup that hides the nav bar must not drop the dock (#124).
                val bars = if (android.os.Build.VERSION.SDK_INT >= 30) ins.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars()).bottom
                    else ins.stableInsetBottom
                content.setPadding(ins.systemWindowInsetLeft, top, ins.systemWindowInsetRight, if (behind) 0 else bars)
                dockBox.setPadding(0, 0, 0, if (behind) bars else 0)
                drawer.applyInsets(ins.systemWindowInsetLeft, top, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
                drag.dropBar.setPadding(drag.dropBar.paddingLeft, top + drag.dropBar.paddingBottom, drag.dropBar.paddingRight, drag.dropBar.paddingBottom)
                insets.set(ins.systemWindowInsetLeft, top, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
                ins
            }
        }
    }

    /**
     * As an overlay the grid runs behind the dock: home icons under the shown dock hide so the two never draw over
     * each other, and come back when the dock is toggled off. Widgets stay, they're what the space behind is for.
     * Layout positions, not screen ones, so a shrunk page in the overview doesn't change the answer.
     */
    fun coverDock() {
        val dockTop = if (looks.on("dockOverlay") && !landscape && dockBox.visibility == View.VISIBLE) dockBox.top + dock.top else Int.MAX_VALUE
        for (cell in pages) for (i in 0 until cell.childCount) cell.getChildAt(i).takeIf { it !is WidgetFrame }?.let {
            it.visibility = if (workspace.top + cell.top + it.top + it.height / 2 > dockTop) View.INVISIBLE else View.VISIBLE
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        // Before lastDown moves: a new DOWN must drop a pending drag measured from the old one.
        drag.onTouch(ev)
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) lastDown.set(ev.x, ev.y)
        return super.dispatchTouchEvent(ev)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", workspace.current)
        outState.putIntArray("insets", intArrayOf(insets.left, insets.top, insets.right, insets.bottom))
    }

    /** Back inside an overlay: its own back handler (a view tag) if it has one, else close it. */
    private fun backInOverlay() = (overlay?.tag as? Runnable)?.run() ?: closeOverlay()

    override fun onDestroy() {
        super.onDestroy()
        if (!::launcherApps.isInitialized) return // handed off to home in onCreate
        launcherApps.unregisterCallback(appsCallback)
        unregisterReceiver(dateReceiver)
        unregisterReceiver(profileReceiver)
        // A copy left over from before Vulcan became the home app must not take the live one's badge updates away.
        if (running?.get() === this) BadgeService.onChange = {}
        homeMenus.torchWatch?.let { getSystemService(android.hardware.camera2.CameraManager::class.java).unregisterTorchCallback(it) }
    }

    override fun onStart() {
        super.onStart()
        if (stale) { stale = false; rebuild() }
        // The system answers with every widget's waiting update at once; too big a reply throws, and home must still open.
        runCatching { host.startListening() }
    }

    override fun onStop() {
        super.onStop()
        // Stops the host id for the whole process: only the live copy may (see onDestroy).
        if (running?.get() === this) host.stopListening()
        if (drawer.closeOnStop) { drawer.closeOnStop = false; drawer.hide(animate = false) }
        // Daily automatic backup, kept privately on the device and offered in Backup & restore.
        // Once a day (not on every stop) so a reset or bad change can't overwrite the last good copy straight away.
        val auto = store.autoBackups[0]
        if (!auto.exists() || System.currentTimeMillis() - auto.lastModified() > 24 * 3600_000L) runCatching { store.rotateAutoBackup() }
    }

    /**
     * Memory pressure: keep only the icons used last when home hides, and none under a complete trim. Emptying
     * the cache on every hide meant the next drawer open decoded every icon again on the UI thread, which is what
     * made scrolling lag until the icons were cached once more. The disk cache stays the fallback.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Not set in an activity that handed off to home in onCreate.
        if (::repo.isInitialized && level >= TRIM_MEMORY_UI_HIDDEN) repo.trim(all = level >= TRIM_MEMORY_COMPLETE)
    }

    /** Between onResume and onPause: home is what the user sees. */
    var resumed = false
        private set

    override fun onResume() {
        super.onResume()
        resumed = true
        // A copy opened from another launcher before Vulcan became the default one.
        if (handOffToHome()) return
        updateLocation()
        if (nightChanged()) rebuild()
        refreshWelcome()
        setClockHidden(true)
        updateGlance()
        // PinActivity may have added an item while we were in the background.
        reloadItems()
        // Shortcuts restored before Vulcan was the default home app: pinned now that it is, and drawn with their icons.
        if (store.data.getBoolean(REPIN_SHORTCUTS, false)) { repinShortcuts(); if (!store.data.getBoolean(REPIN_SHORTCUTS, false)) render() }
        // Calendar, media and the apps' launch counts may have changed while away; not while the page is open.
        if (overlay !is NowPanel) prepareNow()
    }

    /** Takes [items] from the store, where PinActivity may have added something meanwhile. */
    private fun reloadItems() {
        val stored = fit(store.load(sub))
        if (stored != items) { items = stored; render() }
    }

    /**
     * Started outside the home task (another launcher's icon, Settings → Open, `am start -n`) while Vulcan is the default
     * home: hands over to the home copy and finishes, so only one activity ever edits the layout. Two copies each
     * saved their own [items], and the one in the background overwrote the other's changes.
     */
    private fun handOffToHome(): Boolean {
        if (intent.hasCategory(Intent.CATEGORY_HOME)) return false
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        if (packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName != packageName) return false
        // Task removed first: on Android 11 this singleTask copy otherwise took the home intent itself and then
        // finished, leaving the previous app in front.
        finishAndRemoveTask()
        startActivity(home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        setClockHidden(false)
    }

    /** Persisted, so a launcher killed while showing (no onPause) still puts the clock back on its next start. */
    private var addedClock: Boolean
        get() = looks.raw("clockHiddenByUs") == "1"
        set(v) { looks["clockHiddenByUs"] = if (v) "1" else "0" }

    /** "Hide clock": hides the status-bar clock while the home screen is showing. Needs WRITE_SECURE_SETTINGS (granted over adb). */
    private fun setClockHidden(hide: Boolean) {
        if (!looks.on("hideClock") && !addedClock) return
        if (!granted(android.Manifest.permission.WRITE_SECURE_SETTINGS)) return
        // Only our own "clock" token is added or removed; the rest of the system's list is left exactly as it was.
        val list = Settings.Secure.getString(contentResolver, "icon_blacklist").orEmpty().split(",").filter { it.isNotBlank() }.toMutableList()
        val want = hide && looks.on("hideClock")
        if (want && "clock" !in list) { list += "clock"; addedClock = true }
        else if (!want && addedClock) { list.remove("clock"); addedClock = false }
        else return
        runCatching { Settings.Secure.putString(contentResolver, "icon_blacklist", list.joinToString(",")) }
            .onFailure { android.util.Log.w("Vulcan", "hide clock: can't write icon_blacklist", it) }
    }

    /** For sunset-to-sunrise night mode: the last location Android already knows. No new fix is requested. */
    @SuppressLint("MissingPermission")
    private fun updateLocation() {
        if (looks.str("nightMode") != "sun" || !granted(android.Manifest.permission.ACCESS_COARSE_LOCATION)) return
        val lm = getSystemService(android.location.LocationManager::class.java)
        val loc = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } ?: return
        looks["lat"] = loc.latitude.toString()
        looks["lon"] = loc.longitude.toString()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSION) rebuild()
    }

    /** Sends a query to the chosen search engine (in the browser) or to the default web search app. */
    fun webSearch(q: String) {
        val url = searchUrl(looks.str("searchEngine"), q)
        val intent = if (url != null) Intent(Intent.ACTION_VIEW, Uri.parse(url))
            else Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q)
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { toast(R.string.no_web_search_app) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!::drawer.isInitialized) return // handed off to home in onCreate; Android 11 still delivers that home intent here
        // Without focus an app was in front: coming home only closes what was open, the action is for a second press.
        val onHome = hasWindowFocus()
        // Home leaves pickers and dialogs too (#99: the icon pack grid kept it), at once: their slide-outs, one per open
        // dialog, ran while the activity paused and resumed under them, and stuttered (#100).
        dialogs.toList().forEach { it.window?.setWindowAnimations(0); it.dismiss() }
        when {
            // The welcome (or an import's review) covers home: a panel opened now would sit over it, and Back would step the hidden welcome.
            welcoming -> {}
            homeMenus.menu != null -> homeMenus.closeMenu()
            overlay != null -> closeOverlay()
            drawer.visible -> drawer.hide()
            onHome -> {
                val action = looks.str("homeButton")
                val main = looks.int("defaultPage").coerceAtMost(looks.pages - 1)
                if (looks.on("homeFirst") && workspace.current != main) workspace.snapTo(main) else homeMenus.perform(action)
            }
        }
    }

    override fun handleBack() {
        when {
            welcoming -> welcomeBack()
            homeMenus.menu != null -> homeMenus.closeMenu()
            overlay != null -> backInOverlay()
            else -> drawer.hide()
        }
    }

    fun fit(list: List<Item>) = fitItems(list, looks.pages, looks.cols * sub, looks.rows * sub, looks.dockCols, looks.dockPages, sub, looks.on("widgetOverlap")).toMutableList()

    fun save() = store.save(items, sub)
    /** Puts [item] in place of the one at [i], saves and redraws. */
    fun replace(i: Int, item: Item) { items[i] = item; save(); render() }

    /** First run: the default phone, messages, browser, camera and gallery apps in the dock (as many as fit). */
    /** A fresh install's layout, as Nova sets it up: a five-app dock, mail and photos above it, settings top right. */
    fun defaultLayout(): MutableList<Item> {
        val me = repo.serial(Process.myUserHandle())
        fun role(category: String) = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category)
        fun apps(vararg intents: Intent) = intents.mapNotNull { intent ->
            // The user's default first; with no default set, resolveActivity returns the chooser, so fall back to any handler.
            val handlers = listOfNotNull(packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)) + packageManager.queryIntentActivities(intent, 0)
            handlers.firstNotNullOfOrNull { r -> repo.apps.values.firstOrNull { it.pkg == r.activityInfo.packageName && it.serial == me } }
        }.distinctBy { it.key }
        val dock = apps(Intent(Intent.ACTION_DIAL), role(Intent.CATEGORY_APP_MESSAGING), role(Intent.CATEGORY_APP_MAPS),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://")), Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)).take(looks.dockCols)
        val home = apps(role(Intent.CATEGORY_APP_EMAIL), role(Intent.CATEGORY_APP_GALLERY)).filter { a -> dock.none { it.key == a.key } }
        return (dock.mapIndexed { i, a -> Item(DOCK, i, 0, pkg = a.pkg, cls = a.cls, user = a.serial) } +
            home.mapIndexed { i, a -> Item(0, i * sub, (looks.rows - 1) * sub, sub, sub, pkg = a.pkg, cls = a.cls, user = a.serial) } +
            Item(0, (looks.cols - 1) * sub, 0, sub, sub, action = "settings")).toMutableList()
    }

    private val appsCallback = object : LauncherApps.Callback() {
        // Both write the layout: start from the stored one, which PinActivity may have changed while we were in the background.
        override fun onPackageAdded(pkg: String, user: UserHandle) {
            reloadItems()
            reload(listOf(pkg))
            // Not twice: a second MainActivity, or an app that was removed and came back, already has its icon.
            if (looks.on("autoAdd")) repo.activities(pkg, user)
                .filter { app -> items.none { i -> (i.children + i).any { it.pkg == app.pkg && it.cls == app.cls && it.user == app.serial } } }
                .forEach { addToHome(it, quiet = true) }
        }
        // Uninstalled (an update is onPackageChanged): its icons go too, or they'd hold their cells invisibly. The
        // system has already deleted its widgets.
        override fun onPackageRemoved(pkg: String, user: UserHandle) {
            reloadItems()
            items = keepWidgets(removePackage(items, pkg, repo.serial(user))) { awm.getAppWidgetInfo(it) != null }.toMutableList()
            save()
            reload(listOf(pkg))
        }
        // An update or a component switched on or off: home redraws only if it shows one of the package's apps.
        override fun onPackageChanged(pkg: String, user: UserHandle) = reload(listOf(pkg), homeOnly = true)
        override fun onPackagesAvailable(pkgs: Array<out String>, user: UserHandle, replacing: Boolean) = reload(pkgs.toList(), homeOnly = true)
        override fun onPackagesUnavailable(pkgs: Array<out String>, user: UserHandle, replacing: Boolean) = reload(pkgs.toList(), homeOnly = true)
        // Apps republish their shortcuts often (messaging apps on every message): redraw only for one pinned on home.
        override fun onShortcutsChanged(pkg: String, shortcuts: MutableList<ShortcutInfo>, user: UserHandle) {
            val s = repo.serial(user)
            if ((items + items.flatMap { it.children }).any { it.isShortcut && it.pkg == pkg && it.user == s }) render()
        }
        private fun reload(pkgs: List<String>, homeOnly: Boolean = false) {
            repo.loadApps(pkgs)
            drawer.invalidate()
            if (!homeOnly || (items + items.flatMap { it.children }).any { it.pkg in pkgs }) render()
        }
    }

    /** Midnight or a clock/time-zone change: date icons redraw with the new day. */
    private val dateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) {
            val dated = repo.datedPackages
            if (dated.isEmpty()) return
            repo.loadApps(dated); drawer.invalidate(); render()
        }
    }

    /** Private Space (or a work profile) locked, unlocked, paused or resumed. */
    private val coverDockListener = android.view.ViewTreeObserver.OnGlobalLayoutListener { coverDock() }
    /** The root [coverDockListener] is on. */
    private var listening: View? = null

    private val profileReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) {
            repo.loadApps(); drawer.invalidate(); render()
            // ponytail: a resumed profile answers shortcut queries only a moment later, with no callback; listen for user unlock if 2 s proves short.
            if (i.action == Intent.ACTION_PROFILE_AVAILABLE || i.action == Intent.ACTION_MANAGED_PROFILE_AVAILABLE) window.decorView.postDelayed(::render, 2000)
        }
    }

    fun launch(a: App, v: View?) {
        runCatching { launcherApps.startMainActivity(a.info.componentName, a.info.user, bounds(v), launchOptions(v)) }
            .onFailure { toast(R.string.cant_open, repo.label(a)) }
        store.countLaunch(a.key)
        if (drawer.visible && looks.on("closeAfterLaunch")) drawer.hide()
    }

    fun launches() = store.launches()

    private fun bounds(v: View?): Rect? = v?.let {
        val loc = IntArray(2).also { l -> it.getLocationOnScreen(l) }
        Rect(loc[0], loc[1], loc[0] + it.width, loc[1] + it.height)
    }

    fun launchOptions(v: View?): Bundle? = when (looks.str("launchAnim")) {
        "zoom" -> v?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }
        "reveal" -> v?.let { ActivityOptions.makeClipRevealAnimation(it, 0, 0, it.width, it.height).toBundle() }
        "fade" -> ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out).toBundle()
        "slideUp" -> ActivityOptions.makeCustomAnimation(this, R.anim.slide_up_in, R.anim.stay).toBundle()
        "slideLeft" -> ActivityOptions.makeCustomAnimation(this, android.R.anim.slide_in_left, android.R.anim.slide_out_right).toBundle()
        "none" -> ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
        else -> null
    }

    fun startShortcut(pkg: String, id: String, user: UserHandle, v: View?) {
        runCatching { launcherApps.startShortcut(pkg, id, bounds(v), launchOptions(v), user) }
            .onFailure { toast(R.string.shortcut_unavailable) }
    }

    /** Gesture and home button actions, the ones settings hold. */
    fun settingActions() = LOOKS.filter { it.action }.map { looks[it] }

    /** Pins exactly the shortcuts of [pkg] that home or an action uses (plus [also], about to be), so removed ones are released. */
    // ponytail: runs when a shortcut is picked, placed or removed, not when a gesture is cleared, so that one stays pinned until the next sync
    fun syncPins(pkg: String, user: Long, also: String? = null) {
        val ids = pinsByApp(items, settingActions())[pkg to user].orEmpty() + listOfNotNull(also)
        // A profile that's gone: userOf would fall back to the personal one, and pinning there unpins that app's own shortcuts.
        val u = um.getUserForSerialNumber(user) ?: return
        runCatching { launcherApps.pinShortcuts(pkg, ids.distinct(), u) }
    }

    // ---- Home screen ----

    fun style(surface: String): IconStyle {
        val size = looks.int(if (surface == "") "iconSize" else "${surface}IconSize")
        val labels = looks.on(if (surface == "") "labels" else "${surface}Labels")
        return IconStyle((size * dp).toInt(), labels, looks.labelSize("${surface}LabelSize"), looks.color("labelColor"),
            looks.on("labelShadow"), looks.on("iconShadow"), font = looks.str("labelFont"), lines = looks.int("labelLines"))
    }

    fun badgeStyle() = BadgeStyle(looks.str("badges"), looks.str("badgePos"),
        when (looks.str("badgeSize")) { "small" -> 0.8f; "large" -> 1.3f; else -> 1f }, looks.color("badgeColor"), looks.str("badgeShape"))

    /** Rebuilds every page from [items]. Apps that are missing (e.g. mid-update) are skipped, not deleted. */
    fun render() {
        (pages + dockPages).forEach { it.removeAllViews() }
        views.clear()
        viewItems.clear()
        iconViews.clear()
        val home = style("")
        val dockStyle = style("dock")
        for (item in items) {
            val cell = (if (isDock(item.page)) dockPages.getOrNull(dockIndex(item.page)) else pages.getOrNull(item.page)) ?: continue
            if (item.x + item.w > cell.cols || item.y + item.h > cell.rows) continue // no room after a grid change
            val v = itemView(item, if (isDock(item.page)) dockStyle else home) ?: continue
            v.layoutParams = CellLayout.CellParams(item.x, item.y, item.w, item.h)
            v.setOnLongClickListener { drag.startDrag(it, item); true }
            accessibleActions(v, { homeMenus.showPopup(v, item) }, if (locked || isDock(item.page)) null else { { moveToPage(item) } })
            cell.addView(v)
            views[item] = v
            viewItems[v] = item
        }
        widgetFlow.keepViews((items + nowCards()).flatMap { it.widgetIds }.toSet())
        updateBadges()
        updateGlance()
        folderFx.settle()
        refillFolder?.invoke() // an open folder shows what home now has
    }

    /** Label and icon for any non-widget item, or null when its app is gone. */
    fun resolve(item: Item): Pair<CharSequence, Drawable>? = when {
        item.folder -> item.label to (icons.custom(item.icon) ?: folderIcon(item.children))
        // The settings shortcut wears the app icon, so it takes the app's short name; "Launcher settings" didn't fit.
        item.isAction -> item.label.ifEmpty { if (item.action == "settings") getString(R.string.app_name) else actionName(item.action, repo::appLabel) } to
            (icons.custom(item.icon) ?: if (item.action == "settings") packageManager.getApplicationIcon(applicationInfo)
            else GlyphIcon(m3.primaryContainer, m3.onPrimaryContainer, symbol = when {
                item.action.startsWith("page:") -> "carousel"
                item.action.startsWith("app:") -> "apps"
                else -> ACTION_SYMBOLS[item.action] ?: "bolt"
            }))
        item.isShortcut -> repo.pinnedShortcut(item).let { s ->
            item.label.ifEmpty { s?.shortLabel?.toString() ?: "" } to (icons.custom(item.icon) ?: repo.shortcutFace(item, s) ?: packageManager.defaultActivityIcon)
        }
        item.action == "activity" -> runCatching { packageManager.getActivityInfo(ComponentName(item.pkg, item.cls), 0) }.getOrNull()?.let {
            item.label.ifEmpty { it.loadLabel(packageManager).toString() } to (icons.custom(item.icon) ?: it.loadIcon(packageManager))
        }
        else -> repo.apps[item.appKey]?.let { a -> item.label.ifEmpty { repo.label(a) } to (icons.custom(item.icon) ?: repo.icon(a)) }
    }

    fun itemView(item: Item, style: IconStyle): View? {
        if (item.isWidget) return if (item.children.isEmpty()) widgetFlow.widgetView(item) else widgetFlow.stackView(item)
        if (item.isWidgetSlot) return widgetFlow.widgetSlotView(item)
        if (item.isClock) return clockView()
        val (label, icon) = resolve(item) ?: return null
        val key = if (item.folder) item.children.joinToString("|") { repo.badgeKey(it.pkg, it.user) } else repo.badgeKey(item.pkg, item.user)
        return IconView(this, label, icon, style, badgeStyle(), key).apply {
            contentDescription = label
            setOnClickListener { open(item, it) }
            onSwipe = when {
                item.folder && folderSwipeOpens(item.swipe, looks.on("folderSwipe")) -> { { openFolder(item) } }
                // A folder's "none" only opts out of Swipe to open; like an app with no action, the swipe goes to home (#71).
                item.swipe.isNotEmpty() && item.swipe != "none" -> { { homeMenus.perform(item.swipe) } }
                else -> null
            }
            iconViews += this
        }
    }

    fun open(item: Item, v: View?) {
        when {
            item.folder -> if (folderSwipeOpens(item.swipe, looks.on("folderSwipe"))) item.children.firstOrNull()?.let { open(it, v) } else openFolder(item)
            item.isAction -> homeMenus.perform(item.action)
            item.isShortcut -> startShortcut(item.pkg, item.shortcutId, repo.userOf(item.user), v)
            item.action == "activity" -> try {
                startActivity(Intent().setComponent(ComponentName(item.pkg, item.cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), launchOptions(v))
            } catch (e: Exception) { toast(R.string.cant_open_activity) }
            else -> repo.apps[item.appKey]?.let { launch(it, v) } ?: toast(R.string.app_not_installed)
        }
    }

    /** Moves a home item to the first free spot on a page picked from a list: the TalkBack way to move it. */
    fun moveToPage(item: Item) {
        sheet().setTitle(R.string.move_to_page).setItems(Array(pages.size) { getString(R.string.page_n, it + 1) }) { _, p ->
            val spot = firstFreeSpot(items.filter { it != item && !isDock(it.page) }, looks.pages, looks.cols * sub, looks.rows * sub, item.w, item.h, p)
            if (spot == null) { toast(R.string.no_room); return@setItems }
            val i = items.indexOf(item)
            if (i < 0) return@setItems // gone while the list was open
            replace(i, item.copy(page = spot.page, x = spot.x, y = spot.y))
            workspace.snapTo(spot.page)
        }.show()
    }

    /** [pkgs]: redraw only icons badged by these packages; null redraws all. */
    fun updateBadges(pkgs: Set<String>? = null) {
        val on = looks.str("badges") != "off"
        pkgs?.let { badgeIcons.keys.removeAll(it) }
        for (v in iconViews) if (pkgs == null || v.badgeKey.split("|").any { it in pkgs }) badge(v.image, if (on) v.badgeKey else "")
    }

    /** Each package's dynamic badge icon, loaded once per change rather than on every bind. */
    private val badgeIcons = HashMap<String, Drawable?>()

    fun badge(image: IconImage, keys: String) {
        val list = if (keys.isEmpty()) emptyList() else keys.split("|").flatMap { BadgeService.byPackage[it].orEmpty() }
        image.badgeCount = list.size
        image.badgeIcon = if (looks.str("badges") == "dynamic") list.firstOrNull()?.let { n -> badgeIcons.getOrPut(n.packageName) { n.notification?.getLargeIcon()?.loadDrawable(this) } } else null
    }

    // ---- Drag and drop ----
    /** Takes a dragged item out of where it came from. False when it's no longer there: the drop must then do nothing. */
    fun detach(p: Any?): Boolean = when (p) {
        is Item -> items.remove(p)
        is FolderChild -> removeFromFolder(p)
        else -> true // from the drawer: a copy, with nothing to take out
    }

    /** Takes an app out of its drawer folder (from its menu: a drag only copies it to home); a folder left empty goes. */
    fun removeFromGroup(p: GroupChild) = saveGroups(drawer.groups().mapNotNull { g ->
        if (g.name != p.group) g else g.copy(apps = g.apps - p.app.key).takeIf { it.apps.isNotEmpty() }
    })

    fun removeItem(item: Item) {
        if (!items.remove(item)) return // a second tap on Remove while the menu fades
        save()
        showUndo(item) {
            item.widgetIds.forEach(host::deleteAppWidgetId)
            if (item.isShortcut) syncPins(item.pkg, item.user)
        }
    }

    fun uninstall(a: App) {
        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")).putExtra(Intent.EXTRA_USER, a.info.user))
    }

    /** Puts an item on the first free spot, starting at the current page. */
    fun addItem(item: Item, quiet: Boolean = false): Boolean {
        val home = items.filter { !isDock(it.page) }
        val spot = firstFreeSpot(home, looks.pages, looks.cols * sub, looks.rows * sub, item.w, item.h, workspace.current)
        if (spot == null) { if (!quiet) toast(R.string.no_room); return false }
        items += item.copy(page = spot.page, x = spot.x, y = spot.y)
        save()
        render()
        if (!quiet) toast(R.string.added_to_home)
        return true
    }

    // ponytail: a locked Private Space's icons vanish but keep their cells (like an app on an unmounted SD card).
    fun addToHome(a: App, quiet: Boolean = false) = addItem(Item(0, 0, 0, sub, sub, pkg = a.pkg, cls = a.cls, user = a.serial), quiet)

    @Suppress("DEPRECATION")
    fun applyBar() {
        if (barHidden) window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.requestApplyInsets()
    }

    private var wallpaperPages = -1

    private fun wallpaperScroll(pos: Float) {
        val n = looks.pages
        val token = window.decorView.windowToken ?: return
        val f = if (n > 1) (pos / (n - 1)).coerceIn(0f, 1f) else 0.5f
        val x = when (looks.str("wallpaperScroll")) { "off" -> 0.5f; "reverse" -> 1 - f; else -> f }
        val wm = WallpaperManager.getInstance(this)
        // As Launcher3: the steps change only with the page count, so they're set then rather than every scroll frame.
        if (n != wallpaperPages) { wm.setWallpaperOffsetSteps(if (n > 1) 1f / (n - 1) else 1f, 1f); wallpaperPages = n }
        wm.setWallpaperOffsets(token, x, 0.5f)
    }

    /**
     * Pins every shortcut the stored layout and actions use: a restored backup's, which this install never pinned (#103).
     * Android pins only for the default home app, so a restore made before Vulcan is one waits here for onResume.
     */
    fun repinShortcuts() {
        if (!runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false)) {
            store.data.edit().putBoolean(REPIN_SHORTCUTS, true).apply()
            return
        }
        store.data.edit().remove(REPIN_SHORTCUTS).apply()
        pinsByApp(store.load(looks.sub), settingActions()).forEach { (key, ids) ->
            um.getUserForSerialNumber(key.second)?.let { u -> runCatching { launcherApps.pinShortcuts(key.first, ids, u) } }
        }
    }

    // ---- Activity results ----

    @Deprecated("Plain Activity result API is fine without androidx")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (shortcutMade(requestCode, resultCode, data)) return
        val ok = resultCode == RESULT_OK
        when (requestCode) {
            REQ_BIND -> if (ok) widgetFlow.configureWidget() else widgetFlow.cancelWidget()
            REQ_CONFIG -> if (ok) widgetFlow.placeWidget() else if (widgetFlow.pendingSlot != null) widgetFlow.skipWidget() else widgetFlow.cancelWidget()
            REQ_SHORTCUT -> if (ok && data != null) {
                val req = launcherApps.getPinItemRequest(data)
                // ponytail: pre-Android 8 style EXTRA_SHORTCUT_INTENT results aren't stored; every current app uses pin requests
                // The user can change the default Home app while the external picker is open.
                val added = try {
                    req != null && addPinned(this, req)
                } catch (_: SecurityException) {
                    toast(R.string.shortcut_requires_home)
                    false
                }
                if (added) reloadItems()
            }
            REQ_DOC -> data?.data?.takeIf { ok }?.let { onDocument?.invoke(it) ?: restarted() }.also { onDocument = null }
            REQ_ICON -> data?.data?.takeIf { ok }?.let { uri ->
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                onPickedImage?.invoke(uri) ?: restarted()
                onPickedImage = null
            }
        }
    }
}

/** A readable name from an activity's class: "a.b.FaceEnrollConfirmationActivity" → "Face enroll confirmation"; acronyms stay. */
fun activityName(cls: String): String {
    val simple = cls.substringAfterLast('.').substringAfterLast('$').let { if (it.length > 8) it.removeSuffix("Activity") else it }
    val words = simple.split(Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")).filter { it.isNotEmpty() }
    return words.mapIndexed { i, w -> if (i == 0 || w.all { it.isUpperCase() || it.isDigit() }) w else w.lowercase() }.joinToString(" ")
}
