package uk.co.softwarecrafts.contextlauncher.core.log

import java.time.Instant

/** What the launcher records. The weekly review reads these back. */
enum class EventType {
    /** An off-list app was opened through the friction screen. */
    BYPASS,
    /** A capped allowed app was opened with its fixed timer. */
    CAPPED_OPEN,
    /** A timed session ran out and the launcher took over. */
    TIMES_UP,
    STAGE_CHANGE,
    TASK_COMPLETED,
    REVIEW_COMPLETED,
    /** A setting was changed (detail says what). */
    CONFIG_CHANGE,
}

data class LogEvent(
    val id: Long = 0,
    val at: Instant,
    val type: EventType,
    val stageId: String? = null,
    val packageName: String? = null,
    /** Optional typed reason from the friction screen. */
    val reason: String? = null,
    /** Time limit chosen on the friction screen, or the fixed cap. */
    val limitMinutes: Int? = null,
    /** 1 for the first bypass in the stage block, 2 for the second, and so on. */
    val bypassNumber: Int? = null,
    /** Free text for anything else (new stage id, task id, setting name). */
    val detail: String? = null,
)
