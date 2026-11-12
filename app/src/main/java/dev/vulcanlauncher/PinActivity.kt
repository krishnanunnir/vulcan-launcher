package dev.vulcanlauncher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserManager
import android.widget.Toast
import kotlin.math.ceil

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
    val item = when (req.requestType) {
        LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT -> {
            val s = req.shortcutInfo ?: return false
            if (!req.accept()) return false
            Item(0, 0, 0, sub, sub, pkg = s.`package`, shortcutId = s.id, user = serial(s.userHandle), label = s.shortLabel?.toString() ?: "")
        }
        LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET -> {
            val info = req.getAppWidgetProviderInfo(activity) ?: return false
            val id = AppWidgetHost(activity, MainActivity.HOST_ID).allocateAppWidgetId()
            if (!req.accept(Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, id) })) return false
            // ponytail: cell size estimated from the screen width, exact once the widget is resized
            val cell = activity.resources.displayMetrics.widthPixels / looks.cols.toFloat()
            val t = info.targetCells()
            val w = (t?.first ?: ceil(info.minWidth / cell).toInt()).coerceIn(1, looks.cols) * sub
            val h = (t?.second ?: ceil(info.minHeight / cell).toInt()).coerceIn(1, looks.rows) * sub
            Item(0, 0, 0, w, h, widgetId = id)
        }
        else -> return false
    }
    val spot = firstFreeSpot(items, looks.pages, looks.cols * sub, looks.rows * sub, item.w, item.h)
    if (spot == null) {
        Toast.makeText(activity, R.string.no_room, Toast.LENGTH_SHORT).show()
        return false
    }
    items += item.copy(page = spot.page, x = spot.x, y = spot.y)
    store.save(items, sub)
    Toast.makeText(activity, R.string.added_to_home, Toast.LENGTH_SHORT).show()
    return true
}
