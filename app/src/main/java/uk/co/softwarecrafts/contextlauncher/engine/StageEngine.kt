package uk.co.softwarecrafts.contextlauncher.engine

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uk.co.softwarecrafts.contextlauncher.calendar.CalendarStore
import uk.co.softwarecrafts.contextlauncher.core.Clock
import uk.co.softwarecrafts.contextlauncher.core.log.EventType
import uk.co.softwarecrafts.contextlauncher.core.log.LogEvent
import uk.co.softwarecrafts.contextlauncher.core.stage.Resolution
import uk.co.softwarecrafts.contextlauncher.core.stage.StageResolver
import uk.co.softwarecrafts.contextlauncher.core.stage.StageTracker
import uk.co.softwarecrafts.contextlauncher.core.stage.TaskSnapshot
import uk.co.softwarecrafts.contextlauncher.data.ConfigRepository
import uk.co.softwarecrafts.contextlauncher.data.EventLogRepository
import java.time.Duration

/** Where task snapshots come from. Phase 3 plugs Todoist in; until then there are none. */
fun interface TaskSource {
    suspend fun snapshot(): List<TaskSnapshot>
}

/** What the home screen shows. */
sealed class StageState {
    object Loading : StageState()
    /** Calendar access is missing or no stage calendar is configured. */
    data class SetupNeeded(val reason: String) : StageState()
    data class Ready(val resolution: Resolution) : StageState()
}

/**
 * Keeps the current [Resolution] up to date: re-resolves when asked (launcher
 * resumed), when the calendar provider changes, and at the next moment the
 * resolver says the answer may change.
 */
class StageEngine(
    context: Context,
    private val clock: Clock,
    private val config: ConfigRepository,
    private val eventLog: EventLogRepository,
    private val calendar: CalendarStore,
    var taskSource: TaskSource = TaskSource { emptyList() },
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resolver = StageResolver(clock)
    private val tracker = StageTracker()
    private val lock = Mutex()
    private var scheduled: Job? = null

    private val _state = MutableStateFlow<StageState>(StageState.Loading)
    val state: StateFlow<StageState> = _state
    val zone: java.time.ZoneId get() = clock.zone

    init {
        appContext.contentResolver.registerContentObserver(
            CalendarContract.CONTENT_URI, true,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = refresh()
            },
        )
    }

    fun refresh() {
        scope.launch { resolveNow() }
    }

    private suspend fun resolveNow() = lock.withLock {
        config.seedIfEmpty()
        val cfg = config.load()
        if (!calendar.hasPermission()) {
            _state.value = StageState.SetupNeeded("Calendar access needed")
            return
        }
        val calendarName = cfg.settings.calendarName
        val calendarInfo = calendarName?.let { calendar.findByName(it) }
        if (calendarInfo == null) {
            _state.value = StageState.SetupNeeded(if (calendarName == null) "Choose a stage calendar" else "Calendar \"$calendarName\" not found")
            return
        }
        val now = clock.now()
        val events = calendar.instances(calendarInfo.id, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)))
        val tasks = runCatching { taskSource.snapshot() }.getOrDefault(emptyList())
        val resolution = try {
            resolver.resolve(cfg, events, tasks)
        } catch (e: IllegalStateException) {
            _state.value = StageState.SetupNeeded(e.message ?: "No default stage")
            return
        }
        tracker.update(resolution, now)?.let { change ->
            eventLog.record(LogEvent(
                at = now, type = EventType.STAGE_CHANGE, stageId = change.to.stageId,
                detail = change.from?.stageId?.let { "from $it" } ?: "start",
            ))
        }
        _state.value = StageState.Ready(resolution)
        scheduleNext(resolution)
    }

    private fun scheduleNext(resolution: Resolution) {
        scheduled?.cancel()
        val at = resolution.nextChangeAt ?: return
        val wait = Duration.between(clock.now(), at).toMillis().coerceAtLeast(1_000L)
        scheduled = scope.launch {
            delay(wait)
            resolveNow()
        }
    }
}
