package dev.vulcanlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import java.util.Calendar

/** What home (MainActivity) and the settings screen (SettingsActivity) share: settings, colours, dialogs. */
abstract class VulcanActivity : Activity() {
    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        // Android 16 no longer dispatches onBackPressed for apps targeting API 36.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { handleBack() }
        }
    }

    protected open fun handleBack() { finish() }

    @Deprecated("Legacy Android Back dispatch")
    @android.annotation.SuppressLint("GestureBackNavigation") // API 33+ uses the registered platform callback above.
    override fun onBackPressed() { handleBack() }

    /** Both gestures and the Permissions page show this before opening Android's grant screen. */
    fun requestActionAccess() {
        dialog().setTitle(R.string.accessibility_title)
            .setMessage(R.string.accessibility_text)
            .setPositiveButton(R.string.agree_open_settings) { _, _ ->
                startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    fun showPrivacyPolicy() {
        val policy = resources.openRawResource(R.raw.privacy_policy).bufferedReader().use { it.readText() }
        dialog().setTitle(R.string.privacy_policy).setMessage(policy)
            .setPositiveButton(R.string.ok, null).show()
    }

    lateinit var store: Store
    lateinit var looks: Looks
    lateinit var root: FrameLayout
    /** The launcher: this activity on home, the one under it in settings. */
    abstract val home: MainActivity
    var night = false
    val dp get() = resources.displayMetrics.density
    /** Material 3 colours for this activity, from the theme colour setting and the panel theme. */
    lateinit var m3: Palette
    /** Text and panel colours for drawer, folders, menus and settings. */
    val darkPanels get() = when (looks.str("drawerTheme")) { "dark" -> true; "light" -> false; else -> night }
    val panelText get() = m3.onSurface
    fun panelBg(alphaPercent: Int = 100, radius: Float = 16 * dp, color: Int = m3.surfaceContainer) =
        rounded(color and 0xFFFFFF or ((alphaPercent * 255 / 100) shl 24), radius)
    /** A colour setting; "system" gives [fallback], a Material colour role. */
    fun themed(key: String, fallback: Int) = if (looks.str(key) == "system") fallback else looks.color(key)
    /** System window insets, kept for overlays that draw edge to edge. */
    val insets = Rect()

    /** The "Screen orientation" setting. */
    fun applyOrientation() {
        requestedOrientation = when (looks.str("orientation")) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    /** Night mode and the palette, from the settings now. */
    fun recolor() {
        night = computeNight()
        m3 = palette(this, darkPanels, looks.str("accent"), looks.colors())
    }

    // Light or dark text by the dialog's own background (styleDialog), which custom colours can flip from the scheme's.
    /** A picker or form: [dialog] as a bottom sheet (see styleDialog). */
    fun sheet() = dialog(sheet = true)
    fun dialog(sheet: Boolean = false): AlertDialog.Builder = object : AlertDialog.Builder(this, (onColor(m3.surfaceHigh) == Color.WHITE).let { dark -> when {
        m3.housing -> if (dark) R.style.VulcanDialogApplianceDark else R.style.VulcanDialogAppliance
        else -> if (dark) R.style.VulcanDialogDark else R.style.VulcanDialog
    } }) {
        // Styled before the first frame: built (buttons included) without showing, so it never flashes unstyled.
        override fun create(): AlertDialog = super.create().also { d ->
            d.create(); styleDialog(d, sheet)
            // The dim is this window's, not each dialog's: a dialog opened from another (a menu item, a picker's next
            // step) then takes its place over a steady dim. With their own, the dim faded out and back in between them.
            d.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dialogs += d
            d.window?.decorView?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = dialogScrim(true)
                // Posted: a dialog opened from this one is already showing by then, but not attached yet.
                override fun onViewDetachedFromWindow(v: View) { root.post { dialogs.removeAll { !it.isShowing }; if (dialogs.isEmpty()) dialogScrim(false) } }
            })
        }
    }
    /** Open dialogs; full-screen ones built without [dialog] add and remove themselves. */
    val dialogs = mutableSetOf<android.app.Dialog>()

    /** Rotation or a theme change rebuilds the activity: its dialogs close with it, or their windows leak. */
    override fun onDestroy() {
        dialogs.toList().forEach { it.dismiss() }
        super.onDestroy()
    }
    private var scrim: View? = null

    /** The dim behind open dialogs, as the system's (styleDialog's lighter scrim), faded in and out. */
    protected fun dialogScrim(on: Boolean) {
        val v = scrim ?: View(this).apply { setBackgroundColor(0x52000000) }.also { scrim = it }
        if (on && v.parent !== root) { (v.parent as? ViewGroup)?.removeView(v); v.alpha = 0f; root.addView(v, FrameLayout.LayoutParams(-1, -1)) }
        v.animate().alpha(if (on) 1f else 0f).setDuration(150).withEndAction { if (!on) root.removeView(v) }.start()
    }
    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    fun toast(id: Int, vararg args: Any?) = toast(getString(id, *args))

    private fun computeNight(): Boolean {
        val sys = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val cal = Calendar.getInstance()
        if (looks.str("nightMode") == "sun") {
            val lat = looks.raw("lat")?.toDoubleOrNull()
            val lon = looks.raw("lon")?.toDoubleOrNull()
            val offset = cal.timeZone.getOffset(cal.timeInMillis) / 3_600_000.0
            val sun = if (lat != null && lon != null) sunTimes(lat, lon, cal.get(Calendar.DAY_OF_YEAR), offset) else null
            // Without a location yet, fall back to the manual schedule.
            if (sun != null) return isNightSun(cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0, sun)
            return isNight("schedule", cal.get(Calendar.HOUR_OF_DAY), looks.int("nightStart"), looks.int("nightEnd"), sys)
        }
        return isNight(looks.str("nightMode"), cal.get(Calendar.HOUR_OF_DAY), looks.int("nightStart"), looks.int("nightEnd"), sys)
    }
    fun pickApp(title: String, onPicked: (App) -> Unit) = home.run {
        val list = apps.values.sortedWith(compareBy(labelOrder()) { label(it) })
        this@VulcanActivity.pickList(title, list.map { icon(it) to if (isWork(it)) getString(R.string.work_app, label(it)) else label(it) }, appIcon = true) { onPicked(list[it]) }
    }

    /** [folder]: a folder's swipe, which may also open the folder itself (listed last, "folder"). [shortcut]: offer app shortcuts. */
    fun pickAction(title: String, folder: Boolean = false, shortcut: Boolean = true, onPicked: (String) -> Unit) {
        val extra = listOfNotNull<Triple<String, Int, () -> Unit>>(
            Triple("carousel", R.string.jump_to_page_item) {
                sheet().setTitle(R.string.page).setItems(Array(looks.pages) { getString(R.string.page_n, it + 1) }) { _, p -> onPicked("page:$p") }.show()
            },
            Triple("apps", R.string.open_app_item) { pickApp(getString(R.string.open_app)) { onPicked("app:${it.key}") } },
            if (shortcut) Triple("link", R.string.app_shortcut_item) { pickShortcut(onPicked) } else null,
            if (folder) Triple("folder", R.string.action_open_folder) { onPicked("folder") } else null,
        )
        val rows = ACTIONS.zip(ACTION_NAMES).map { (a, n) -> ACTION_SYMBOLS[a]?.let { symbol(it) } to getString(n) } +
            extra.map { symbol(it.first) to getString(it.second) }
        pickList(title, rows) { i -> if (i < ACTIONS.size) onPicked(ACTIONS[i]) else extra[i - ACTIONS.size].third() }
    }

    /** Waiting for the shortcut screen [pickShortcut] opened. */
    private var onShortcutMade: ((String) -> Unit)? = null

    /**
     * An app shortcut for an action: one the app lists ("New tab"), or one its shortcut screen makes ("Direct dial").
     * It's pinned, as home's are, so it keeps working. Android only lets the default home app open shortcuts.
     */
    fun pickShortcut(onPicked: (String) -> Unit) {
        if (!runCatching { home.launcherApps.hasShortcutHostPermission() }.getOrDefault(false)) { toast(R.string.shortcut_requires_home); return }
        pickApp(getString(R.string.app_shortcut)) { app ->
            val listed = home.shortcutsFor(app)
            val makers = packageManager.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_CREATE_SHORTCUT).setPackage(app.pkg), 0)
                .filter { it.activityInfo.exported }
            if (listed.isEmpty() && makers.isEmpty()) { toast(R.string.no_app_shortcuts, home.label(app)); return@pickApp }
            val rows = listed.map { home.shortcutIcon(it) to (it.shortLabel ?: "") } + makers.map { it.loadIcon(packageManager) to it.loadLabel(packageManager) }
            pickList(home.label(app), rows, appIcon = true) { i ->
                if (i < listed.size) listed[i].let { s ->
                    home.syncPins(s.`package`, app.serial, also = s.id)
                    onPicked(shortcutCode(s.`package`, s.id, app.serial))
                } else makers[i - listed.size].activityInfo.let { a ->
                    onShortcutMade = onPicked
                    runCatching { startActivityForResult(android.content.Intent(android.content.Intent.ACTION_CREATE_SHORTCUT)
                        .setComponent(android.content.ComponentName(a.packageName, a.name)), MainActivity.REQ_ACTION_SHORTCUT) }
                        .onFailure { onShortcutMade = null; toast(R.string.shortcut_unavailable) }
                }
            }
        }
    }

    /**
     * Takes the result of the shortcut screen [pickShortcut] opened; false for any other result. Accepting pins it.
     * ponytail: the waiting callback is lost if this activity is rebuilt meanwhile (rotation), so the pick is dropped.
     */
    fun shortcutMade(requestCode: Int, resultCode: Int, data: android.content.Intent?): Boolean {
        if (requestCode != MainActivity.REQ_ACTION_SHORTCUT) return false
        val done = onShortcutMade.also { onShortcutMade = null } ?: return true
        val req = data?.takeIf { resultCode == RESULT_OK }?.let { runCatching { home.launcherApps.getPinItemRequest(it) }.getOrNull() } ?: return true
        val s = req.shortcutInfo ?: return true
        // The default home app can change while the other app's screen is open.
        if (runCatching { req.accept() }.getOrDefault(false)) done(shortcutCode(s.`package`, s.id, home.um.getSerialNumberForUser(s.userHandle)))
        else toast(R.string.shortcut_requires_home)
        return true
    }

    /** True when night mode would change now (the hour passed a boundary, the system theme flipped). */
    fun nightChanged() = computeNight() != night
}
