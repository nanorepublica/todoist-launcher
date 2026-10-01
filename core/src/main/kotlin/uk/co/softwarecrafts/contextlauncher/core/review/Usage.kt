package uk.co.softwarecrafts.contextlauncher.core.review

import uk.co.softwarecrafts.contextlauncher.core.log.EventType
import uk.co.softwarecrafts.contextlauncher.core.log.LogEvent
import java.time.Duration
import java.time.Instant

/** One stretch of an app being in the foreground (from UsageStatsManager events). */
data class ForegroundInterval(val packageName: String, val start: Instant, val end: Instant)

/** A stretch of time the phone was in one stage, rebuilt from STAGE_CHANGE events. */
data class StageSegment(val stageId: String, val start: Instant, val end: Instant)

object StageTimeline {
    /**
     * Turns STAGE_CHANGE events into contiguous segments covering [periodStart, periodEnd).
     * Time before the first change inside the period is attributed to the stage that
     * change says it came from ("from X" detail), or "unknown".
     */
    fun build(events: List<LogEvent>, periodStart: Instant, periodEnd: Instant): List<StageSegment> {
        val changes = events.filter { it.type == EventType.STAGE_CHANGE && it.stageId != null }
            .filter { !it.at.isBefore(periodStart) && it.at.isBefore(periodEnd) }
            .sortedBy { it.at }
        if (changes.isEmpty()) {
            val last = events.filter { it.type == EventType.STAGE_CHANGE && it.stageId != null && it.at.isBefore(periodStart) }.maxByOrNull { it.at }
            return listOf(StageSegment(last?.stageId ?: UNKNOWN, periodStart, periodEnd))
        }
        val segments = mutableListOf<StageSegment>()
        val first = changes.first()
        if (first.at.isAfter(periodStart)) {
            val before = first.detail?.removePrefix("from ")?.takeIf { it != "start" && it.isNotBlank() } ?: UNKNOWN
            segments += StageSegment(before, periodStart, first.at)
        }
        changes.forEachIndexed { i, change ->
            val end = changes.getOrNull(i + 1)?.at ?: periodEnd
            if (end.isAfter(change.at)) segments += StageSegment(change.stageId!!, change.at, end)
        }
        return segments
    }

    const val UNKNOWN = "unknown"
}

/** Foreground time per app, in total and split by stage. */
data class AppUsage(val packageName: String, val total: Duration, val byStage: Map<String, Duration>)

object UsageAttribution {
    fun attribute(intervals: List<ForegroundInterval>, segments: List<StageSegment>): List<AppUsage> {
        val perApp = mutableMapOf<String, MutableMap<String, Duration>>()
        for (interval in intervals) {
            val stages = perApp.getOrPut(interval.packageName) { mutableMapOf() }
            var covered = false
            for (seg in segments) {
                val s = maxOf(interval.start, seg.start)
                val e = minOf(interval.end, seg.end)
                if (e.isAfter(s)) {
                    covered = true
                    stages[seg.stageId] = (stages[seg.stageId] ?: Duration.ZERO).plus(Duration.between(s, e))
                }
            }
            if (!covered && interval.end.isAfter(interval.start))
                stages[StageTimeline.UNKNOWN] = (stages[StageTimeline.UNKNOWN] ?: Duration.ZERO).plus(Duration.between(interval.start, interval.end))
        }
        return perApp.map { (pkg, byStage) -> AppUsage(pkg, byStage.values.fold(Duration.ZERO, Duration::plus), byStage) }
            .sortedByDescending { it.total }
    }
}
