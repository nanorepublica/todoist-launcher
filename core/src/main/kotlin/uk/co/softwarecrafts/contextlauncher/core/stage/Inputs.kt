package uk.co.softwarecrafts.contextlauncher.core.stage

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** One calendar event instance, already expanded from any recurrence. */
data class CalendarEvent(
    val title: String,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean = false,
)

/** What the resolver needs to know about a Todoist task. */
data class TaskSnapshot(
    val id: String,
    val content: String,
    /** Labels without the leading `@`. */
    val labels: Set<String>,
    val due: LocalDate? = null,
    /** Only set when the task has a due time. */
    val dueTime: LocalTime? = null,
    val completed: Boolean = false,
    val completedAt: Instant? = null,
)
