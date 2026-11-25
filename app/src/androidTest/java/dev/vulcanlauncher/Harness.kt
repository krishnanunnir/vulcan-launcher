package dev.vulcanlauncher

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import java.io.File

// Helpers shared by the issue audits (IssueReproCheck, OpenIssueAuditCheck).

/** [v] and every view under it. */
internal fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

/** A private field of [o] or its superclasses, for state the app doesn't expose. */
internal fun field(o: Any, name: String): Any? {
    var c: Class<*>? = o.javaClass
    while (c != null) {
        try { return c.getDeclaredField(name).apply { isAccessible = true }.get(o) } catch (_: NoSuchFieldException) { c = c.superclass }
    }
    error("No field $name")
}

internal fun showingDialogs(v: VulcanActivity) = v.dialogs.filterIsInstance<AlertDialog>().filter { it.isShowing }

/**
 * The issue audits' copy of the user's setup, written to disk before they touch it (like DeviceCheck's devicecheck-backup):
 * a crash or a killed process skips their finally, and the next run then restores this copy instead of backing up the
 * audit's leftovers. Call [dropAuditBackup] only once the restore succeeded.
 */
internal fun auditBackup(a: MainActivity, note: (String) -> Unit): String {
    val f = File(a.filesDir, "issue-audit-backup.json")
    if (f.exists()) note("Restoring the setup an earlier run left behind")
    else {
        // Written aside and renamed, so a crash mid-write never leaves half a backup to restore.
        val tmp = File(a.filesDir, "issue-audit-backup.tmp").apply { writeText(a.store.export(a.repo.apps.values.toList())) }
        check(tmp.renameTo(f)) { "Couldn't save the backup" }
    }
    return f.readText()
}

internal fun dropAuditBackup(a: MainActivity) = File(a.filesDir, "issue-audit-backup.json").delete()
