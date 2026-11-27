package dev.vulcanlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import org.json.JSONObject


/** A launchable app from any profile (personal or work). [serial] is the profile's UserManager serial number. */
class App(val info: LauncherActivityInfo, val serial: Long, labelOf: (App) -> String) {
    val pkg: String = info.componentName.packageName
    val cls: String = info.componentName.className
    val key = appKey(pkg, cls, serial)
    /** The app's own label. Cached: each LauncherActivityInfo.getLabel() loads the app's resources, and sorting asks often. */
    val title: String by lazy(LazyThreadSafetyMode.NONE) { labelOf(this) }
}

/**
 * How many finished icons (about 110 kB each) the in-memory cache holds: roughly a thirty-second of the heap, between
 * 64 and 112 icons (about 7 to 12 MB), which is two to three drawer screens. The drawer prefetches the next screen
 * off the UI thread and the disk cache serves the rest, so more than that only held memory (30 MB after one scroll
 * through 600 apps).
 */
fun iconCacheCapacity(maxMemoryBytes: Long): Int =
    ((maxMemoryBytes / 32) / (110 * 1024)).coerceIn(64, 112).toInt()

/** Icons kept in memory while home is hidden: the two drawer screens seen last, so coming back draws at once. */
const val ICONS_KEPT_HIDDEN = 48

/**
 * The installed apps of every profile, with their labels, icons and shortcuts. Owns the icon caches.
 * [context] is the home activity: icons are drawn for its resources (density, night mode).
 */
class AppRepo(private val context: Context, private val store: Store, private val launcherApps: LauncherApps, private val um: UserManager) {
    private val resources get() = context.resources
    private val packageManager get() = context.packageManager
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var icons: Icons

    /**
     * New settings: [icons] draws from now on. Follow with [loadApps]. Only a change that shapes icons
     * ([Icons.signature]) drops the ones held: any other setting keeps them, so home isn't drawn from disk again (#100).
     */
    fun restyle(icons: Icons) = synchronized(this) {
        val same = ::icons.isInitialized && this.icons.signature == icons.signature
        this.icons = icons
        if (same) return@synchronized
        appCache = AppCache(context, icons.signature)
        generation++
        restyled = true
    }
    /** [restyle] changed how icons look, and [loadApps] hasn't dropped the old ones yet. */
    private var restyled = false
    /** Counts [restyle]s: an icon the worker thread began in the old style must not land in the new style's cache. */
    private var generation = 0

    /** Memory pressure: drops every icon held in memory ([all]) or all but the [ICONS_KEPT_HIDDEN] used last. The disk cache stays. */
    fun trim(all: Boolean) {
        if (all) iconCache.evictAll() else iconCache.trimToSize(ICONS_KEPT_HIDDEN)
    }

    /** The launcher activities of [pkg] in profile [user]. */
    fun activities(pkg: String, user: UserHandle) = launcherApps.getActivityList(pkg, user).map { App(it, serial(user), appCache::label) }

    /** The packages whose icon shows today's date. */
    val datedPackages get() = dateIcons.keys.map { it.substringBefore('/') }.distinct()

    var apps = mapOf<String, App>()
        private set
    var customs = JSONObject()
        private set
    /** Bounded and thread-safe (LruCache locks internally), so prefetch can fill it off the UI thread. */
    private val iconCache = android.util.LruCache<String, Drawable>(iconCacheCapacity(Runtime.getRuntime().maxMemory()))
    private val iconLoader = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** Icons and labels kept on disk across starts, so loading them doesn't open every app's APK. Set with [icons]. */
    private lateinit var appCache: AppCache

    fun serial(user: UserHandle) = um.getSerialNumberForUser(user)
    private val ownSerial = um.getSerialNumberForUser(Process.myUserHandle())
    /** What [BadgeService.byPackage] lists the notifications of [pkg] in profile [user] under, so a work app doesn't badge its personal twin. */
    fun badgeKey(pkg: String, user: Long) = if (user == 0L || user == ownSerial) pkg else "$pkg#$user"

    fun userOf(serial: Long): UserHandle = um.getUserForSerialNumber(serial) ?: Process.myUserHandle()

    /** Profiles and hidden apps: what kind each profile is, whether it is on, and where an app may show. */
    val spaces = Spaces(um, launcherApps, store)

