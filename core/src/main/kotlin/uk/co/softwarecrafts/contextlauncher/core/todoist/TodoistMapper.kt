package uk.co.softwarecrafts.contextlauncher.core.todoist

import uk.co.softwarecrafts.contextlauncher.core.stage.TaskSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** Turns API items into what the stage engine and the home screen use. */
object TodoistMapper {

    data class Due(val date: LocalDate, val time: LocalTime?)

    /**
     * Resolves a due value into the user's zone. Fixed-time dues ("...Z") are
     * converted; floating ones are taken as wall-clock time already.
     */
    fun parseDue(due: DueDto?, zone: ZoneId): Due? {
        val raw = due?.date ?: return null
        return try {
            when {
                raw.length == 10 -> Due(LocalDate.parse(raw), null)
                raw.endsWith("Z") || raw.contains('+') -> {
                    val local = OffsetDateTime.parse(raw).atZoneSameInstant(zone).toLocalDateTime()
                    Due(local.toLocalDate(), local.toLocalTime())
                }
                else -> {
                    val local = LocalDateTime.parse(raw)
                    Due(local.toLocalDate(), local.toLocalTime())
                }
            }
        } catch (e: DateTimeParseException) {
            null
        }
    }

    fun parseInstant(text: String?): Instant? = text?.let {
        try {
            OffsetDateTime.parse(it).toInstant()
        } catch (e: DateTimeParseException) {
            try { Instant.parse(it) } catch (e2: DateTimeParseException) { null }
        }
    }

    fun toSnapshot(item: ItemDto, zone: ZoneId): TaskSnapshot {
        val due = parseDue(item.due, zone)
        return TaskSnapshot(
            id = item.id,
            content = item.content,
            labels = item.labels.map { it.removePrefix("@") }.toSet(),
            due = due?.date,
            dueTime = due?.time,
            completed = item.checked,
            completedAt = parseInstant(item.completedAt),
        )
    }
}
