package dev.vulcanlauncher

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.app.WallpaperManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.hypot

/** A launchable app from any profile (personal or work). [serial] is the profile's UserManager serial number. */
class App(val info: LauncherActivityInfo, val serial: Long) {
    val pkg: String = info.componentName.packageName
    val cls: String = info.componentName.className
    val key = appKey(pkg, cls, serial)
    /** The app's own label. Cached: each LauncherActivityInfo.getLabel() loads the app's resources, and sorting asks often. */
    val title: String by lazy(LazyThreadSafetyMode.NONE) { info.label.toString() }
}

/** Drag payload for an item dragged out of a home-screen folder. */
class FolderChild(val folder: Item, val index: Int)

/** Drag payload for an app in the drawer folder named [group]: it reorders there; dropped on home, it's a copy. */
class GroupChild(val group: String, val app: App)

/** How far the page shrinks while dragging (Launcher3's spring-loaded state is about 0.8). */
const val SPRING_SCALE = 0.8f

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
        /** The live launcher activity, for the on-device check (androidTest/DeviceCheck). */
        var running: java.lang.ref.WeakReference<MainActivity>? = null
    }

    override val home get() = this
    /** A setting changed in SettingsActivity: home is built again before it shows (onStart). */
    var stale = false
    lateinit var icons: Icons
    lateinit var items: MutableList<Item>
    lateinit var host: AppWidgetHost
    lateinit var awm: AppWidgetManager
    lateinit var launcherApps: LauncherApps
    lateinit var um: UserManager
    /** Home pages, indicator and dock: blurred while a panel is open. */
    lateinit var content: LinearLayout
    lateinit var workspace: Workspace
    lateinit var pages: List<CellLayout>
    lateinit var dock: Workspace
    lateinit var dockPages: List<CellLayout>
    lateinit var dockBox: LinearLayout
    lateinit var indicator: Indicator
    lateinit var drawer: Drawer
    /** The date and status strip above the first page, when on (see Now.kt). */
    private lateinit var dropBar: LinearLayout
    /** The open folder window, desktop previews or resize frame, and what to do when it closes. */
    var overlay: View? = null
    var overlayClosed: () -> Unit = {}
    /** Plays the open overlay's exit animation, then calls its argument; null: the overlay just goes. */
    var overlayExit: ((done: () -> Unit) -> Unit)? = null
    val views = HashMap<Item, View>()
    val viewItems = HashMap<View, Item>()
    val folderFx = FolderFx(this)
    private val iconViews = ArrayList<IconView>()
    var apps = mapOf<String, App>()
    var customs = JSONObject()
    /** The widget being bound or configured. Saved, because the launcher can be killed while the other app's screen is open. */
    var pendingWidget: Pair<Int, AppWidgetProviderInfo>? = null
        set(v) { field = v; store.data.edit().putString("pendingWidget", v?.let { "${it.first} ${it.second.provider.flattenToString()}" } ?: "").apply() }
    var onPickedImage: ((Uri) -> Unit)? = null
    var onDocument: ((Uri) -> Unit)? = null
    private var lastEdgeFlip = 0L
    var dragSource: View? = null
        private set
    private var dragPayload: Any? = null
    private var dragStart: PointF? = null
    var dragMoved = false
        private set
    private var barHidden = false
    private val iconCache = HashMap<String, Drawable>()

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
        awm = AppWidgetManager.getInstance(this)
        host = AppWidgetHost(this, HOST_ID)
        // The application's LauncherApps, not this activity's: the system keeps its listener binder (and so its context)
        // alive after unregisterCallback, which leaked every recreated activity (~20 MB each).
        launcherApps = applicationContext.getSystemService(LauncherApps::class.java)
        um = getSystemService(UserManager::class.java)
        build()
        store.data.getString("pendingWidget", "")!!.split(" ").takeIf { it.size == 2 }?.let { (id, provider) ->
            val c = ComponentName.unflattenFromString(provider)
            awm.installedProviders.firstOrNull { it.provider == c }?.let { pendingWidget = id.toInt() to it }
        }
        // Widget IDs no item holds (the process died mid-flow, a pin failed, an undo bar was cut short) still get
        // updates from their providers: free them.
        val held = items.filter { it.isWidget }.map { it.widgetId } + listOfNotNull(pendingWidget?.first)
        host.appWidgetIds.filter { it !in held }.forEach(host::deleteAppWidgetId)
        launcherApps.registerCallback(appsCallback)
        if (android.os.Build.VERSION.SDK_INT >= 34) registerReceiver(profileReceiver,
            android.content.IntentFilter(Intent.ACTION_PROFILE_AVAILABLE).apply { addAction(Intent.ACTION_PROFILE_UNAVAILABLE) }, RECEIVER_NOT_EXPORTED)
        BadgeService.onChange = { pkgs -> runOnUiThread { if (pkgs.isNotEmpty()) { updateBadges(pkgs); drawer.refreshBadges() }; updateGlance() } }
        // Rotation recreates the activity: stay on the page the user was on.
        savedInstanceState?.getInt("page", -1)?.takeIf { it in 0 until looks.pages }?.let { workspace.snapTo(it, animate = false) }
    }


    /**
     * Applies changed settings by building home again in this window, as Launcher3 does for a grid change (onIdpChanged).
     * recreate() tears the window down, and on older Android versions the wallpaper or a blank frame showed in between.
     * Settings changed in SettingsActivity only mark home [stale]; it's built once, before it shows again.
     */
    fun rebuild() {
        val page = workspace.current
        depthAnim?.cancel()
        wiggles.forEach { it.cancel() }
        wiggles = emptyList()
        // Everything below belongs to the old views; none of it is shown again.
        overlay = null; overlayClosed = {}; menu = null; dragSource = null
        iconCache.clear(); badgeIcons.clear(); widgetFrames.clear()
        build()
        workspace.snapTo(page.coerceAtMost(looks.pages - 1), animate = false)
        if (dialogs.any { it.isShowing }) dialogScrim(true)
    }

    /** Everything the settings shape: palette, icons, layout and the views. At creation, and again in [rebuild]. */
    @Suppress("DEPRECATION")
    private fun build() {
        sub = looks.sub
        icons = Icons(this, looks)
        recolor()
        applyOrientation()
        loadApps()
        // Items of apps uninstalled while the launcher wasn't running (no callback came) would hold their cells unseen.
        items = fit(if (store.isNew) defaultLayout() else removeApps(store.load(sub), ::uninstalled))
        // Before 0.24 the clock was a strip fixed above the first page ("glance"); now it's an item on the first free row.
        if (looks.raw("glance") == "on") {
            looks["glance"] = "item"
            firstFreeSpot(items.filter { !isDock(it.page) }, looks.pages, looks.cols * sub, looks.rows * sub, looks.cols * sub, sub)
                ?.let { items += it.copy(action = "clock") }
        }
        save()

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

        val gesture = { g: String -> perform(looks.str("g_$g")) }
        pages = List(looks.pages) { i ->
            CellLayout(this, looks.cols * sub, looks.rows * sub, i).apply {
                setOnDragListener(dropListener(this))
                setOnLongClickListener { homeMenu(); true }
            }
        }
        indicator = Indicator(this, looks.pages, looks.str("indicator"), looks.color("labelColor"), m3.signal)
        workspace = Workspace(this).apply {
            pages.forEach { addView(it) }
            onGesture = gesture
            effect = looks.str("scrollEffect")
            infinite = looks.on("infiniteScroll")
            onScroll = { indicator.position = it; wallpaperScroll(it) }
            snapTo(looks.int("defaultPage").coerceAtMost(looks.pages - 1), animate = false)
        }
        dockPages = List(looks.dockPages) { CellLayout(this, looks.dockCols, 1, -1 - it, landscape).apply { setOnDragListener(dropListener(this)) } }
        dock = Workspace(this).apply { dockPages.forEach { addView(it) }; onGesture = gesture }
        val search = looks.str("searchBar")
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
        val dockHeight = ((looks.int("dockIconSize") + 28 + (if (looks.on("dockLabels")) 22 else 0)) * dp).toInt()
        val pagesBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (search == "top") addView(searchBar())
            // As an overlay, later children (indicator, dock) are pulled up over the bottom of the pages.
            val overlay = looks.on("dockOverlay") && looks.dockOn && !landscape
            addView(workspace, LinearLayout.LayoutParams(-1, 0, 1f).apply { if (overlay) bottomMargin = -dockHeight })
            if (looks.str("indicator") != "none" && looks.pages > 1) addView(indicator, LinearLayout.LayoutParams(-1, (14 * dp).toInt()))
            if (looks.on("swipeIndicator")) addView(TextView(context).apply {
                text = "︿"
                gravity = Gravity.CENTER
                setTextColor(looks.color("labelColor"))
                setOnClickListener { perform("drawer") }
            }, LinearLayout.LayoutParams(-1, (20 * dp).toInt()))
            if (landscape && search in listOf("dockAbove", "dockBelow")) addView(searchBar())
            if (!landscape) addView(dockBox, LinearLayout.LayoutParams(-1, -2).apply {
                if (floatingDock) setMargins((14 * dp).toInt(), (4 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
            })
            if (search == "bottom") addView(searchBar())
            // Spring-loaded, the page is a card: holding a drag beside it turns the page, as the edge of a full page does.
            setOnDragListener { v, e ->
                trackDrag(v, e)
                if (e.action == DragEvent.ACTION_DRAG_LOCATION && spring > 0f) {
                    val r = Rect().also(workspace::getHitRect)
                    if (e.y >= r.top && e.y <= r.bottom && (e.x < r.left || e.x > r.right))
                        edgeFlip(workspace, if (e.x < r.left) 0f else workspace.width.toFloat(), workspace.width)
                }
                e.action != DragEvent.ACTION_DROP
            }
        }
        content = if (!landscape) pagesBox else LinearLayout(this).apply {
            addView(pagesBox, LinearLayout.LayoutParams(0, -1, 1f))
            addView(dockBox, LinearLayout.LayoutParams(-2, -1).apply {
                if (floatingDock) setMargins((4 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt())
            })
        }
        drawer = Drawer(this)
        // Swipe up opens the drawer under the finger instead of after the gesture ends.
        if (looks.str("g_swipeUp") == "drawer") { workspace.onPull = drawer::pull; dock.onPull = drawer::pull }
        // Likewise swipe right on the first page pulls the Now page in under the finger.
        if (looks.str("g_swipeRight") == "now") workspace.onEdgePull = ::pullNow
        dropBar = LinearLayout(this).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            alpha = 0f
            val p = (12 * dp).toInt()
            setPadding(p, p, p, p)
            // As in Launcher3: Remove (Cancel from the drawer), and Uninstall for apps that can be. App info is in the menu.
            listOf("remove", "uninstall").forEach {
                addView(dropTarget(it), LinearLayout.LayoutParams(-2, (48 * dp).toInt()).apply { setMargins(p / 2, 0, p / 2, 0) })
            }
        }
        root = FrameLayout(this).apply {
            addView(content)
            addView(drawer.root)
            addView(dropBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            setOnApplyWindowInsetsListener { _, ins ->
                // LAYOUT_STABLE keeps reporting the status bar while it's hidden: only stay clear of a notch then.
                val top = if (!barHidden) ins.systemWindowInsetTop
                    else if (android.os.Build.VERSION.SDK_INT >= 28) ins.displayCutout?.safeInsetTop ?: 0 else 0
                val behind = looks.on("dockBehindNav") && looks.dockOn && !floatingDock && !landscape
                content.setPadding(ins.systemWindowInsetLeft, top, ins.systemWindowInsetRight,
                    if (behind) 0 else ins.systemWindowInsetBottom)
                dockBox.setPadding(0, 0, 0, if (behind) ins.systemWindowInsetBottom else 0)
                drawer.applyInsets(ins.systemWindowInsetLeft, top, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
                dropBar.setPadding(dropBar.paddingLeft, top + dropBar.paddingBottom, dropBar.paddingRight, dropBar.paddingBottom)
                insets.set(ins.systemWindowInsetLeft, top, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
                ins
            }
        }
        setContentView(root)
        render()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
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
        if (android.os.Build.VERSION.SDK_INT >= 34) unregisterReceiver(profileReceiver)
        BadgeService.onChange = {}
        torchWatch?.let { getSystemService(android.hardware.camera2.CameraManager::class.java).unregisterTorchCallback(it) }
    }

    override fun onStart() {
        super.onStart()
        if (stale) { stale = false; rebuild() }
        host.startListening()
    }

    override fun onStop() {
        super.onStop()
        host.stopListening()
        // Daily automatic backup, kept privately on the device and offered in Backup & restore.
        // Once a day (not on every stop) so a reset or bad change can't overwrite the last good copy straight away.
        val auto = store.autoBackups[0]
        if (!auto.exists() || System.currentTimeMillis() - auto.lastModified() > 24 * 3600_000L) runCatching { store.rotateAutoBackup() }
    }

    override fun onResume() {
        super.onResume()
        // A copy opened from another launcher before Vulcan became the default one.
        if (handOffToHome()) return
        updateLocation()
        if (nightChanged()) rebuild()
        setClockHidden(true)
        updateGlance()
        // PinActivity may have added an item while we were in the background.
        reloadItems()
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
        when {
            menu != null -> closeMenu()
            overlay != null -> closeOverlay()
            drawer.visible -> drawer.hide()
            onHome -> {
                val action = looks.str("homeButton")
                val main = looks.int("defaultPage").coerceAtMost(looks.pages - 1)
                if (looks.on("homeFirst") && workspace.current != main) workspace.snapTo(main) else perform(action)
            }
        }
    }

    @Deprecated("Launchers swallow back")
    override fun onBackPressed() {
        when {
            menu != null -> closeMenu()
            overlay != null -> backInOverlay()
            else -> drawer.hide()
        }
    }

    fun fit(list: List<Item>) = fitItems(list, looks.pages, looks.cols * sub, looks.rows * sub, looks.dockCols, looks.dockPages).toMutableList()

    fun save() = store.save(items, sub)

    // ---- Apps ----

    fun serial(user: UserHandle) = um.getSerialNumberForUser(user)
    fun userOf(serial: Long): UserHandle = um.getUserForSerialNumber(serial) ?: Process.myUserHandle()

    /** The Private Space profile (Android 15+), or null. Android lists it only to the home app holding ACCESS_HIDDEN_PROFILES. */
    var privateUser: UserHandle? = null
        private set
    val privateLocked get() = privateUser?.let { um.isQuietModeEnabled(it) } ?: false

    /** [changed]: the only packages whose icons may have changed; null drops every cached icon. */
    fun loadApps(changed: Collection<String>? = null) {
        val profiles = launcherApps.profiles
        privateUser = if (android.os.Build.VERSION.SDK_INT >= 35)
            profiles.firstOrNull { launcherApps.getLauncherUserInfo(it)?.userType == UserManager.USER_TYPE_PROFILE_PRIVATE } else null
        // A locked Private Space shows none of its apps, anywhere.
        apps = profiles.filter { it != privateUser || !privateLocked }.flatMap { user -> launcherApps.getActivityList(null, user).map { App(it, serial(user)) } }
            .filter { it.pkg != packageName }.associateBy { it.key }
        customs = store.obj("custom")
        if (changed == null) iconCache.clear() else iconCache.keys.removeAll { it.substringBefore('/') in changed }
    }

    /**
     * Whether [item]'s package is gone for good: Android says it isn't installed for that profile, or the profile was
     * removed. Anything uncertain (on an unmounted SD card, mid-update, disabled, a paused or locked profile) counts as
     * there, so its icon comes back when the app does.
     */
    private fun uninstalled(item: Item): Boolean {
        if (apps.values.any { it.pkg == item.pkg && it.serial == item.user }) return false
        val user = um.getUserForSerialNumber(item.user) ?: return true
        return try {
            val info = launcherApps.getApplicationInfo(item.pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES, user)
            info != null && info.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED == 0
        } catch (e: PackageManager.NameNotFoundException) { true } catch (e: Exception) { false }
    }

    /** First run: the default phone, messages, browser, camera and gallery apps in the dock (as many as fit). */
    /** A fresh install's layout, as Nova sets it up: a five-app dock, mail and photos above it, settings top right. */
    fun defaultLayout(): MutableList<Item> {
        val me = serial(Process.myUserHandle())
        fun role(category: String) = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category)
        fun apps(vararg intents: Intent) = intents.mapNotNull { intent ->
            // The user's default first; with no default set, resolveActivity returns the chooser, so fall back to any handler.
            val handlers = listOfNotNull(packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)) + packageManager.queryIntentActivities(intent, 0)
            handlers.firstNotNullOfOrNull { r -> apps.values.firstOrNull { it.pkg == r.activityInfo.packageName && it.serial == me } }
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
            if (looks.on("autoAdd")) launcherApps.getActivityList(pkg, user).forEach { addToHome(App(it, serial(user)), quiet = true) }
        }
        // Uninstalled (an update is onPackageChanged): its icons go too, or they'd hold their cells invisibly. The
        // system has already deleted its widgets.
        override fun onPackageRemoved(pkg: String, user: UserHandle) {
            reloadItems()
            items = removePackage(items, pkg, serial(user)).filter { !it.isWidget || awm.getAppWidgetInfo(it.widgetId) != null }.toMutableList()
            save()
            reload(listOf(pkg))
        }
        // An update or a component switched on or off: home redraws only if it shows one of the package's apps.
        override fun onPackageChanged(pkg: String, user: UserHandle) = reload(listOf(pkg), homeOnly = true)
        override fun onPackagesAvailable(pkgs: Array<out String>, user: UserHandle, replacing: Boolean) = reload(pkgs.toList(), homeOnly = true)
        override fun onPackagesUnavailable(pkgs: Array<out String>, user: UserHandle, replacing: Boolean) = reload(pkgs.toList(), homeOnly = true)
        // Apps republish their shortcuts often (messaging apps on every message): redraw only for one pinned on home.
        override fun onShortcutsChanged(pkg: String, shortcuts: MutableList<ShortcutInfo>, user: UserHandle) {
            val s = serial(user)
            if ((items + items.flatMap { it.children }).any { it.isShortcut && it.pkg == pkg && it.user == s }) render()
        }
        private fun reload(pkgs: List<String>, homeOnly: Boolean = false) {
            loadApps(pkgs)
            drawer.invalidate()
            if (!homeOnly || (items + items.flatMap { it.children }).any { it.pkg in pkgs }) render()
        }
    }

    fun label(a: App): String = customs.optJSONObject(a.key)?.optString("label")?.takeIf { it.isNotEmpty() } ?: a.title
    fun icon(a: App): Drawable = iconCache.getOrPut(a.key) {
        icons.get(a.pkg, a.cls, customs.optJSONObject(a.key)?.optString("icon") ?: "") { a.info.getBadgedIcon(0) }
    }
    /** Main activity of [pkg] in profile [user], for apps a backup names by package only. */
    fun mainActivity(pkg: String, user: Long): String? {
        val mine = apps.values.filter { it.pkg == pkg && it.serial == user }
        // A package can have several launcher activities (Google: search and voice search); take the one Android launches.
        val launch = packageManager.getLaunchIntentForPackage(pkg)?.component?.className
        return (mine.firstOrNull { it.cls == launch } ?: mine.firstOrNull())?.cls
    }

    fun appLabel(key: String) = apps[key]?.let(::label)
    fun isWork(a: App) = a.info.user != Process.myUserHandle() && !isPrivate(a)
    fun isPrivate(a: App) = a.info.user == privateUser

    /** Locks or unlocks Private Space; unlocking asks for the screen lock first. The profile broadcast then reloads the apps. */
    fun setPrivateLocked(lock: Boolean) { privateUser?.let { runCatching { um.requestQuietModeEnabled(lock, it) } } }

    /** Private Space (or a work profile) locked, unlocked, paused or resumed. */
    private val profileReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) { loadApps(); drawer.invalidate(); render() }
    }

    fun launch(a: App, v: View?) {
        runCatching { launcherApps.startMainActivity(a.info.componentName, a.info.user, bounds(v), launchOptions(v)) }
            .onFailure { toast(R.string.cant_open, label(a)) }
        val counts = store.obj("launches")
        store.putObj("launches", counts.put(a.key, counts.optInt(a.key) + 1))
        if (drawer.visible && looks.on("closeAfterLaunch")) drawer.hide()
    }

    fun launches() = store.obj("launches")

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

    fun shortcutsFor(a: App): List<ShortcutInfo> = runCatching {
        launcherApps.getShortcuts(LauncherApps.ShortcutQuery().setPackage(a.pkg).setActivity(a.info.componentName)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST), a.info.user)
            .orEmpty().sortedBy { it.rank }.take(4) // Launcher3's MAX_SHORTCUTS
    }.getOrDefault(emptyList())

    private fun pinnedShortcut(item: Item): ShortcutInfo? = runCatching {
        launcherApps.getShortcuts(LauncherApps.ShortcutQuery().setPackage(item.pkg).setShortcutIds(listOf(item.shortcutId))
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED), userOf(item.user))?.firstOrNull()
    }.getOrNull()

    fun shortcutIcon(s: ShortcutInfo): Drawable =
        runCatching { launcherApps.getShortcutIconDrawable(s, resources.displayMetrics.densityDpi) }.getOrNull() ?: packageManager.defaultActivityIcon

    fun startShortcut(pkg: String, id: String, user: UserHandle, v: View?) {
        runCatching { launcherApps.startShortcut(pkg, id, bounds(v), launchOptions(v), user) }
            .onFailure { toast(R.string.shortcut_unavailable) }
    }

    /** Pins exactly the shortcuts of [pkg] that are on the home screen, so removed ones are released. */
    fun syncPins(pkg: String, user: Long) {
        val ids = (items + items.flatMap { it.children }).filter { it.isShortcut && it.pkg == pkg && it.user == user }.map { it.shortcutId }.distinct()
        runCatching { launcherApps.pinShortcuts(pkg, ids, userOf(user)) }
    }

    // ---- Home screen ----

    fun style(surface: String): IconStyle {
        val size = looks.int(if (surface == "") "iconSize" else "${surface}IconSize")
        val labels = looks.on(if (surface == "") "labels" else "${surface}Labels")
        return IconStyle((size * dp).toInt(), labels, looks.labelSize("${surface}LabelSize"), looks.color("labelColor"),
            looks.on("labelShadow"), looks.on("iconShadow"), font = looks.str("labelFont"), lines = looks.int("labelLines"))
    }

    fun badgeStyle() = BadgeStyle(looks.str("badges"), looks.str("badgePos"),
        when (looks.str("badgeSize")) { "small" -> 0.8f; "large" -> 1.3f; else -> 1f }, looks.color("badgeColor"))

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
            v.setOnLongClickListener { startDrag(it, item); true }
            accessibleActions(v, { showPopup(v, item) }, if (locked || isDock(item.page)) null else { { moveToPage(item) } })
            cell.addView(v)
            views[item] = v
            viewItems[v] = item
        }
        widgetFrames.keys.retainAll(items.filter { it.isWidget }.map { it.widgetId }.toSet())
        updateBadges()
        updateGlance()
        folderFx.settle()
    }

    /** Label and icon for any non-widget item, or null when its app is gone. */
    fun resolve(item: Item): Pair<CharSequence, Drawable>? = when {
        item.folder -> item.label to folderIcon(item.children)
        item.isAction -> item.label.ifEmpty { actionName(item.action, ::appLabel) } to
            (icons.custom(item.icon) ?: if (item.action == "settings") packageManager.getApplicationIcon(applicationInfo)
            else GlyphIcon(m3.primaryContainer, m3.onPrimaryContainer, symbol = when {
                item.action.startsWith("page:") -> "carousel"
                item.action.startsWith("app:") -> "apps"
                else -> ACTION_SYMBOLS[item.action] ?: "bolt"
            }))
        item.isShortcut -> pinnedShortcut(item).let { s ->
            item.label.ifEmpty { s?.shortLabel?.toString() ?: "" } to (icons.custom(item.icon) ?: s?.let(::shortcutIcon) ?: packageManager.defaultActivityIcon)
        }
        item.action == "activity" -> runCatching { packageManager.getActivityInfo(ComponentName(item.pkg, item.cls), 0) }.getOrNull()?.let {
            item.label.ifEmpty { it.loadLabel(packageManager).toString() } to (icons.custom(item.icon) ?: it.loadIcon(packageManager))
        }
        else -> apps[item.appKey]?.let { a -> item.label.ifEmpty { label(a) } to (icons.custom(item.icon) ?: icon(a)) }
    }

    fun itemView(item: Item, style: IconStyle): View? {
        if (item.isWidget) return widgetView(item)
        if (item.isClock) return clockView()
        val (label, icon) = resolve(item) ?: return null
        val shown = if (item.folder && !looks.on("folderNames")) "" else label
        val key = if (item.folder) item.children.joinToString("|") { it.pkg } else item.pkg
        return IconView(this, shown, icon, style, badgeStyle(), key).apply {
            contentDescription = label
            setOnClickListener { open(item, it) }
            onSwipe = when {
                item.folder && looks.on("folderSwipe") -> { { openFolder(item) } }
                item.swipe.isNotEmpty() -> { { perform(item.swipe) } }
                else -> null
            }
            iconViews += this
        }
    }

    fun open(item: Item, v: View?) {
        when {
            item.folder -> if (looks.on("folderSwipe")) item.children.firstOrNull()?.let { open(it, v) } else openFolder(item)
            item.isAction -> perform(item.action)
            item.isShortcut -> startShortcut(item.pkg, item.shortcutId, userOf(item.user), v)
            item.action == "activity" -> try {
                startActivity(Intent().setComponent(ComponentName(item.pkg, item.cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), launchOptions(v))
            } catch (e: Exception) { toast(R.string.cant_open_activity) }
            else -> apps[item.appKey]?.let { launch(it, v) } ?: toast(R.string.app_not_installed)
        }
    }

    /** Kept across render(), so a widget isn't inflated again (and reset) whenever any item changes. */
    private val widgetFrames = HashMap<Int, WidgetFrame>()

    @Suppress("DEPRECATION")
    private fun widgetView(item: Item): View? {
        val info = awm.getAppWidgetInfo(item.widgetId) ?: return null
        widgetFrames[item.widgetId]?.let { return it }
        val hv: AppWidgetHostView = host.createView(applicationContext, item.widgetId, info)
        hv.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) {
                val w = ((r - l) / dp).toInt()
                val h = ((b - t) / dp).toInt()
                (v as AppWidgetHostView).updateAppWidgetSize(null, w, h, w, h)
            }
        }
        val radius = looks.int("widgetRadius") * dp
        return WidgetFrame(this).apply {
            addView(hv)
            if (radius > 0) {
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, radius)
                }
                clipToOutline = true
            }
        }.also { widgetFrames[item.widgetId] = it }
    }

    /** Moves a home item to the first free spot on a page picked from a list: the TalkBack way to move it. */
    fun moveToPage(item: Item) {
        dialog().setTitle(R.string.move_to_page).setItems(Array(pages.size) { getString(R.string.page_n, it + 1) }) { _, p ->
            val spot = firstFreeSpot(items.filter { it != item && !isDock(it.page) }, looks.pages, looks.cols * sub, looks.rows * sub, item.w, item.h, p)
            if (spot == null) { toast(R.string.no_room); return@setItems }
            items[items.indexOf(item)] = item.copy(page = spot.page, x = spot.x, y = spot.y)
            save()
            render()
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

    fun startDrag(v: View, payload: Any) {
        if (locked) { showPopup(v, payload); return }
        dragSource = v
        dragPayload = payload
        dragStart = null
        dragMoved = false
        (dropBar.getChildAt(0) as TextView).text = getString(if (payload is Item || payload is FolderChild) R.string.remove else R.string.cancel)
        val system = appOf(payload)?.info?.applicationInfo?.let { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 }
        dropBar.getChildAt(1).visibility = if (system == false) View.VISIBLE else View.GONE
        // As Launcher3: the lifted icon stays where it was under the finger (the default shadow centres on it), and
        // its cell is left empty.
        val at = IntArray(2).also { v.getLocationInWindow(it) }
        val touch = Point((lastDown.x - at[0]).toInt().coerceIn(0, v.width), (lastDown.y - at[1]).toInt().coerceIn(0, v.height))
        v.startDragAndDrop(ClipData.newPlainText("", ""), object : View.DragShadowBuilder(v) {
            override fun onProvideShadowMetrics(size: Point, touchPoint: Point) { super.onProvideShadowMetrics(size, touchPoint); touchPoint.set(touch.x, touch.y) }
        }, payload, View.DRAG_FLAG_OPAQUE)
        if (payload is Item) v.alpha = 0f
        // The menu shows the moment the long-press registers, as on the wallpaper; moving the drag closes it (trackDrag).
        // Not for a drag out of a menu row (a shortcut): that menu stays until the drag moves.
        if (menu == null) root.post { if (dragSource === v && !dragMoved) showPopup(v, payload) }
        if (looks.on("wiggle")) wiggles = iconViews.map { iv ->
            android.animation.ObjectAnimator.ofFloat(iv.image, "rotation", -4f, 4f).apply {
                duration = 140
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE
                start()
            }
        }
    }

    private var wiggles = listOf<android.animation.Animator>()

    /**
     * A drag that never moves is a long-press: its menu stays. [keepOverlay]: a folder window reordering its icons, which
     * keeps it and the drawer behind it until the drag leaves the folder.
     */
    fun trackDrag(v: View, e: DragEvent, keepOverlay: Boolean = false) {
        if (e.action != DragEvent.ACTION_DRAG_LOCATION || dragMoved) return
        val loc = IntArray(2).also { v.getLocationOnScreen(it) }
        val x = loc[0] + e.x
        val y = loc[1] + e.y
        val s = dragStart ?: PointF(x, y).also { dragStart = it }
        if (hypot(x - s.x, y - s.y) > 16 * dp) {
            dragMoved = true
            // A drag that never moves stays a long-press: no drop targets until it does. A drawer folder's app has none:
            // it only reorders or copies to home.
            if (dragPayload !is GroupChild) dropBar.visibility = View.VISIBLE
            springLoad(true)
            root.tick()
            closeMenu()
            if (!keepOverlay) { drawer.hide(animate = false); closeOverlay() }
        }
    }

    /** For views that cover home (drawer, folder window): follow the drag but never accept the drop. */
    val passDrag = View.OnDragListener { v, e -> trackDrag(v, e); e.action != DragEvent.ACTION_DROP }

    private fun dropListener(cell: CellLayout) = View.OnDragListener { v, e ->
        trackDrag(v, e)
        when (e.action) {
            DragEvent.ACTION_DRAG_LOCATION -> {
                edgeFlip(if (isDock(cell.page)) dock else workspace, e.x, cell.width)
                val merge = mergeTarget(cell, e.x, e.y, e.localState)
                folderFx.hover(cell, merge)
                cell.landing = if (merge != null || !dragMoved) null else landing(cell, e.x, e.y, e.localState)
            }
            DragEvent.ACTION_DRAG_EXITED -> { folderFx.hover(cell, null); cell.landing = null }
            DragEvent.ACTION_DROP -> { folderFx.clearHover(); return@OnDragListener drop(cell, e.x, e.y, e.localState) }
            // An accepted drop's ENDED goes to the accepting view only, so every cell must clean up too.
            DragEvent.ACTION_DRAG_ENDED -> dragEnded()
        }
        true
    }

    /** Drop target shown at the top while dragging, as in Launcher3: an outline in the label colour, filled while hovered. */
    private fun dropTarget(kind: String) = text(getString(if (kind == "uninstall") R.string.uninstall else R.string.remove), 14f, medium = true).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        val p = (16 * dp).toInt()
        setPadding(p, 0, p * 5 / 4, 0)
        compoundDrawablePadding = p / 2
        fun look(hover: Boolean) {
            val line = looks.color("labelColor")
            val fg = if (hover) m3.onPrimaryContainer else line
            background = if (hover) rounded(m3.primaryContainer, 24 * dp) else rounded(0, 24 * dp, line, 1.5f * dp)
            setTextColor(fg)
            setCompoundDrawablesRelative(symbol(if (kind == "uninstall") "delete" else "close", fg, 20f).apply {
                setBounds(0, 0, intrinsicWidth, intrinsicHeight)
            }, null, null, null)
        }
        look(false)
        setOnDragListener { _, e ->
            val p = e.localState
            when (e.action) {
                DragEvent.ACTION_DRAG_ENTERED -> { look(true); performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
                DragEvent.ACTION_DRAG_EXITED -> look(false)
                DragEvent.ACTION_DROP -> when (kind) {
                    "remove" -> when (p) {
                        is Item -> removeItem(p)
                        is FolderChild -> { removeFromFolder(p); save() }
                    }
                    "uninstall" -> appOf(p)?.let { uninstall(it) }
                }
                DragEvent.ACTION_DRAG_ENDED -> { look(false); dragEnded() }
            }
            true
        }
    }

    private var spring = 0f
    private var springAnim: ValueAnimator? = null
    private var springCard: View? = null

    /**
     * Launcher3's spring-loaded home while dragging: the page and its clock shrink into an outlined card, which leaves
     * the top strip to the drop targets; the dock stays. [on] false grows it back.
     */
    // ponytail: one card for the current page; Launcher3 gives every page its own and lets the next one peek in.
    private fun springLoad(on: Boolean) {
        // Launcher3's workspace accent: its light tone over a dark wallpaper (light labels), its dark tone otherwise.
        // The Appliance look keeps to its legends' colour.
        val label = looks.color("labelColor")
        val accent = if (m3.housing) label else m3.ramps[0](if (luminance(label) > 0.5) 90 else 10)
        (pages + dockPages).forEach { it.outlineColor = accent; if (!on) it.landing = null }
        val block = listOf(workspace)
        val card = springCard ?: View(this).also { springCard = it; root.addView(it, 0) }
        if (on && spring == 0f) {
            fun rootX(v: View): Int { var x = 0; var p = v; while (p !== root) { x += p.left; p = p.parent as View }; return x }
            fun rootY(v: View): Int { var y = 0; var p = v; while (p !== root) { y += p.top; p = p.parent as View }; return y }
            val left = rootX(workspace)
            val top = rootY(block.first())
            val bottom = rootY(workspace) + workspace.height
            // The card's top lands just under the drop targets; it scales about the point that puts it there.
            val py = (insets.top + 76 * dp - SPRING_SCALE * top) / (1 - SPRING_SCALE)
            val px = left + workspace.width / 2f
            card.layoutParams = FrameLayout.LayoutParams(workspace.width, bottom - top, Gravity.TOP or Gravity.LEFT).apply { leftMargin = left; topMargin = top }
            card.background = rounded(label and 0xFFFFFF or 0x1F000000, 28 * dp, accent and 0xFFFFFF or 0x99000000.toInt(), 2 * dp)
            for (v in block) { v.pivotX = px - rootX(v); v.pivotY = py - rootY(v) }
            card.pivotX = px - left; card.pivotY = py - top
        }
        springAnim?.cancel()
        springAnim = ValueAnimator.ofFloat(spring, if (on) 1f else 0f).apply {
            duration = 300
            interpolator = EASE
            addUpdateListener {
                spring = it.animatedValue as Float
                val s = 1 - (1 - SPRING_SCALE) * spring
                for (v in block + card) { v.scaleX = s; v.scaleY = s }
                card.alpha = spring
                dropBar.alpha = spring
            }
            if (!on) addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { if (spring == 0f) dropBar.visibility = View.GONE }
            })
            start()
        }
    }

    fun dragEnded() {
        folderFx.clearHover()
        val src = dragSource ?: return // ENDED can fan out to several listeners; first one wins
        dragSource = null
        wiggles.forEach { it.cancel() }
        iconViews.forEach { it.image.rotation = 0f }
        wiggles = emptyList()
        springLoad(false)
        src.alpha = 1f
        val moved = dragMoved
        val payload = dragPayload
        // Posted: adding or removing views while ENDED is still being dispatched throws
        // ConcurrentModificationException in ViewGroup.dispatchDragEvent.
        // A drag that never moved already shows its menu (startDrag); it stays open.
        root.post { if (!moved && src.isAttachedToWindow) { if (menu == null) showPopup(src, payload) } else render() }
    }

    /** Holding a drag near the left/right edge flips to the neighbouring page. */
    private fun edgeFlip(ws: Workspace, x: Float, width: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastEdgeFlip < 700 || ws.childCount < 2) return
        val edge = width * 0.06f
        if (x < edge) ws.snapTo(ws.current - 1)
        else if (x > width - edge) ws.snapTo(ws.current + 1)
        else return
        lastEdgeFlip = now
    }

    fun appOf(p: Any?): App? = when (p) {
        is App -> p
        is GroupChild -> p.app
        is Item -> if (p.isApp) apps[p.appKey] else null
        is FolderChild -> p.folder.children.getOrNull(p.index)?.let { if (it.isApp) apps[it.appKey] else null }
        else -> null
    }

    /** The payload as a 1×1 item that can live in a folder, or null (widgets, folders). */
    fun asChild(p: Any?): Item? = when (p) {
        is Item -> if (p.isBlock || p.folder) null else p.copy(page = 0, x = 0, y = 0, w = 1, h = 1)
        is App -> Item(0, 0, 0, pkg = p.pkg, cls = p.cls, user = p.serial)
        is GroupChild -> asChild(p.app)
        is FolderChild -> p.folder.children.getOrNull(p.index)
        is ShortcutInfo -> Item(0, 0, 0, pkg = p.`package`, shortcutId = p.id, user = serial(p.userHandle), label = p.shortLabel?.toString() ?: "")
        else -> null
    }

    /** Where dropping [payload] at ([px], [py]) would place it (grid units), or null when it can't go there. */
    private fun landing(cell: CellLayout, px: Float, py: Float, payload: Any?): Rect? {
        val moving = payload as? Item
        if (moving == null && asChild(payload) == null) return null
        if (isDock(cell.page) && moving?.isBlock == true) return null
        val unit = if (isDock(cell.page)) 1 else sub
        val w = if (moving?.isBlock == true) moving.w else unit
        val h = if (moving?.isBlock == true) moving.h else unit
        val (gx, gy) = cell.toGrid(px, py)
        val x = (gx - w / 2f + 0.5f).toInt().coerceIn(0, maxOf(0, cell.cols - w))
        val y = (gy - h / 2f + 0.5f).toInt().coerceIn(0, maxOf(0, cell.rows - h))
        val overlap = looks.on("widgetOverlap") && moving?.isBlock == true
        if (!overlap && !cell.isFree(x, y, w, h, ignore = moving?.let { views[it] })) return null
        return Rect(x, y, x + w, y + h)
    }

    private fun drop(cell: CellLayout, px: Float, py: Float, payload: Any?): Boolean {
        if (!dragMoved) return false
        val child = asChild(payload)
        val target = mergeTarget(cell, px, py, payload)
        if (child != null && target != null) { mergeIntoFolder(target, child, payload); return true }
        val spot = landing(cell, px, py, payload) ?: return false
        val placed = ((payload as? Item) ?: child ?: return false).copy(page = cell.page, x = spot.left, y = spot.top, w = spot.width(), h = spot.height())
        detach(payload)
        items += placed
        root.tick()
        if (placed.isShortcut) syncPins(placed.pkg, placed.user)
        save()
        render()
        return true
    }

    fun detach(p: Any?) {
        when (p) {
            is Item -> items.remove(p)
            is FolderChild -> removeFromFolder(p)
        }
    }

    /** Takes an app out of its drawer folder (from its menu: a drag only copies it to home); a folder left empty goes. */
    fun removeFromGroup(p: GroupChild) = saveGroups(drawer.groups().mapNotNull { g ->
        if (g.name != p.group) g else g.copy(apps = g.apps - p.app.key).takeIf { it.apps.isNotEmpty() }
    })

    fun removeItem(item: Item) {
        items.remove(item)
        save()
        showUndo(item) {
            if (item.isWidget) host.deleteAppWidgetId(item.widgetId)
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

    // ---- Long-press menu ----

    fun showPopup(anchor: View, payload: Any?) {
        val rows = ArrayList<View>()
        val tiles = ArrayList<Triple<String, String, () -> Unit>>()
        fun row(icon: Drawable, text: String, summary: String? = null, long: ((View) -> Unit)? = null, click: () -> Unit) {
            rows += listItem(icon, text, summary, dense = true, summaryLines = 2) { closeMenu(); click() }.apply {
                // A drag that starts here closes the menu once it moves (see trackDrag).
                if (long != null) setOnLongClickListener { v -> long(v); if (dragSource !== v) closeMenu(); true }
            }
        }
        fun tile(sym: String, title: String, click: () -> Unit) { tiles += Triple(sym, title, click) }
        val item = payload as? Item
        val app = appOf(payload)
        if (app != null && looks.on("popupShortcuts")) shortcutsFor(app).forEach { s ->
            row(shortcutIcon(s), s.shortLabel?.toString() ?: "", long = { v -> if (!locked) startDrag(v, s) }) {
                startShortcut(s.`package`, s.id, s.userHandle, anchor)
            }
        }
        if (app != null && looks.on("popupNotifications")) // The newest only, as Launcher3 shows one notification at a time; long texts (promo SMS) filled the screen.
        BadgeService.byPackage[app.pkg].orEmpty().sortedByDescending { it.postTime }.take(1).forEach { n ->
            val title = n.notification.extras.getCharSequence("android.title") ?: ""
            val body = n.notification.extras.getCharSequence("android.text") ?: ""
            row(symbol("notifications"), title.toString(), body.toString(), long = { BadgeService.instance?.cancelNotification(n.key) }) {
                runCatching { n.notification.contentIntent?.send() }
            }
        }
        when {
            app != null && (payload is App || payload is GroupChild) -> {
                if (!locked) tile("add", getString(R.string.menu_add)) { addToHome(app) }
                tile("edit", getString(R.string.edit)) { editApp(app) }
                val hidden = app.key in store.strings("hidden")
                tile(if (hidden) "show" else "hide", getString(if (hidden) R.string.unhide else R.string.hide)) { toggleHidden(app.key) }
                tile("tabs", getString(R.string.menu_group)) { addToGroup(app.key) }
                if (payload is GroupChild) tile("close", getString(R.string.remove)) { removeFromGroup(payload) }
            }
            item != null && !locked -> {
                if (!item.isBlock) tile("edit", getString(R.string.edit)) { editItem(item) }
                if (item.isBlock) tile("resize", getString(R.string.resize)) { resizeWidget(item) }
                if (item.isClock) {
                    val big = views[item]?.tag == true
                    tile("clock", getString(if (big) R.string.glanceStyle_date else R.string.glanceStyle_clock)) {
                        looks["glanceStyle"] = if (big) "date" else "clock"; rebuild()
                    }
                }
                if (item.isWidget && awm.getAppWidgetInfo(item.widgetId)?.configure != null) tile("settings", getString(R.string.settings)) {
                    runCatching { host.startAppWidgetConfigureActivityForResult(this, item.widgetId, 0, REQ_RECONFIG, null) }
                }
                tile("close", getString(R.string.remove)) { removeItem(item); render() }
            }
            payload is FolderChild && !locked -> tile("close", getString(R.string.remove)) { removeFromFolder(payload); save(); render() }
        }
        if (app != null) {
            tile("info", getString(R.string.app_info)) { launcherApps.startAppDetailsActivity(app.info.componentName, app.info.user, null, null) }
            if (!isSystemApp(app)) tile("delete", getString(R.string.uninstall)) { uninstall(app) }
            if (awm.installedProviders.any { it.provider.packageName == app.pkg }) tile("widgets", getString(R.string.widgets)) { pickWidget(app.pkg) }
            tile("download", getString(R.string.save_apk)) { saveApk(app) }
            if (looks.on("closeApp")) tile("stop", getString(R.string.close_app)) {
                getSystemService(android.app.ActivityManager::class.java).killBackgroundProcesses(app.pkg)
                toast(R.string.closed_tasks, label(app))
            }
            tile("image", getString(R.string.icon_png)) { exportIcon(app) }
        }
        if (rows.isEmpty() && tiles.isEmpty()) return
        showMenu(screenRect(anchor)) {
            rows.forEach { addView(it) }
            if (rows.isNotEmpty() && tiles.isNotEmpty()) addView(hairline())
            addTiles(tiles, 4)
        }
    }

    /** The open long-press or home menu, an overlay inside this window. */
    var menu: View? = null
        private set

    fun closeMenu() {
        val m = menu ?: return
        menu = null
        // Let touches through at once; it only fades out.
        m.setOnClickListener(null); m.isClickable = false
        m.animate().alpha(0f).setDuration(120).withEndAction { root.removeView(m) }.start()
    }

    /** [v]'s bounds in this window. */
    fun screenRect(v: View): Rect {
        val a = IntArray(2).also { v.getLocationInWindow(it) }
        val r = IntArray(2).also { root.getLocationInWindow(it) }
        return Rect(a[0] - r[0], a[1] - r[1], a[0] - r[0] + v.width, a[1] - r[1] + v.height)
    }

    /**
     * A glass popover menu next to [anchor] (window coordinates): below it if there's room, else above. It grows out
     * of the anchor. Drawn inside this window rather than as a PopupWindow: a drag started from another window never
     * delivers its end event here.
     */
    fun showMenu(anchor: Rect, build: LinearLayout.() -> Unit) {
        // Wider with a larger system font size, so the tracked tile legends don't clip.
        val margin = (8 * dp).toInt()
        val w = minOf((264 * dp * maxOf(1f, resources.configuration.fontScale)).toInt(), root.width - 2 * margin)
        menuWidth = w
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            frost(this, 24 * dp)
            clipToOutline = true
            val p = (6 * dp).toInt()
            setPadding(0, p, 0, p)
            build()
        }
        closeMenu()
        box.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val x = (anchor.centerX() - w / 2).coerceIn(margin, maxOf(margin, root.width - w - margin))
        val below = anchor.bottom + margin
        val y = if (below + box.measuredHeight < root.height - insets.bottom) below
            else maxOf(insets.top, anchor.top - box.measuredHeight - margin)
        val shade = FrameLayout(this).apply {
            setOnClickListener { closeMenu() }
            // No drag listener: a drag goes to the view under the finger, so a folder window reordering its icons keeps
            // itself open (trackDrag's keepOverlay) instead of this full-screen shade closing it on the first move (#1).
            // LEFT, not the default START: x is measured from the left edge in right-to-left languages too.
            addView(box, FrameLayout.LayoutParams(w, -2, Gravity.TOP or Gravity.LEFT).apply { leftMargin = x; topMargin = y })
        }
        root.addView(shade)
        menu = shade
        box.pivotX = (anchor.centerX() - x).toFloat()
        box.pivotY = if (y >= below) 0f else box.measuredHeight.toFloat()
        box.scaleX = 0.8f; box.scaleY = 0.8f; box.alpha = 0f
        box.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).setInterpolator(android.view.animation.OvershootInterpolator(1.1f)).start()
    }

    /** Width of the menu being built by [showMenu]. */
    private var menuWidth = 0

    /** Icon-over-label action tiles for a menu: up to [maxCols] to a row, fewer when a large font makes the legends wide. */
    fun LinearLayout.addTiles(tiles: List<Triple<String, String, () -> Unit>>, maxCols: Int) {
        val pad = (6 * dp).toInt()
        val views = tiles.map { (sym, title, click) ->
            (if (m3.housing) legend(title, 10f) else text(title, 11f, m3.onSurfaceVariant)).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                compoundDrawablePadding = (6 * dp).toInt()
                setCompoundDrawablesWithIntrinsicBounds(null, symbol(sym, m3.onSurface, 22f), null, null)
                setPadding(0, (12 * dp).toInt(), 0, (10 * dp).toInt())
                background = pressable(null, 16 * dp)
                setOnClickListener { closeMenu(); click() }
            }
        }
        val widest = views.maxOfOrNull { it.measure(0, 0); it.measuredWidth } ?: 1
        val cols = ((menuWidth - 2 * pad) / maxOf(1, widest)).coerceIn(1, maxCols)
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
    private fun hairline() = View(this).apply {
        setBackgroundColor(m3.onSurface and 0xFFFFFF or 0x1F000000)
        layoutParams = LinearLayout.LayoutParams(-1, maxOf(1, (dp / 2).toInt())).apply { setMargins(0, (6 * dp).toInt(), 0, (6 * dp).toInt()) }
    }

    // ponytail: copies the base APK only; split APKs (app bundles) need all of applicationInfo.splitSourceDirs to install
    @Suppress("DEPRECATION")
    private fun saveApk(a: App) {
        onDocument = { uri ->
            runCatching {
                java.io.File(a.info.applicationInfo.sourceDir).inputStream().use { src -> contentResolver.openOutputStream(uri, "wt")!!.use { src.copyTo(it) } }
            }.onSuccess { toast(R.string.apk_saved) }.onFailure { toast(R.string.couldnt_save, it.message) }
        }
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/vnd.android.package-archive").putExtra(Intent.EXTRA_TITLE, "${a.pkg}.apk"), REQ_DOC)
    }

    @Suppress("DEPRECATION")
    private fun exportIcon(a: App) {
        onDocument = { uri ->
            val size = (192 * dp).toInt()
            val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            icon(a).apply { setBounds(0, 0, size, size); draw(android.graphics.Canvas(bmp)) }
            runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                .onSuccess { toast(R.string.icon_saved) }.onFailure { toast(R.string.couldnt_save, it.message) }
        }
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/png").putExtra(Intent.EXTRA_TITLE, "${label(a)}.png"), REQ_DOC)
    }

    private fun isSystemApp(a: App) = a.info.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0

    fun toggleHidden(key: String) {
        val hidden = store.strings("hidden")
        if (!hidden.remove(key)) hidden += key
        store.putStrings("hidden", hidden)
        drawer.refresh()
    }

    // ---- Home menu, widgets and shortcuts ----

    fun homeMenu() {
        val entries = listOf(
            Triple("widgets", getString(R.string.widgets)) { pickWidget() },
            Triple("bolt", getString(R.string.shortcuts)) { addShortcutMenu() },
            Triple("image", getString(R.string.wallpaper)) { perform("wallpaper") },
            Triple("carousel", getString(R.string.pages)) { showOverview() },
            // As a clock widget: one row across the page, sized and moved like one.
            Triple("clock", getString(R.string.clock)) { addItem(Item(0, 0, 0, looks.cols * sub, sub, action = "clock")); Unit },
            Triple(if (locked) "unlock" else "lock", getString(if (locked) R.string.unlock else R.string.lock)) {
                looks["lockLayout"] = if (locked) "off" else "on"; toast(if (locked) R.string.layout_locked else R.string.layout_unlocked)
            },
            Triple("settings", getString(R.string.settings)) { showSettings() },
        ).filter { !locked || it.first !in listOf("widgets", "bolt", "clock") }
        val at = Rect(lastDown.x.toInt(), lastDown.y.toInt(), lastDown.x.toInt(), lastDown.y.toInt())
        showMenu(at) { addTiles(entries, 3) }
    }

    /**
     * The widget picker, as Launcher3's: a row per app (icon, name, how many widgets), A to Z; tapping one opens its
     * widgets under it, A to Z, and closes the last one opened (#18). For one app ([pkg], or only one has widgets), its
     * widgets alone.
     */
    fun pickWidget(pkg: String? = null) {
        val order = labelOrder()
        val page = pages[workspace.current]
        val groups = awm.installedProviders.filter { pkg == null || it.provider.packageName == pkg }.groupBy { it.provider.packageName }
            .map { (p, list) ->
                Triple(p, runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p),
                    list.map { it to it.loadLabel(packageManager).toString() }.sortedWith(compareBy(order) { it.second }))
            }.sortedWith(compareBy(order) { it.second })
        val flat = groups.size <= 1
        val previewH = (120 * dp).toInt()
        lateinit var d: android.app.AlertDialog
        // ponytail: a widget with only a previewLayout (Android 12+, no preview image) shows its app icon; inflating the
        // layout through an AppWidgetHostView would show the real thing.
        fun widgetRow(info: AppWidgetProviderInfo, label: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * dp).toInt()
            setPadding(pad * 3 / 2, pad / 2, pad * 3 / 2, pad / 2)
            background = pressable(null)
            addView(ImageView(context).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_START
                maxHeight = previewH
                setImageDrawable(info.loadPreviewImage(this@MainActivity, 0) ?: info.loadIcon(this@MainActivity, 0))
            }, LinearLayout.LayoutParams(-2, -2))
            val w = ceil(info.minWidth / page.cellW.toFloat() / sub).toInt().coerceAtLeast(1)
            val h = ceil(info.minHeight / page.cellH.toFloat() / sub).toInt().coerceAtLeast(1)
            addView(TextView(context).apply { setTextColor(m3.onSurface); textSize = 14f; setPadding(0, pad / 4, 0, 0); text = "$label  ($w×$h)" })
            contentDescription = "$label  ($w×$h)"
            setOnClickListener { d.dismiss(); addWidget(info) }
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(this).apply { addView(list) }
        var close = {}
        for ((p, app, widgets) in groups) {
            if (flat) { widgets.forEach { (info, label) -> list.addView(widgetRow(info, label)) }; continue }
            val rows by lazy { widgets.map { (info, label) -> widgetRow(info, label) } }
            val chevron = ImageView(this).apply { setImageDrawable(symbol("right")); if (layoutDirection == View.LAYOUT_DIRECTION_RTL) scaleX = -1f }
            val icon = apps.values.firstOrNull { it.pkg == p }?.let(::icon) ?: runCatching { packageManager.getApplicationIcon(p) }.getOrNull()
            lateinit var header: View
            var open = false
            header = listItem(icon, app, resources.getQuantityString(R.plurals.widget_count, widgets.size, widgets.size), chevron, appIcon = true) {
                val opening = !open
                close()
                if (!opening) return@listItem
                open = true
                chevron.animate().rotation(90f * chevron.scaleX).setDuration(150).start()
                val at = list.indexOfChild(header) + 1
                rows.forEachIndexed { i, r -> list.addView(r, at + i) }
                close = { rows.forEach(list::removeView); chevron.animate().rotation(0f).setDuration(150).start(); open = false; close = {} }
                header.post { scroll.smoothScrollTo(0, header.top) }
            }
            list.addView(header)
        }
        d = dialog().setTitle(R.string.widgets).setView(scroll).create().also { it.show() }
    }

    private fun addWidget(info: AppWidgetProviderInfo) {
        val id = host.allocateAppWidgetId()
        pendingWidget = id to info
        if (awm.bindAppWidgetIdIfAllowed(id, info.provider)) configureWidget()
        else startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider), REQ_BIND)
    }

    private fun configureWidget() {
        val (id, info) = pendingWidget ?: return
        if (info.configure != null) host.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIG, null)
        else placeWidget()
    }

    private fun placeWidget() {
        val (id, info) = pendingWidget ?: return
        val page = pages[workspace.current]
        // After a restart the result arrives before the first layout; with no cell size yet the widget would fill the page.
        if (page.width == 0) {
            page.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                    v.removeOnLayoutChangeListener(this)
                    placeWidget()
                }
            })
            return
        }
        pendingWidget = null
        val w = ceil(info.minWidth / page.cellW.toFloat()).toInt().coerceIn(1, page.cols)
        val h = ceil(info.minHeight / page.cellH.toFloat()).toInt().coerceIn(1, page.rows)
        if (!addItem(Item(workspace.current, 0, 0, w, h, widgetId = id), quiet = true)) {
            host.deleteAppWidgetId(id)
            toast(R.string.no_room)
        }
    }

    private fun cancelWidget() {
        pendingWidget?.let { host.deleteAppWidgetId(it.first) }
        pendingWidget = null
    }

    fun resizeWidget(item: Item) {
        val v = views[item] ?: return
        val cell = v.parent as? CellLayout ?: return
        // The clock: any size from two cells wide and one tall.
        val info = if (item.isClock) null else awm.getAppWidgetInfo(item.widgetId) ?: return
        val canW = info == null || info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
        val canH = info == null || info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
        val minW = if (!canW) item.w else if (info == null) minOf(2 * sub, item.w)
            else ceil(info.minResizeWidth.coerceAtLeast(1) / cell.cellW.toFloat()).toInt().coerceIn(1, item.w)
        val minH = if (!canH) item.h else if (info == null) minOf(sub, item.h)
            else ceil(info.minResizeHeight.coerceAtLeast(1) / cell.cellH.toFloat()).toInt().coerceIn(1, item.h)
        if (getSystemService(android.view.accessibility.AccessibilityManager::class.java).isTouchExplorationEnabled) resizeByList(cell, item, v, minW, minH, canW, canH)
        else showResize(cell, item, v, minW, minH, canW, canH)
    }

    /** TalkBack can't drag the resize handles: the sizes that fit from the widget's top corner, as a list. */
    private fun resizeByList(cell: CellLayout, item: Item, v: View, minW: Int, minH: Int, canW: Boolean, canH: Boolean) {
        val sizes = widgetSizes(item.x, item.y, item.w, item.h, minW, minH, cell.cols, cell.rows, canW, canH)
            .filter { (w, h) -> looks.on("widgetOverlap") || cell.isFree(item.x, item.y, w, h, ignore = v) }
            // Subgrid: whole-cell steps only, or the list runs to a hundred entries.
            .filter { (w, h) -> (w == item.w || w % sub == 0) && (h == item.h || h % sub == 0) }
        fun cells(n: Int) = if (n % sub == 0) "${n / sub}" else "${n / sub}½"
        dialog().setTitle(R.string.resize).setItems(sizes.map { (w, h) -> "${cells(w)} × ${cells(h)}".let { if (w == item.w && h == item.h) getString(R.string.resize_current, it) else it } }
            .toTypedArray()) { _, i ->
            val (w, h) = sizes[i]
            items[items.indexOf(item)] = item.copy(w = w, h = h)
            save()
            render()
        }.show()
    }

    private fun addShortcutMenu() {
        val options = listOf(R.string.launcher_action, R.string.app_shortcut, R.string.activity).map(::getString).toTypedArray()
        dialog().setTitle(R.string.add_to_home).setItems(options) { _, which ->
            when (which) {
                0 -> pickAction(getString(R.string.launcher_action)) { addItem(Item(0, 0, 0, sub, sub, action = it)) }
                1 -> {
                    val list = packageManager.queryIntentActivities(Intent(Intent.ACTION_CREATE_SHORTCUT), 0)
                        .sortedWith(compareBy(labelOrder()) { it.loadLabel(packageManager).toString() })
                    // Each with its app's icon and name: shortcut names alone ("Contact", "Direct dial") don't say whose they are.
                    pickList(getString(R.string.app_shortcut), list.map { it.loadIcon(packageManager) to it.loadLabel(packageManager) }, appIcon = true,
                        summaries = list.map { it.activityInfo.applicationInfo.loadLabel(packageManager) }) { i ->
                        val a = list[i].activityInfo
                        runCatching { startActivityForResult(Intent(Intent.ACTION_CREATE_SHORTCUT).setComponent(ComponentName(a.packageName, a.name)), REQ_SHORTCUT) }
                    }
                }
                else -> pickApp(getString(R.string.activity)) { app ->
                    val acts = runCatching { packageManager.getPackageInfo(app.pkg, PackageManager.GET_ACTIVITIES).activities }
                        .getOrNull().orEmpty().filter { it.exported }
                    dialog().setTitle(label(app)).setItems(acts.map { "${it.loadLabel(packageManager)}\n${it.name.removePrefix(app.pkg)}" }.toTypedArray()) { _, i ->
                        addItem(Item(0, 0, 0, sub, sub, pkg = app.pkg, cls = acts[i].name, action = "activity"))
                    }.show()
                }
            }
        }.show()
    }

    // ---- Actions ----

    fun perform(action: String) {
        when {
            action.startsWith("page:") -> workspace.snapTo(action.removePrefix("page:").toIntOrNull() ?: 0)
            action.startsWith("app:") -> apps[action.removePrefix("app:")]?.let { launch(it, null) } ?: toast(R.string.app_not_installed)
            else -> when (action) {
                "drawer" -> drawer.show(false)
                "now" -> if (looks.on("nowPage")) showNow()
                "search" -> drawer.show(true)
                "notifications" -> statusBar("expandNotificationsPanel", AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                "quicksettings" -> statusBar("expandSettingsPanel", AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
                "togglebar" -> { barHidden = !barHidden; applyBar() }
                "recents" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
                "lock" -> if (android.os.Build.VERSION.SDK_INT >= 28) global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) else toast(R.string.lock_needs_9)
                "power" -> global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
                "defaultpage" -> workspace.snapTo(looks.int("defaultPage"))
                "previews" -> showOverview()
                "toggledock" -> dockBox.visibility = if (dockBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                "voice" -> startAny(Intent(RecognizerIntent.ACTION_WEB_SEARCH), Intent(Intent.ACTION_VOICE_COMMAND))
                "assistant" -> startAny(Intent(Intent.ACTION_ASSIST), Intent(Intent.ACTION_VOICE_COMMAND))
                "settings" -> showSettings()
                "widgets" -> pickWidget()
                "wallpaper" -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), getString(R.string.wallpaper)))
                "torch" -> toggleTorch()
            }
        }
    }

    private var torchOn = false
    /** Keeps [torchOn] current, also when the torch is switched elsewhere (quick settings). Registered on first use. */
    private var torchWatch: android.hardware.camera2.CameraManager.TorchCallback? = null

    /** Needs no permission: setTorchMode doesn't open the camera. */
    private fun toggleTorch() {
        val cm = getSystemService(android.hardware.camera2.CameraManager::class.java)
        val id = runCatching {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        }.getOrNull() ?: return toast(R.string.no_flashlight)
        if (torchWatch == null) torchWatch = object : android.hardware.camera2.CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (cameraId == id) torchOn = enabled }
        }.also { cm.registerTorchCallback(it, null) }
        // A camera app holding the camera makes this fail.
        runCatching { cm.setTorchMode(id, !torchOn) }.onSuccess { torchOn = !torchOn }.onFailure { toast(R.string.no_flashlight) }
    }

    private fun startAny(vararg intents: Intent) {
        for (i in intents) try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: ActivityNotFoundException) {}
        toast(R.string.no_app_handles)
    }

    /** Hidden StatusBarManager API (needs EXPAND_STATUS_BAR); falls back to the accessibility service. */
    @SuppressLint("WrongConstant")
    private fun statusBar(method: String, fallback: Int) {
        val ok = runCatching {
            val sbm = getSystemService("statusbar")
            sbm.javaClass.getMethod(method).invoke(sbm)
        }.isSuccess
        if (!ok) global(fallback)
    }

    private fun global(action: Int) {
        val svc = ActionService.instance
        if (svc != null) { svc.performGlobalAction(action); return }
        dialog().setTitle(R.string.accessibility_title)
            .setMessage(R.string.accessibility_text)
            .setPositiveButton(R.string.open_settings) { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    @Suppress("DEPRECATION")
    private fun applyBar() {
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

    /** After a restore: widget IDs belong to the install that made them; drop the ones this install doesn't own. */
    fun dropForeignWidgets() {
        val s = looks.sub // the restored setting; load converts the restored layout to it
        store.save(store.load(s).filter { !it.isWidget || awm.getAppWidgetInfo(it.widgetId) != null }, s)
    }

    // ---- Activity results ----

    @Deprecated("Plain Activity result API is fine without androidx")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val ok = resultCode == RESULT_OK
        when (requestCode) {
            REQ_BIND -> if (ok) configureWidget() else cancelWidget()
            REQ_CONFIG -> if (ok) placeWidget() else cancelWidget()
            REQ_SHORTCUT -> if (ok && data != null) {
                val req = launcherApps.getPinItemRequest(data)
                // ponytail: pre-Android 8 style EXTRA_SHORTCUT_INTENT results aren't stored; every current app uses pin requests
                if (req != null && addPinned(this, req)) reloadItems()
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