    /** [changed]: the only packages whose icons may have changed; null drops every cached icon. */
    fun loadApps(changed: Collection<String>? = null) {
        val profiles = launcherApps.profiles
        spaces.load(profiles)
        // A locked Private Space shows none of its apps, anywhere.
        apps = profiles.filter { it != spaces.privateUser || !spaces.privateLocked }.flatMap { user -> val s = serial(user); launcherApps.getActivityList(null, user).map { App(it, s, appCache::label) } } // one binder call per profile, not per app
            .filter { it.pkg != context.packageName }.associateBy { it.key }
        val customsWere = customs.toString()
        customs = store.obj("custom")
        // ponytail: personal profile only (PackageManager sees one user); a work-profile calendar keeps its static icon.
        dateIcons = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.GET_META_DATA)
            .mapNotNull { r -> r.activityInfo.let { ai ->
                // Samsung's "LiveIconSupport" apps (Samsung Calendar) draw the date into their own icon: 0, no array.
                (ai.metaData?.getInt("${ai.packageName}.dynamic_icons")?.takeIf { it != 0 }
                    ?: 0.takeIf { ai.applicationInfo.metaData?.getBoolean("LiveIconSupport") == true })?.let { "${ai.packageName}/${ai.name}" to it } } }
            .toMap()
        if (changed == null) {
            // Held icons stay good unless their style or a custom icon (a restored backup) changed.
            if (restyled || customs.toString() != customsWere) iconCache.evictAll()
            restyled = false
            appCache.keepOnly(apps.keys)
        }
        else { iconCache.snapshot().keys.filter { it.substringBefore('/') in changed }.forEach(iconCache::remove); appCache.forget(changed) }
    }

    /**
     * Whether [item]'s package is gone for good: Android says it isn't installed for that profile, or the profile was
     * removed. Anything uncertain (on an unmounted SD card, mid-update, disabled, a paused or locked profile) counts as
     * there, so its icon comes back when the app does.
     */
    fun uninstalled(item: Item): Boolean {
        if (apps.values.any { it.pkg == item.pkg && it.serial == item.user }) return false
        val user = um.getUserForSerialNumber(item.user) ?: return true
        // A profile Vulcan can't ask about (Private Space while another launcher is the default home) answers "not found" for everything.
        if (user !in launcherApps.profiles || spaces.stateNow(user) != SpaceState.ON) return false
        return try {
            val info = launcherApps.getApplicationInfo(item.pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES, user)
            info != null && info.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED == 0
        } catch (e: PackageManager.NameNotFoundException) { true } catch (e: Exception) { false }
    }

    fun label(a: App): String = customs.optJSONObject(a.key)?.optString("label")?.takeIf { it.isNotEmpty() } ?: a.title
    /** A paused work profile's apps show greyed out, as in Launcher3 (#73). */
    fun icon(a: App): Drawable = baseIcon(a).let { d -> if (spaces.state(a.info.user) != SpaceState.OFF) d else greyed(d.constantState!!.newDrawable(resources)) }

    private fun greyed(d: Drawable) = d.mutate().apply {
        colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) }); alpha = 140
    }

    /** Each pinned shortcut's last real icon: a paused profile can't be asked for it, and just after it resumes it still can't (#103). */
    private val shortcutFaces = HashMap<String, Drawable.ConstantState>()

    fun shortcutFace(item: Item, s: ShortcutInfo?): Drawable? {
        val key = "${item.user}/${item.pkg}/${item.shortcutId}"
        s?.let { runCatching { launcherApps.getShortcutBadgedIconDrawable(it, resources.displayMetrics.densityDpi) }.getOrNull() }
            ?.let { d -> d.constantState?.let { shortcutFaces[key] = it } ?: return d }
        // Not seen since Vulcan started (#103): its app's icon, as Launcher3 shows it, not Android's generic one.
        val d = shortcutFaces[key]?.newDrawable(resources)
            ?: return userOf(item.user).let { u -> apps.values.firstOrNull { it.pkg == item.pkg && it.info.user == u } }?.let(::icon)
        return if (spaces.state(userOf(item.user)) == SpaceState.OFF) greyed(d) else d
    }

    /**
     * [b] held by the GPU only, where [HARDWARE_ICONS]. Drawn as a plain bitmap an icon is held twice, in the heap
     * and as a texture: on a 55-app S23 this took 8 MB off the open drawer and off home in the background.
     * Launcher3 does the same.
     */
    private fun hardware(b: android.graphics.Bitmap) =
        if (HARDWARE_ICONS) b.copy(android.graphics.Bitmap.Config.HARDWARE, false) ?: b else b

    private fun baseIcon(a: App): Drawable = iconCache.get(a.key) ?: run {
        val custom = synchronized(customs) { customs.optJSONObject(a.key)?.optString("icon") } ?: ""
        // Icons.get always ends in a flat bitmap. Decoded outside any lock: prefetch runs this on a worker thread
        // while the UI thread may decode the same icon, so a duplicate decode is merged by the put below.
        val dated = dateIcons["${a.pkg}/${a.cls}"]?.takeIf { a.info.user == Process.myUserHandle() }
        val day = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH)
        // A date icon's disk entry also holds the day it shows, so tomorrow draws afresh.
        val (gen, icons, appCache) = synchronized(this) { Triple(generation, icons, appCache) }
        val d = BitmapDrawable(resources, hardware(appCache.icon(a, if (dated == null) custom else "$custom|day$day") {
            (icons.get(a.pkg, a.cls, custom) { dated?.takeIf { it != 0 }?.let { dateIcon(a, it, day) } ?: a.info.getBadgedIcon(0) } as BitmapDrawable).bitmap
        }))
        if (gen == synchronized(this) { generation }) iconCache.put(a.key, d)
        d
    }

    /** Launcher activities that draw today's date ("<pkg>.dynamic_icons" meta-data: 31 drawables), to their array; 0 for a Samsung live icon. */
    private var dateIcons: Map<String, Int> = emptyMap()

    /** Day [day]'s drawable from [a]'s dynamic-icon [array], as Launcher3 picks it for Google Calendar. */
    private fun dateIcon(a: App, array: Int, day: Int): Drawable? = runCatching {
        val res = packageManager.getResourcesForApplication(a.info.applicationInfo)
        val ids = res.obtainTypedArray(array)
        val id = try { ids.getResourceId(day - 1, 0) } finally { ids.recycle() }
        res.getDrawableForDensity(id, resources.displayMetrics.densityDpi, null)
    }.getOrNull()

    /** Warms the icons of [apps] on a worker thread, so scrolling the drawer does not decode them on the UI thread. */
    /** [a]'s icon if it's in memory; else null, and [ready] gets it on the UI thread once loaded off it. */
    fun iconLater(a: App, ready: (Drawable) -> Unit): Drawable? = if (iconCache.get(a.key) != null) icon(a) else null.also {
        iconLoader.execute { val d = icon(a); ui.post { ready(d) } }
    }
    fun prefetchIcons(apps: Collection<App>) {
        val missing = apps.filter { iconCache.get(it.key) == null }
        if (missing.isNotEmpty()) iconLoader.execute { missing.forEach(::icon) }
    }
    /** Main activity of [pkg] in profile [user], for apps a backup names by package only. */
    fun mainActivity(pkg: String, user: Long): String? {
        val mine = apps.values.filter { it.pkg == pkg && it.serial == user }
        // A package can have several launcher activities (Google: search and voice search); take the one Android launches.
        val launch = packageManager.getLaunchIntentForPackage(pkg)?.component?.className
        return (mine.firstOrNull { it.cls == launch } ?: mine.firstOrNull())?.cls
    }

    /** An app's name by key, or a "shortcut:" action's shortcut name (see actionName). */
    fun appLabel(key: String): String? = shortcutAction(key)?.let { (p, id, u) ->
        pinnedShortcut(Item(0, 0, 0, pkg = p, shortcutId = id, user = u))?.shortLabel?.toString()
    } ?: apps[key]?.let(::label)
    fun shortcutsFor(a: App): List<ShortcutInfo> = runCatching {
        launcherApps.getShortcuts(LauncherApps.ShortcutQuery().setPackage(a.pkg).setActivity(a.info.componentName)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST), a.info.user)
            .orEmpty().sortedBy { it.rank }.take(4) // Launcher3's MAX_SHORTCUTS
    }.getOrDefault(emptyList())

    /** Every app's shortcuts in every profile, for drawer search; none unless Vulcan is the default home (an Android rule). */
    fun allShortcuts(): List<ShortcutInfo> {
        if (!runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false)) return emptyList()
        val query = LauncherApps.ShortcutQuery().setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
        // A locked profile throws; its shortcuts are left out.
        return launcherApps.profiles.flatMap { u -> runCatching { launcherApps.getShortcuts(query, u).orEmpty() }.getOrDefault(emptyList()) }
    }

    fun pinnedShortcut(item: Item): ShortcutInfo? = runCatching {
        launcherApps.getShortcuts(LauncherApps.ShortcutQuery().setPackage(item.pkg).setShortcutIds(listOf(item.shortcutId))
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED), userOf(item.user))?.firstOrNull()
    }.getOrNull()

    fun shortcutIcon(s: ShortcutInfo): Drawable =
        runCatching { launcherApps.getShortcutIconDrawable(s, resources.displayMetrics.densityDpi) }.getOrNull() ?: packageManager.defaultActivityIcon
}
