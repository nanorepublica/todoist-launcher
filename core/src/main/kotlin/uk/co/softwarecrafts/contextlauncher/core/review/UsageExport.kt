package uk.co.softwarecrafts.contextlauncher.core.review

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uk.co.softwarecrafts.contextlauncher.core.log.LogEvent

/** JSON export of the usage log and attributed app time for a period (SPEC.md: "Export: JSON for v1"). */
object UsageExport {
    @Serializable
    data class EventDto(
        val at: String, val type: String, val stageId: String?, val packageName: String?, val app: String?,
        val reason: String?, val limitMinutes: Int?, val bypassNumber: Int?, val detail: String?,
    )

    @Serializable
    data class UsageDto(val packageName: String, val app: String, val totalMinutes: Long, val byStageMinutes: Map<String, Long>)

    @Serializable
    data class ExportDto(
        val periodStart: String,
        val periodEnd: String,
        val totalBypasses: Int,
        val totalTimesUp: Int,
        val tasksCompleted: Int,
        val events: List<EventDto>,
        val usage: List<UsageDto>,
    )

    private val json = Json { prettyPrint = true; prettyPrintIndent = "  "; encodeDefaults = true }

    fun encode(report: ReviewReport, events: List<LogEvent>, label: (String) -> String): String {
        val dto = ExportDto(
            periodStart = report.periodStart.toString(),
            periodEnd = report.periodEnd.toString(),
            totalBypasses = report.totalBypasses,
            totalTimesUp = report.totalTimesUp,
            tasksCompleted = report.tasksCompleted,
            events = events.filter { !it.at.isBefore(report.periodStart) && it.at.isBefore(report.periodEnd) }.map {
                EventDto(it.at.toString(), it.type.name, it.stageId, it.packageName, it.packageName?.let(label), it.reason, it.limitMinutes, it.bypassNumber, it.detail)
            },
            usage = report.usage.map { u ->
                UsageDto(u.packageName, label(u.packageName), u.total.toMinutes(), u.byStage.mapValues { it.value.toMinutes() })
            },
        )
        return json.encodeToString(ExportDto.serializer(), dto)
    }
}
