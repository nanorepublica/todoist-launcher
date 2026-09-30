package uk.co.softwarecrafts.contextlauncher.data.todoist

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uk.co.softwarecrafts.contextlauncher.core.Clock
import uk.co.softwarecrafts.contextlauncher.core.config.LauncherConfig
import uk.co.softwarecrafts.contextlauncher.core.config.StageTrigger
import uk.co.softwarecrafts.contextlauncher.core.log.EventType
import uk.co.softwarecrafts.contextlauncher.core.log.LogEvent
import uk.co.softwarecrafts.contextlauncher.core.stage.TaskSnapshot
import uk.co.softwarecrafts.contextlauncher.core.todoist.CommandDto
import uk.co.softwarecrafts.contextlauncher.core.todoist.ItemDto
import uk.co.softwarecrafts.contextlauncher.core.todoist.TaskCache
import uk.co.softwarecrafts.contextlauncher.core.todoist.TodoistJson
import uk.co.softwarecrafts.contextlauncher.core.todoist.TodoistMapper
import uk.co.softwarecrafts.contextlauncher.data.ConfigRepository
import uk.co.softwarecrafts.contextlauncher.data.EventLogRepository
import uk.co.softwarecrafts.contextlauncher.data.db.SyncStateEntity
import uk.co.softwarecrafts.contextlauncher.data.db.TaskDao
import uk.co.softwarecrafts.contextlauncher.data.db.TaskEntity
import uk.co.softwarecrafts.contextlauncher.engine.TaskSource
import java.time.Duration
import java.time.Instant
import java.util.UUID

sealed class SyncStatus {
    object NoToken : SyncStatus()
    object Syncing : SyncStatus()
    data class Ok(val at: Instant, val taskCount: Int) : SyncStatus()
    data class Failed(val at: Instant, val message: String) : SyncStatus()
}

/**
 * Local copy of Todoist tasks plus the calls that change them. Offline, the
 * last synced state stays available; every write is applied locally first.
 */
