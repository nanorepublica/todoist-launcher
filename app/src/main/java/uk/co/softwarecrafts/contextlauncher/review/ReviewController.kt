package uk.co.softwarecrafts.contextlauncher.review

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.co.softwarecrafts.contextlauncher.Graph
import uk.co.softwarecrafts.contextlauncher.core.config.LauncherConfig
import uk.co.softwarecrafts.contextlauncher.core.log.EventType
import uk.co.softwarecrafts.contextlauncher.core.log.LogEvent
import uk.co.softwarecrafts.contextlauncher.core.review.ReviewBuilder
import uk.co.softwarecrafts.contextlauncher.core.review.ReviewReport
import uk.co.softwarecrafts.contextlauncher.core.review.Suggestion
import uk.co.softwarecrafts.contextlauncher.core.review.SuggestionRules
import uk.co.softwarecrafts.contextlauncher.core.review.UsageExport
import uk.co.softwarecrafts.contextlauncher.core.stage.Activation
import uk.co.softwarecrafts.contextlauncher.engine.StageState
import uk.co.softwarecrafts.contextlauncher.usage.UsageReader
import java.time.Duration
import java.time.Instant

/**
 * Glue for the weekly review: gathers the last seven days of log events and
 * usage intervals into a core [ReviewReport], turns it into suggestions,
 * applies one, exports JSON and completes the review task.
 */
class ReviewController(context: Context) {
    private val ctx = context.applicationContext
    private val labels = mutableMapOf<String, String>()

    class Loaded(val report: ReviewReport, val events: List<LogEvent>, val suggestions: List<Suggestion>, val config: LauncherConfig)

    fun label(packageName: String): String = labels.getOrPut(packageName) {
        runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))).toString()
        }.getOrDefault(packageName)
    }

    fun hasUsageAccess(): Boolean = UsageReader(ctx).hasPermission()

    suspend fun load(): Loaded = withContext(Dispatchers.IO) {
        val end = Graph.clock.now()
        val start = end.minus(PERIOD)
        val events = Graph.eventLog(ctx).between(start.minus(Duration.ofDays(1)), end) // one extra day so the timeline knows the opening stage
        val intervals = UsageReader(ctx).intervals(start, end)
        val report = ReviewBuilder.build(events, intervals, start, end)
        val config = Graph.config(ctx).load()
        Loaded(report, events, SuggestionRules.generate(config, report, ::label), config)
    }

    /** Applies the change, logs it and lets the engine pick the new config up. Returns validation problems, if any. */
    suspend fun apply(suggestion: Suggestion): List<String> = withContext(Dispatchers.IO) {
        val problems = Graph.config(ctx).update { suggestion.change.apply(it) }
        if (problems.isEmpty()) {
            Graph.eventLog(ctx).record(LogEvent(at = Graph.clock.now(), type = EventType.CONFIG_CHANGE, detail = "review: ${suggestion.text}"))
            Graph.stageEngine(ctx).refresh()
        }
        problems
    }

    fun exportJson(loaded: Loaded): String = UsageExport.encode(loaded.report, loaded.events, ::label)

    /** The due review task while the review stage is active, else null. */
    fun dueReviewTaskId(): String? =
        ((Graph.stageEngine(ctx).state.value as? StageState.Ready)?.resolution?.current as? Activation.TaskDue)?.taskId

    /** Logs the review as done and completes the @phone/review task when one is due. */
    suspend fun finish(applied: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        val taskId = dueReviewTaskId()
        Graph.eventLog(ctx).record(LogEvent(at = Graph.clock.now(), type = EventType.REVIEW_COMPLETED, detail = "applied $applied" + (taskId?.let { ", task $it" } ?: "")))
        if (taskId == null) Result.success(false)
        else runCatching { Graph.todoist(ctx).complete(taskId); true }
    }

    companion object {
        val PERIOD: Duration = Duration.ofDays(7)
    }
}
