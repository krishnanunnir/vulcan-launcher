package dev.vulcanlauncher

import android.content.Intent
import android.provider.Settings

/** Android's settings pages that drawer search can open, by title. */
private val SETTINGS_PAGES = listOf(R.string.page_wifi to Settings.ACTION_WIFI_SETTINGS, R.string.page_bluetooth to Settings.ACTION_BLUETOOTH_SETTINGS,
    R.string.page_network to Settings.ACTION_WIRELESS_SETTINGS, R.string.page_airplane to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
    R.string.page_display to Settings.ACTION_DISPLAY_SETTINGS, R.string.page_sound to Settings.ACTION_SOUND_SETTINGS,
    R.string.page_battery to Intent.ACTION_POWER_USAGE_SUMMARY, R.string.page_apps to Settings.ACTION_APPLICATION_SETTINGS,
    R.string.page_default_apps to Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, R.string.page_storage to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
    R.string.page_location to Settings.ACTION_LOCATION_SOURCE_SETTINGS, R.string.page_security to Settings.ACTION_SECURITY_SETTINGS,
    R.string.page_accessibility to Settings.ACTION_ACCESSIBILITY_SETTINGS, R.string.page_language to Settings.ACTION_LOCALE_SETTINGS,
    R.string.page_date to Settings.ACTION_DATE_SETTINGS, R.string.page_developer to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
    R.string.page_about to Settings.ACTION_DEVICE_INFO_SETTINGS)

/** What drawer search finds besides apps: contacts, a sum or a timer, app shortcuts and Android's settings pages. */
internal class DrawerSearch(private val a: MainActivity) {
    /** Up to four matching contacts, when contact search is on and the user granted access. */
    fun contacts(q: String): List<Drawer.Entry> {
        if (!a.looks.on("contactSearch") || !a.granted(android.Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = ArrayList<Drawer.Entry>()
        runCatching {
            a.contentResolver.query(android.net.Uri.withAppendedPath(android.provider.ContactsContract.Contacts.CONTENT_FILTER_URI, android.net.Uri.encode(q)),
                arrayOf("_id", "lookup", "display_name", "photo_thumb_uri"), null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < 4) out += Drawer.Entry.ContactE(c.getString(2) ?: "", c.getString(3),
                    android.provider.ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1)))
            }
        }
        return out
    }

    private fun glyph(symbol: String? = null, text: String? = null) = GlyphIcon(a.m3.primaryContainer, a.m3.onPrimaryContainer, text, symbol)

    /** A sum's answer (tap to copy it), or a timer or alarm to set, as the first result. */
    fun quick(q: String): List<Drawer.Entry> {
        calc(q)?.let { v ->
            val t = calcText(v)
            return listOf(Drawer.Entry.ActionE("= $t", glyph(text = "=")) {
                a.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText(t, t))
                // Android 13+ confirms a copy itself.
                if (android.os.Build.VERSION.SDK_INT < 33) a.toast(R.string.copied)
            })
        }
        fun clock(label: String, intent: Intent) = listOf(Drawer.Entry.ActionE(label, glyph("clock")) {
            runCatching { a.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { a.toast(R.string.no_clock_app) }
        })
        return when (val c = command(q)) {
            is Command.Timer -> clock(a.getString(R.string.search_timer, android.text.format.DateUtils.formatElapsedTime(c.seconds.toLong())),
                Intent(android.provider.AlarmClock.ACTION_SET_TIMER).putExtra(android.provider.AlarmClock.EXTRA_LENGTH, c.seconds))
            is Command.Alarm -> clock(a.getString(R.string.search_alarm, android.text.format.DateFormat.getTimeFormat(a).format(
                    java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, c.hour); set(java.util.Calendar.MINUTE, c.minute) }.time)),
                Intent(android.provider.AlarmClock.ACTION_SET_ALARM).putExtra(android.provider.AlarmClock.EXTRA_HOUR, c.hour)
                    .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, c.minute))
            null -> emptyList()
        }
    }

    /** Every app's shortcuts, read on the first search after the drawer opens and dropped when it closes. */
    private var shortcuts: List<android.content.pm.ShortcutInfo>? = null
    fun forget() { shortcuts = null }

    /** Up to four app shortcuts ("New tab", "Compose") whose names match, from apps the drawer shows. */
    fun shortcutHits(q: String, unlisted: Set<String>, iconPx: Int): List<Drawer.Entry> {
        if (q.length < 3) return emptyList()
        val shown = a.repo.apps.values.filter { it.key !in unlisted }.map { it.pkg to it.info.user }.toSet()
        val all = shortcuts ?: a.repo.allShortcuts().also { shortcuts = it }
        return all.filter { (it.`package` to it.userHandle) in shown }.map { it to matchScore(it.shortLabel?.toString() ?: "", q) }
            .filter { it.second >= 2 }.sortedByDescending { it.second }.take(4).map { (s, _) ->
                // Drawn once at cell size: a shortcut's own icon can be a 108 dp adaptive one.
                Drawer.Entry.ActionE(s.shortLabel.toString(), flatIcon(a, a.repo.shortcutIcon(s), iconPx)) { v -> a.startShortcut(s.`package`, s.id, s.userHandle, v) }
            }
    }

    /** Up to three of Android's settings pages ("Wi-Fi settings", "Battery settings") whose names match. */
    fun settingsHits(q: String): List<Drawer.Entry> {
        if (q.length < 3) return emptyList()
        return SETTINGS_PAGES.map { a.getString(it.first) to it.second }.filter { matchScore(it.first, q) >= 2 }
            .sortedByDescending { matchScore(it.first, q) }.take(3).map { (title, action) ->
                Drawer.Entry.ActionE(title, glyph("settings")) {
                    runCatching { a.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { a.toast(R.string.cant_open, title) }
                }
            }
    }
}