class TodoistRepository(
    private val api: TodoistApi,
    private val tokens: TokenStore,
    private val dao: TaskDao,
    private val config: ConfigRepository,
    private val eventLog: EventLogRepository,
    private val clock: Clock,
    /** Called after the local cache changes so the stage engine can re-resolve. */
    private val onTasksChanged: () -> Unit = {},
) : TaskSource {

    private val syncLock = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.NoToken)
    val status: StateFlow<SyncStatus> = _status

    val hasToken: Boolean get() = tokens.hasToken

    fun saveToken(token: String?) {
        tokens.token = token
        if (token == null) _status.value = SyncStatus.NoToken
    }

    override suspend fun snapshot(): List<TaskSnapshot> =
        dao.all().map { TodoistMapper.toSnapshot(TodoistJson.decodeItem(it.json), clock.zone) }

    fun snapshotFlow(): Flow<List<TaskSnapshot>> =
        dao.observe().map { rows -> rows.map { TodoistMapper.toSnapshot(TodoistJson.decodeItem(it.json), clock.zone) } }

    suspend fun lastSyncAt(): Instant? = dao.state(KEY_LAST_SYNC)?.toLongOrNull()?.let(Instant::ofEpochMilli)

    /** Syncs unless one finished less than [minAge] ago. */
    suspend fun syncIfStale(minAge: Duration = Duration.ofSeconds(60)): SyncStatus {
        val last = lastSyncAt()
        if (last != null && Duration.between(last, clock.now()) < minAge) return _status.value
        return sync()
    }

    /** Incremental sync (full on first run or when [forceFull]). Never throws; see [status]. */
    suspend fun sync(forceFull: Boolean = false): SyncStatus = syncLock.withLock {
        val token = tokens.token ?: return SyncStatus.NoToken.also { _status.value = it }
        _status.value = SyncStatus.Syncing
        try {
            val syncToken = if (forceFull) "*" else dao.state(KEY_SYNC_TOKEN) ?: "*"
            val response = api.sync(token, syncToken)
            var merged = TaskCache.merge(currentItems(), response)
            if (response.fullSync) {
                val dayStart = clock.today().atStartOfDay(clock.zone).toInstant()
                val completed = runCatching { api.completedBetween(token, dayStart, clock.now()) }
                    .onFailure { Log.w(TAG, "completed-today fetch failed", it) }
                    .getOrDefault(emptyList())
                merged = TaskCache.addCompleted(merged, completed)
                ensureLabels(token, response.labels.map { it.name }.toSet())
            }
            replaceAll(merged)
            dao.putState(SyncStateEntity(KEY_SYNC_TOKEN, response.syncToken))
            dao.putState(SyncStateEntity(KEY_LAST_SYNC, clock.now().toEpochMilli().toString()))
            SyncStatus.Ok(clock.now(), merged.size).also { _status.value = it }
        } catch (e: Exception) {
            Log.w(TAG, "sync failed", e)
            SyncStatus.Failed(clock.now(), e.message ?: e.javaClass.simpleName).also { _status.value = it }
        } finally {
            onTasksChanged()
        }
    }

    /**
     * Completes a task: local first, then `item_close` (which rolls recurring
     * tasks forward). On failure the local change is reverted. Throws on failure.
     */
    suspend fun complete(taskId: String) {
        val token = tokens.token ?: throw IllegalStateException("No Todoist token")
        val before = currentItems()
        val item = before[taskId] ?: throw IllegalArgumentException("Unknown task $taskId")
        val now = clock.now()
        replaceAll(TaskCache.markCompleted(before, taskId, now.toString()))
        onTasksChanged()
        try {
            val uuid = UUID.randomUUID().toString()
            val response = api.sync(token, dao.state(KEY_SYNC_TOKEN) ?: "*", listOf(CommandDto("item_close", uuid, mapOf("id" to taskId))))
            val result = response.syncStatus[uuid]?.toString()
            if (result != null && result != "\"ok\"") throw TodoistApiException(400, result)
            eventLog.record(LogEvent(at = now, type = EventType.TASK_COMPLETED, detail = "${item.id} ${item.content.take(60)}"))
            // The response carries the closed item (and a rolled-forward copy for recurring tasks)
            replaceAll(TaskCache.merge(currentItems(), response))
            dao.putState(SyncStateEntity(KEY_SYNC_TOKEN, response.syncToken))
        } catch (e: Exception) {
            replaceAll(before)
            throw e
        } finally {
            onTasksChanged()
        }
    }

    /** Creates a task from natural language ("Buy milk tomorrow 9am @errand"). */
    suspend fun quickAdd(text: String): ItemDto {
        val token = tokens.token ?: throw IllegalStateException("No Todoist token")
        val item = api.quickAdd(token, text)
        replaceAll(currentItems() + (item.id to item))
        onTasksChanged()
        return item
    }

    /** Every label the config refers to must exist in Todoist, or tasks cannot carry it. */
    private suspend fun ensureLabels(token: String, existing: Set<String>) {
        val needed = labelsIn(config.load())
        for (label in needed - existing) {
            runCatching { api.addLabel(token, label) }
                .onSuccess { eventLog.record(LogEvent(at = clock.now(), type = EventType.CONFIG_CHANGE, detail = "created Todoist label $label")) }
                .onFailure { Log.w(TAG, "could not create label $label", it) }
        }
    }

    private suspend fun currentItems(): Map<String, ItemDto> =
        dao.all().associate { it.id to TodoistJson.decodeItem(it.json) }

    private suspend fun replaceAll(items: Map<String, ItemDto>) {
        dao.replaceAll(items.values.map { item ->
            val due = TodoistMapper.parseDue(item.due, clock.zone)
            TaskEntity(
                id = item.id,
                json = TodoistJson.json.encodeToString(ItemDto.serializer(), item),
                dueDate = due?.date?.toString(),
                completed = item.checked,
            )
        })
    }

    companion object {
        private const val TAG = "TodoistRepository"
        private const val KEY_SYNC_TOKEN = "sync_token"
        private const val KEY_LAST_SYNC = "last_sync_millis"

        fun labelsIn(config: LauncherConfig): Set<String> {
            val labels = mutableSetOf<String>()
            config.stages.forEach { stage ->
                stage.doneLabel?.let { labels += it }
                when (val t = stage.trigger) {
                    is StageTrigger.TasksDone -> labels += t.label
                    is StageTrigger.TaskDue -> labels += t.label
                    else -> Unit
                }
            }
            config.labelGroups.forEach { labels += it.label }
            return labels
        }
    }
}
