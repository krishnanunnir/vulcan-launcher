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
    fun dialog(): AlertDialog.Builder = object : AlertDialog.Builder(this, (onColor(m3.surfaceHigh) == Color.WHITE).let { dark -> when {
        m3.housing -> if (dark) R.style.VulcanDialogApplianceDark else R.style.VulcanDialogAppliance
        else -> if (dark) R.style.VulcanDialogDark else R.style.VulcanDialog
    } }) {
        // Styled before the first frame: built (buttons included) without showing, so it never flashes unstyled.
        override fun create(): AlertDialog = super.create().also { d ->
            d.create(); styleDialog(d)
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
    protected val dialogs = mutableSetOf<AlertDialog>()
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

    fun pickAction(title: String, onPicked: (String) -> Unit) {
        val rows = ACTIONS.zip(ACTION_NAMES).map { (a, n) -> ACTION_SYMBOLS[a]?.let { symbol(it) } to getString(n) } +
            listOf(symbol("carousel") to getString(R.string.jump_to_page_item), symbol("apps") to getString(R.string.open_app_item))
        pickList(title, rows) { i ->
            when (i) {
                ACTIONS.size -> dialog().setTitle(R.string.page).setItems(Array(looks.pages) { getString(R.string.page_n, it + 1) }) { _, p -> onPicked("page:$p") }.show()
                ACTIONS.size + 1 -> pickApp(getString(R.string.open_app)) { onPicked("app:${it.key}") }
                else -> onPicked(ACTIONS[i])
            }
        }
    }

    /** True when night mode would change now (the hour passed a boundary, the system theme flipped). */
    fun nightChanged() = computeNight() != night
}
