package uk.co.softwarecrafts.contextlauncher.gate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import uk.co.softwarecrafts.contextlauncher.core.Clock
import uk.co.softwarecrafts.contextlauncher.core.gate.AllowedEntry
import uk.co.softwarecrafts.contextlauncher.core.gate.BypassCounter
import uk.co.softwarecrafts.contextlauncher.core.gate.Decision
import uk.co.softwarecrafts.contextlauncher.core.gate.EnforcementAction
import uk.co.softwarecrafts.contextlauncher.core.gate.Gatekeeper
import uk.co.softwarecrafts.contextlauncher.core.gate.SessionKind
import uk.co.softwarecrafts.contextlauncher.core.gate.SessionRules
import uk.co.softwarecrafts.contextlauncher.core.gate.TimedSession
import uk.co.softwarecrafts.contextlauncher.core.log.EventType
import uk.co.softwarecrafts.contextlauncher.core.log.LogEvent
import uk.co.softwarecrafts.contextlauncher.core.stage.Resolution
import uk.co.softwarecrafts.contextlauncher.data.AppPrefs
import uk.co.softwarecrafts.contextlauncher.data.ConfigRepository
import uk.co.softwarecrafts.contextlauncher.data.EventLogRepository
import uk.co.softwarecrafts.contextlauncher.engine.StageEngine
import uk.co.softwarecrafts.contextlauncher.engine.StageState
import java.time.Duration

/**
 * Launch decisions, the one running timed session, the per-block bypass
 * counter and "time's up" enforcement. The accessibility service reports
 * foreground changes and supplies the go-home action; without it, a
 * notification is the fallback and the session is closed on the next resume.
 */
class GateController(
    context: Context,
    private val clock: Clock,
    private val config: ConfigRepository,
    private val engine: StageEngine,
    private val eventLog: EventLogRepository,
    private val prefs: AppPrefs,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _session = MutableStateFlow(prefs.loadSession())
    val session: StateFlow<TimedSession?> = _session

    /** Set by the accessibility service while it is connected. Returns true when it acted. */
    @Volatile var homeAction: (() -> Boolean)? = null
    @Volatile var lastForeground: String? = null
    private var deadlineJob: Job? = null

    /** Packages of the default dialer and camera, always allowed outside every cap. */
    val phoneAndCamera: Set<String> by lazy {
        val pm = appContext.packageManager
        listOfNotNull(
            pm.resolveActivity(Intent(Intent.ACTION_DIAL), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName,
            pm.resolveActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName,
        ).filter { it != "android" }.toSet()
    }

    init {
        _session.value?.let { scheduleDeadline(it) }
    }

    private fun resolution(): Resolution? = (engine.state.value as? StageState.Ready)?.resolution

    suspend fun allowed(): List<AllowedEntry> {
        val resolution = resolution() ?: return emptyList()
        return Gatekeeper.allowed(config.load(), resolution, phoneAndCamera)
    }

    /** Fails open: before setup is complete nothing is gated. */
    suspend fun decide(packageName: String): Decision {
        val resolution = resolution() ?: return Decision.Allowed
        return Gatekeeper.decide(packageName, Gatekeeper.allowed(config.load(), resolution, phoneAndCamera), resolution)
    }

    /** The number the next bypass in the current block would get (1 = no wait). */
    fun nextBypassNumber(): Int {
        val key = currentBlockKey() ?: return 1
        return prefs.loadBypassCounter().forBlock(key).count + 1
    }

    suspend fun startBypass(packageName: String, label: String, reason: String?, minutes: Int): TimedSession {
        val resolution = resolution()
        val key = currentBlockKey() ?: "none"
        val (number, counter) = prefs.loadBypassCounter().forBlock(key).next()
        prefs.saveBypassCounter(counter)
        val now = clock.now()
        val session = TimedSession.start(packageName, resolution?.stage?.id ?: "none", SessionKind.BYPASS, now, minutes, number, reason?.takeIf { it.isNotBlank() })
        eventLog.record(LogEvent(
            at = now, type = EventType.BYPASS, stageId = session.stageId, packageName = packageName,
            reason = session.reason, limitMinutes = minutes, bypassNumber = number, detail = label,
        ))
        begin(session)
        return session
    }

    suspend fun startCapped(packageName: String, label: String, minutes: Int): TimedSession {
        val now = clock.now()
        val session = TimedSession.start(packageName, resolution()?.stage?.id ?: "none", SessionKind.CAPPED, now, minutes)
        eventLog.record(LogEvent(at = now, type = EventType.CAPPED_OPEN, stageId = session.stageId, packageName = packageName, limitMinutes = minutes, detail = label))
        begin(session)
        return session
    }

    private fun begin(session: TimedSession) {
        _session.value = session
        prefs.saveSession(session)
        scheduleDeadline(session)
    }

    /** Accessibility service: an app came to the front. */
    fun onForeground(packageName: String) {
        lastForeground = packageName
        enforce()
    }

    /** The launcher itself is in front again. */
    fun onLauncherResumed() {
        lastForeground = appContext.packageName
        val session = _session.value ?: return
        if (session.isExpired(clock.now())) endSession(timesUp = true)
    }

    fun endSession(timesUp: Boolean = false) {
        val session = _session.value ?: return
        deadlineJob?.cancel()
        _session.value = null
        prefs.saveSession(null)
        if (timesUp) scope.launch {
            eventLog.record(LogEvent(at = clock.now(), type = EventType.TIMES_UP, stageId = session.stageId, packageName = session.packageName, limitMinutes = session.limitMinutes))
        }
        cancelNotification()
    }

    private fun scheduleDeadline(session: TimedSession) {
        deadlineJob?.cancel()
        val wait = Duration.between(clock.now(), session.endsAt).toMillis().coerceAtLeast(0)
        deadlineJob = scope.launch {
            delay(wait + 250)
            enforce()
        }
    }

    private fun enforce() {
        val session = _session.value ?: return
        when (SessionRules.action(session, lastForeground, clock.now())) {
            EnforcementAction.NOTHING -> Unit
            EnforcementAction.END_SESSION -> endSession(timesUp = true)
            EnforcementAction.RETURN_HOME -> {
                val sentHome = homeAction?.invoke() ?: false
                if (!sentHome) notifyTimesUp(session)
                endSession(timesUp = true)
            }
        }
    }

    /** Fallback when the accessibility service is off: a notification that opens the launcher. */
    private fun notifyTimesUp(session: TimedSession) {
        try {
            val manager = appContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Time's up", NotificationManager.IMPORTANCE_HIGH))
            val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending = PendingIntent.getActivity(appContext, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(appContext, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Time's up")
                .setContentText("${session.limitMinutes} min limit reached. Tap to return to the launcher.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "time's up notification failed", e)
        }
    }

    private fun cancelNotification() {
        runCatching { appContext.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
    }

    private fun currentBlockKey(): String? {
        val resolution = resolution() ?: return null
        return BypassCounter.blockKey(resolution.stage.id, resolution.current.since.toEpochMilli())
    }

    companion object {
        private const val TAG = "GateController"
        private const val CHANNEL = "times_up"
        private const val NOTIFICATION_ID = 4101
    }
}
