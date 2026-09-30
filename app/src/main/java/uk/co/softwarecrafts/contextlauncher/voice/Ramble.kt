package uk.co.softwarecrafts.contextlauncher.voice

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Process

/**
 * Finds and starts Todoist's Ramble app shortcut (voice capture in listening
 * mode). Shortcut queries only work for the default launcher, which this app
 * is once set up.
 */
object Ramble {
    const val TODOIST_PACKAGE = "com.todoist"

    data class Found(val id: String, val label: String)

    /** Every shortcut Todoist publishes, for the Setup diagnostics. */
    fun todoistShortcuts(context: Context): List<Found> {
        val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        if (!launcher.hasShortcutHostPermission()) return emptyList()
        val query = LauncherApps.ShortcutQuery().apply {
            setPackage(TODOIST_PACKAGE)
            setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
            )
        }
        return try {
            launcher.getShortcuts(query, Process.myUserHandle()).orEmpty().map { it.toFound() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun findRamble(context: Context): Found? =
        todoistShortcuts(context).firstOrNull { it.id.contains("ramble", true) || it.label.contains("ramble", true) }

    /** Starts Ramble; returns false when the shortcut is missing or could not be started. */
    fun start(context: Context): Boolean {
        val shortcut = findRamble(context) ?: return false
        val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        return try {
            launcher.startShortcut(TODOIST_PACKAGE, shortcut.id, null, null, Process.myUserHandle())
            true
        } catch (e: Exception) {
            false
        }
    }

    fun openTodoist(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(TODOIST_PACKAGE) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private fun ShortcutInfo.toFound() = Found(id, (shortLabel ?: longLabel ?: id).toString())
}
