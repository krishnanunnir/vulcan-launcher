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
 * How many finished icons (about 110 kB each) the in-memory cache holds: roughly a sixteenth of the heap, between
 * 64 and 256 icons (about 7 to 28 MB). Bounded, so entries are evicted one by one instead of the whole cache being
 * dropped whenever home is hidden.
 */
fun iconCacheCapacity(maxMemoryBytes: Long): Int =
    ((maxMemoryBytes / 16) / (110 * 1024)).coerceIn(64, 256).toInt()

/**
 * The installed apps of every profile, with their labels, icons and shortcuts. Owns the icon caches.
 * [context] is the home activity: icons are drawn for its resources (density, night mode).
 */
class AppRepo(private val context: Context, private val store: Store, private val launcherApps: LauncherApps, private val um: UserManager) {
    private val resources get() = context.resources
    private val packageManager get() = context.packageManager
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var icons: Icons

    /** New icon settings: [icons] draws from now on, with the disk cache that matches it. Follow with [loadApps]. */
    fun restyle(icons: Icons) {
        this.icons = icons
        appCache = AppCache(context, icons.signature)
    }

    /** Memory pressure: drops every icon held in memory ([all]) or the oldest half. The disk cache stays. */
    fun trim(all: Boolean) {
        if (all) iconCache.evictAll() else iconCache.snapshot().keys.take(iconCache.size() / 2).forEach(iconCache::remove)
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
        pausedUsers = profiles.filter { it != Process.myUserHandle() && it != privateUser && um.isQuietModeEnabled(it) }.toSet()
        apps = profiles.filter { it != privateUser || !privateLocked }.flatMap { user -> launcherApps.getActivityList(null, user).map { App(it, serial(user), appCache::label) } }
            .filter { it.pkg != context.packageName }.associateBy { it.key }
        customs = store.obj("custom")
        // ponytail: personal profile only (PackageManager sees one user); a work-profile calendar keeps its static icon.
        dateIcons = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.GET_META_DATA)
            .mapNotNull { r -> r.activityInfo.let { ai ->
                // Samsung's "LiveIconSupport" apps (Samsung Calendar) draw the date into their own icon: 0, no array.
                (ai.metaData?.getInt("${ai.packageName}.dynamic_icons")?.takeIf { it != 0 }
                    ?: 0.takeIf { ai.applicationInfo.metaData?.getBoolean("LiveIconSupport") == true })?.let { "${ai.packageName}/${ai.name}" to it } } }
            .toMap()
        if (changed == null) { iconCache.evictAll(); appCache.keepOnly(apps.keys) }
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
        return try {
            val info = launcherApps.getApplicationInfo(item.pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES, user)
            info != null && info.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED == 0
        } catch (e: PackageManager.NameNotFoundException) { true } catch (e: Exception) { false }
    }

    fun label(a: App): String = customs.optJSONObject(a.key)?.optString("label")?.takeIf { it.isNotEmpty() } ?: a.title
    /** Work profiles that are paused: their apps show greyed out, as in Launcher3 (#73). */
    private var pausedUsers = emptySet<UserHandle>()

    fun icon(a: App): Drawable = baseIcon(a).let { d -> if (a.info.user !in pausedUsers) d else greyed(d.constantState!!.newDrawable(resources)) }

    private fun greyed(d: Drawable) = d.mutate().apply {
        colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) }); alpha = 140
    }

    /** Each pinned shortcut's last real icon: a paused profile can't be asked for it, and just after it resumes it still can't (#103). */
    private val shortcutFaces = HashMap<String, Drawable.ConstantState>()

    fun shortcutFace(item: Item, s: ShortcutInfo?): Drawable? {
        val key = "${item.user}/${item.pkg}/${item.shortcutId}"
        s?.let { runCatching { launcherApps.getShortcutBadgedIconDrawable(it, resources.displayMetrics.densityDpi) }.getOrNull() }
            ?.let { d -> d.constantState?.let { shortcutFaces[key] = it } ?: return d }
        val d = shortcutFaces[key]?.newDrawable(resources) ?: return null
        return if (userOf(item.user) in pausedUsers) greyed(d) else d
    }

    private fun baseIcon(a: App): Drawable = iconCache.get(a.key) ?: run {
        val custom = synchronized(customs) { customs.optJSONObject(a.key)?.optString("icon") } ?: ""
        // Icons.get always ends in a flat bitmap. Decoded outside any lock: prefetch runs this on a worker thread
        // while the UI thread may decode the same icon, so a duplicate decode is merged by the put below.
        val dated = dateIcons["${a.pkg}/${a.cls}"]?.takeIf { a.info.user == Process.myUserHandle() }
        val day = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH)
        // A date icon's disk entry also holds the day it shows, so tomorrow draws afresh.
        val d = BitmapDrawable(resources, appCache.icon(a, if (dated == null) custom else "$custom|day$day") {
            (icons.get(a.pkg, a.cls, custom) { dated?.takeIf { it != 0 }?.let { dateIcon(a, it, day) } ?: a.info.getBadgedIcon(0) } as BitmapDrawable).bitmap
        })
        iconCache.put(a.key, d); d
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
    fun isWork(a: App) = a.info.user != Process.myUserHandle() && !isPrivate(a)
    fun isPrivate(a: App) = a.info.user == privateUser

    /** Locks or unlocks Private Space; unlocking asks for the screen lock first. The profile broadcast then reloads the apps. */
    fun setPrivateLocked(lock: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= 35) privateUser?.let { runCatching { um.requestQuietModeEnabled(lock, it) } }
    }

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
