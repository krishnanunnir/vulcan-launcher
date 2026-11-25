package dev.vulcanlauncher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserManager
import android.widget.Toast

/** Accepts "add to home screen" requests from other apps (pinned shortcuts and widgets) and places them on the first free spot. */
class PinActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val req = applicationContext.getSystemService(LauncherApps::class.java).getPinItemRequest(intent)
        if (req != null && req.isValid) addPinned(this, req)
        finish()
    }
}

/** Shared with the "Add shortcut" flow in MainActivity. Returns true when something was added. */
fun addPinned(activity: Activity, req: LauncherApps.PinItemRequest): Boolean {
    val store = Store(activity)
    val looks = Looks(activity)
    val sub = looks.sub
    val items = store.load(sub)
    val serial = { h: android.os.UserHandle -> activity.getSystemService(UserManager::class.java).getSerialNumberForUser(h) }
    val (w, h) = when (req.requestType) {
        LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT -> sub to sub
        LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET -> {
            val info = req.getAppWidgetProviderInfo(activity) ?: return false
            // ponytail: square sub-cells estimated from the screen width (no page margins), exact once the widget is resized
            val cell = activity.resources.displayMetrics.widthPixels / (looks.cols * sub).toFloat()
            widgetSpan(info.targetCells(), info.minWidth, info.minHeight, cell, cell, looks.cols * sub, looks.rows * sub, sub)
        }
        else -> return false
    }
    // Room first: accepting tells the other app it was added, and Android pins it, whether or not it then fits.
    val spot = firstFreeSpot(items, looks.pages, looks.cols * sub, looks.rows * sub, w, h)
    if (spot == null) {
        Toast.makeText(activity, R.string.no_room, Toast.LENGTH_SHORT).show()
        return false
    }
    items += if (req.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) {
        val s = req.shortcutInfo ?: return false
        if (!req.accept()) return false
        Item(spot.page, spot.x, spot.y, w, h, pkg = s.`package`, shortcutId = s.id, user = serial(s.userHandle), label = s.shortLabel?.toString() ?: "")
    } else {
        val host = AppWidgetHost(activity, MainActivity.HOST_ID)
        val id = host.allocateAppWidgetId()
        if (!req.accept(Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, id) })) { host.deleteAppWidgetId(id); return false }
        Item(spot.page, spot.x, spot.y, w, h, widgetId = id)
    }
    store.save(items, sub)
    Toast.makeText(activity, R.string.added_to_home, Toast.LENGTH_SHORT).show()
    return true
}
