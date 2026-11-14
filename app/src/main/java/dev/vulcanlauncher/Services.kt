package dev.vulcanlauncher

import android.accessibilityservice.AccessibilityService
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent

/** Notification access (the user turns it on): keeps the active notifications for badges and the long-press menu. */
class BadgeService : NotificationListenerService() {
    companion object {
        var instance: BadgeService? = null
        /** Package → its notifications that badge (see [badges]). */
        var byPackage: Map<String, List<StatusBarNotification>> = emptyMap()
        /** Called with the packages whose notifications changed. */
        var onChange: (Set<String>) -> Unit = {}
    }

    override fun onListenerConnected() { instance = this; refresh() }
    override fun onListenerDisconnected() { instance = null; val old = byPackage.keys; byPackage = emptyMap(); onChange(old) }
    override fun onNotificationPosted(sbn: StatusBarNotification) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification) = refresh()

    override fun onNotificationRankingUpdate(rankingMap: RankingMap) = refresh()

    private val ranking = Ranking()

    /**
     * Launcher3's rule: ongoing notifications badge too (Sunup's foreground service, #82), except on the legacy
     * "Miscellaneous" channel; the channel's "Show dot" switch, group summaries and empty notifications don't.
     */
    private fun badges(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (n.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (currentRanking?.getRanking(sbn.key, ranking) == true) {
            if (!ranking.canShowBadge()) return false
            if (ranking.channel?.id == android.app.NotificationChannel.DEFAULT_CHANNEL_ID && !sbn.isClearable) return false
        } else if (!sbn.isClearable) return false
        return !(n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).isNullOrEmpty()
            && n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).isNullOrEmpty())
    }

    private fun refresh() {
        val old = byPackage
        byPackage = runCatching { activeNotifications.orEmpty() }.getOrDefault(emptyArray())
            .filter(::badges)
            .groupBy { it.packageName }
        // As Launcher3's notification dots: only the packages that changed get redrawn.
        fun ids(m: Map<String, List<StatusBarNotification>>, p: String) = m[p]?.map { it.key to it.postTime }
        onChange((old.keys + byPackage.keys).filterTo(HashSet()) { ids(old, it) != ids(byPackage, it) })
    }
}

/** Accessibility service (the user turns it on): only used to perform global actions such as screen lock and recents. */
class ActionService : AccessibilityService() {
    companion object { var instance: ActionService? = null }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
