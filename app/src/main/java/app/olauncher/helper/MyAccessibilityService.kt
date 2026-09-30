package app.olauncher.helper

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.olauncher.R
import app.olauncher.data.Prefs
import uk.co.softwarecrafts.contextlauncher.Graph

/**
 * Two jobs: Olauncher's double-tap-to-lock (a click on the hidden lock view)
 * and Context launcher's "time's up" (window changes tell the gate which app
 * is in front; the gate calls back to send the phone home).
 */
class MyAccessibilityService : AccessibilityService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onServiceConnected() {
        Prefs(applicationContext).lockModeOn = true
        Graph.gate(applicationContext).homeAction = { performGlobalAction(GLOBAL_ACTION_HOME) }
        super.onServiceConnected()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Graph.gate(applicationContext).homeAction = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val pkg = event.packageName?.toString() ?: return
                    if (pkg in IGNORED_PACKAGES) return
                    Graph.gate(applicationContext).onForeground(pkg)
                }

                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    val source: AccessibilityNodeInfo = event.source ?: return
                    if (source.className != "android.widget.FrameLayout") return
                    if (source.contentDescription == getString(R.string.lock_layout_description))
                        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
            }
        } catch (e: Exception) {
            return
        }
    }

    override fun onInterrupt() {
    }

    private companion object {
        /** Overlays that do not mean the session app left the front. */
        val IGNORED_PACKAGES = setOf("com.android.systemui", "android")
    }
}
