package uk.co.softwarecrafts.contextlauncher.core.review

import uk.co.softwarecrafts.contextlauncher.core.log.EventType
import uk.co.softwarecrafts.contextlauncher.core.log.LogEvent
import java.time.Duration
import java.time.Instant

/** Bypasses of one app during one stage over the period. */
data class BypassStat(
    val packageName: String,
    val stageId: String,
    val count: Int,
    val limitsChosen: List<Int>,
    /** Sessions that ran out (TIMES_UP) for this app in this stage. */
    val timesUp: Int,
    val reasons: List<String>,
) {
    val averageLimit: Double get() = if (limitsChosen.isEmpty()) 0.0 else limitsChosen.average()
}

/** Capped allowed-app sessions of one app over the period. */
data class CappedStat(val packageName: String, val opens: Int, val timesUp: Int, val capMinutes: Int?)

data class ReviewReport(
    val periodStart: Instant,
    val periodEnd: Instant,
    val bypasses: List<BypassStat>,
    val capped: List<CappedStat>,
    val totalBypasses: Int,
    val totalTimesUp: Int,
    val tasksCompleted: Int,
    val stageChanges: Int,
    val usage: List<AppUsage>,
    val segments: List<StageSegment>,
) {
    fun topApps(n: Int = 5): List<AppUsage> = usage.take(n)
}

object ReviewBuilder {

    fun build(events: List<LogEvent>, intervals: List<ForegroundInterval>, periodStart: Instant, periodEnd: Instant): ReviewReport {
        val inPeriod = events.filter { !it.at.isBefore(periodStart) && it.at.isBefore(periodEnd) }
        val segments = StageTimeline.build(events, periodStart, periodEnd)

        val bypassEvents = inPeriod.filter { it.type == EventType.BYPASS && it.packageName != null }
        val timesUp = inPeriod.filter { it.type == EventType.TIMES_UP && it.packageName != null }
        val cappedOpens = inPeriod.filter { it.type == EventType.CAPPED_OPEN && it.packageName != null }

        // A TIMES_UP belongs to a bypass when the same app had a bypass in the same stage; otherwise to a capped open
        val bypassKeys = bypassEvents.map { it.packageName!! to (it.stageId ?: "") }.toSet()
        val bypasses = bypassEvents.groupBy { it.packageName!! to (it.stageId ?: "") }.map { (key, list) ->
            val (pkg, stage) = key
            BypassStat(
                packageName = pkg, stageId = stage, count = list.size,
                limitsChosen = list.mapNotNull { it.limitMinutes },
                timesUp = timesUp.count { it.packageName == pkg && (it.stageId ?: "") == stage },
                reasons = list.mapNotNull { it.reason }.filter { it.isNotBlank() },
            )
        }.sortedByDescending { it.count }

        val capped = cappedOpens.groupBy { it.packageName!! }.map { (pkg, list) ->
            CappedStat(
                packageName = pkg, opens = list.size,
                timesUp = timesUp.count { it.packageName == pkg && (it.packageName to (it.stageId ?: "")) !in bypassKeys },
                capMinutes = list.mapNotNull { it.limitMinutes }.maxOrNull(),
            )
        }.sortedByDescending { it.opens }

        return ReviewReport(
            periodStart = periodStart, periodEnd = periodEnd,
            bypasses = bypasses, capped = capped,
            totalBypasses = bypassEvents.size, totalTimesUp = timesUp.size,
            tasksCompleted = inPeriod.count { it.type == EventType.TASK_COMPLETED },
            stageChanges = inPeriod.count { it.type == EventType.STAGE_CHANGE },
            usage = UsageAttribution.attribute(intervals, segments),
            segments = segments,
        )
    }

    fun minutes(d: Duration): Long = d.toMinutes()
}
