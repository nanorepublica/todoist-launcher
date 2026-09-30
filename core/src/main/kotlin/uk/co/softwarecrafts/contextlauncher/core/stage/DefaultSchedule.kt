package uk.co.softwarecrafts.contextlauncher.core.stage

import java.time.DayOfWeek
import java.time.LocalTime

/**
 * The recurring calendar blocks onboarding offers to write into the stage
 * calendar. Titles match SeedConfig's calendar triggers. Edit the events in
 * any calendar app afterwards; the calendar is the source of truth.
 */
object DefaultSchedule {
    data class Block(val title: String, val days: Set<DayOfWeek>, val start: LocalTime, val end: LocalTime)

    private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    private val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    val blocks: List<Block> = listOf(
        Block("Morning routine", weekdays, LocalTime.of(6, 30), LocalTime.of(7, 30)),
        Block("Work AM", weekdays, LocalTime.of(9, 0), LocalTime.of(12, 30)),
        Block("Lunch", weekdays, LocalTime.of(12, 30), LocalTime.of(13, 30)),
        Block("Work PM", weekdays, LocalTime.of(13, 30), LocalTime.of(17, 0)),
        Block("Family", weekdays, LocalTime.of(17, 0), LocalTime.of(19, 0)),
        Block("Kids' bedtime", weekdays, LocalTime.of(19, 0), LocalTime.of(20, 0)),
        Block("Morning routine", weekend, LocalTime.of(7, 0), LocalTime.of(8, 0)),
        Block("Kids' bedtime", weekend, LocalTime.of(19, 0), LocalTime.of(20, 0)),
    )
}
