package uk.co.softwarecrafts.contextlauncher.core.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.softwarecrafts.contextlauncher.core.FakeClock
import uk.co.softwarecrafts.contextlauncher.core.config.SeedConfig
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class StageResolverTest {

    private val zone: ZoneId = ZoneId.of("Europe/London")
    private val config = SeedConfig.default()

    // Wednesday 30 September 2026
    private val wednesday = LocalDate.of(2026, 9, 30)
    private val saturday = LocalDate.of(2026, 10, 3)

    private fun at(date: LocalDate, time: LocalTime): Instant = date.atTime(time).atZone(zone).toInstant()
    private fun clockAt(date: LocalDate, hour: Int, minute: Int = 0) = FakeClock(LocalDateTime.of(date, LocalTime.of(hour, minute)), zone)

    private fun weekdayEvents(date: LocalDate) = listOf(
        CalendarEvent("Morning routine", at(date, LocalTime.of(6, 30)), at(date, LocalTime.of(7, 30))),
        CalendarEvent("Work AM", at(date, LocalTime.of(9, 0)), at(date, LocalTime.of(12, 30))),
        CalendarEvent("Lunch", at(date, LocalTime.of(12, 30)), at(date, LocalTime.of(13, 30))),
        CalendarEvent("Work PM", at(date, LocalTime.of(13, 30)), at(date, LocalTime.of(17, 0))),
        CalendarEvent("Family", at(date, LocalTime.of(17, 0)), at(date, LocalTime.of(19, 0))),
        CalendarEvent("Kids' bedtime", at(date, LocalTime.of(19, 0)), at(date, LocalTime.of(20, 0))),
    )

    private fun morningTask(date: LocalDate, done: Boolean, doneAt: LocalTime? = null) = TaskSnapshot(
        id = "m1", content = "Exercise", labels = setOf("ph_morning"), due = date,
        completed = done, completedAt = doneAt?.let { at(date, it) },
    )

    private fun kidsDown(date: LocalDate, done: Boolean, doneAt: LocalTime? = null) = TaskSnapshot(
        id = "k1", content = "Kids down", labels = setOf("ph_kidsdown"), due = date,
        completed = done, completedAt = doneAt?.let { at(date, it) },
    )

    private fun resolve(clock: FakeClock, events: List<CalendarEvent>, tasks: List<TaskSnapshot> = emptyList()) =
        StageResolver(clock).resolve(config, events, tasks)

    @Test
    fun `calendar block picks the matching stage and reports when it ends`() {
        val r = resolve(clockAt(wednesday, 10), weekdayEvents(wednesday))
        assertEquals("work_am", r.stage.id)
        assertEquals(at(wednesday, LocalTime.of(12, 30)), r.nextChangeAt)
        assertTrue(r.current is Activation.CalendarBlock)
    }

    @Test
    fun `event title matching ignores case and surrounding spaces`() {
        val events = listOf(CalendarEvent("  work am ", at(wednesday, LocalTime.of(9, 0)), at(wednesday, LocalTime.of(12, 30))))
        assertEquals("work_am", resolve(clockAt(wednesday, 10), events).stage.id)
    }

    @Test
    fun `weekday gap falls to the default stage until the next block`() {
        val r = resolve(clockAt(wednesday, 8), weekdayEvents(wednesday))
        assertEquals("default_weekday", r.stage.id)
        assertTrue(r.current is Activation.Gap)
        assertEquals(at(wednesday, LocalTime.of(9, 0)), r.nextChangeAt)
    }

    @Test
    fun `weekend gap falls to the locked-down weekend stage`() {
        val r = resolve(clockAt(saturday, 11), emptyList())
        assertEquals("default_weekend", r.stage.id)
        // Nothing on the calendar, so the next thing to happen is wind-down at 21:00
        assertEquals(at(saturday, LocalTime.of(21, 0)), r.nextChangeAt)
    }

    @Test
    fun `wind-down starts at 21 00 and wraps past midnight`() {
        assertEquals("wind_down", resolve(clockAt(wednesday, 21, 30), weekdayEvents(wednesday)).stage.id)
        val early = resolve(clockAt(wednesday.plusDays(1), 2), emptyList())
        assertEquals("wind_down", early.stage.id)
        assertEquals(at(wednesday.plusDays(1), LocalTime.of(6, 0)), early.nextChangeAt)
        assertEquals("default_weekday", resolve(clockAt(wednesday.plusDays(1), 6, 5), emptyList()).stage.id)
    }

    @Test
    fun `morning routine ends early when its tasks are done and games unlock for ten minutes`() {
        val clock = clockAt(wednesday, 7)
        val notDone = resolve(clock, weekdayEvents(wednesday), listOf(morningTask(wednesday, done = false)))
        assertEquals("morning_routine", notDone.stage.id)
        assertEquals(listOf("m1"), notDone.gatingTaskIds)

        val done = resolve(clock, weekdayEvents(wednesday), listOf(morningTask(wednesday, done = true, doneAt = LocalTime.of(6, 55))))
        assertEquals("default_weekday", done.stage.id)
        assertEquals(listOf(ActivePerk("morning_routine", SeedConfig.GAMES, at(wednesday, LocalTime.of(7, 5)))), done.perks)
        assertEquals(at(wednesday, LocalTime.of(7, 5)), done.nextChangeAt)

        clock.advance(Duration.ofMinutes(6))
        assertEquals(emptyList<ActivePerk>(), resolve(clock, weekdayEvents(wednesday), listOf(morningTask(wednesday, done = true, doneAt = LocalTime.of(6, 55)))).perks)
    }

    @Test
    fun `a task stage with no tasks today simply runs its block`() {
        val r = resolve(clockAt(wednesday, 7), weekdayEvents(wednesday), emptyList())
        assertEquals("morning_routine", r.stage.id)
        assertEquals(emptyList<String>(), r.gatingTaskIds)
    }

    @Test
    fun `overdue tasks with the label still gate the stage`() {
        val overdue = morningTask(wednesday.minusDays(1), done = false)
        val r = resolve(clockAt(wednesday, 7), weekdayEvents(wednesday), listOf(overdue))
        assertEquals(listOf("m1"), r.gatingTaskIds)
    }

    @Test
    fun `kids down ticked ends bedtime and starts after-bedtime until 21 00`() {
        val events = weekdayEvents(wednesday)
        val open = resolve(clockAt(wednesday, 19, 30), events, listOf(kidsDown(wednesday, done = false)))
        assertEquals("kids_bedtime", open.stage.id)

        val ticked = listOf(kidsDown(wednesday, done = true, doneAt = LocalTime.of(19, 40)))
        val after = resolve(clockAt(wednesday, 19, 45), events, ticked)
        assertEquals("after_bedtime", after.stage.id)
        assertEquals(at(wednesday, LocalTime.of(19, 40)), after.current.since)
        assertEquals(at(wednesday, LocalTime.of(20, 0)), after.nextChangeAt)

        assertEquals("wind_down", resolve(clockAt(wednesday, 21, 0), events, ticked).stage.id)
    }

    @Test
    fun `kids down ticked yesterday does not start after-bedtime today`() {
        val yesterday = kidsDown(wednesday.minusDays(1), done = true, doneAt = LocalTime.of(19, 40))
        val r = resolve(clockAt(wednesday, 8), weekdayEvents(wednesday), listOf(yesterday))
        assertEquals("default_weekday", r.stage.id)
    }

    @Test
    fun `most restrictive wins on overlap`() {
        // Family (rank 80) overlapping Work PM (rank 50)
        val events = weekdayEvents(wednesday) + CalendarEvent("Family", at(wednesday, LocalTime.of(16, 0)), at(wednesday, LocalTime.of(18, 0)))
        val r = resolve(clockAt(wednesday, 16, 30), events)
        assertEquals("family", r.stage.id)
        assertEquals(listOf("family", "work_pm"), r.active.map { it.stage.id })
    }

    @Test
    fun `review task falling due forces the review stage over everything`() {
        val review = TaskSnapshot("r1", "Weekly review", setOf("ph_review"), due = wednesday, dueTime = LocalTime.of(10, 0))
        val before = resolve(clockAt(wednesday, 9, 30), weekdayEvents(wednesday), listOf(review))
        assertEquals("work_am", before.stage.id)
        assertEquals(at(wednesday, LocalTime.of(10, 0)), before.nextChangeAt)

        val during = resolve(clockAt(wednesday, 10, 30), weekdayEvents(wednesday), listOf(review))
        assertEquals("weekly_review", during.stage.id)
        assertEquals(listOf("r1"), during.gatingTaskIds)

        val doneReview = review.copy(completed = true, completedAt = at(wednesday, LocalTime.of(10, 45)))
        assertEquals("work_am", resolve(clockAt(wednesday, 11), weekdayEvents(wednesday), listOf(doneReview)).stage.id)
    }

    @Test
    fun `review task due without a time is due from the start of the day, and overdue ones stay due`() {
        val undated = TaskSnapshot("r1", "Weekly review", setOf("ph_review"), due = wednesday)
        assertEquals("weekly_review", resolve(clockAt(wednesday, 0, 30), emptyList(), listOf(undated)).stage.id)
        val overdue = undated.copy(due = wednesday.minusDays(3))
        assertEquals("weekly_review", resolve(clockAt(wednesday, 15), weekdayEvents(wednesday), listOf(overdue)).stage.id)
    }

    @Test
    fun `open task-linked labels unlock their groups only when due by today`() {
        val bankingToday = TaskSnapshot("b1", "Pay bill", setOf("banking"), due = wednesday)
        val bankingLater = bankingToday.copy(id = "b2", due = wednesday.plusDays(3))
        assertEquals(setOf(SeedConfig.BANKING), resolve(clockAt(wednesday, 10), weekdayEvents(wednesday), listOf(bankingToday)).taskLinkedGroups)
        assertEquals(emptySet<String>(), resolve(clockAt(wednesday, 10), weekdayEvents(wednesday), listOf(bankingLater)).taskLinkedGroups)
        assertEquals(emptySet<String>(), resolve(clockAt(wednesday, 10), weekdayEvents(wednesday), listOf(bankingToday.copy(completed = true))).taskLinkedGroups)
    }

    @Test
    fun `tracker reports a change only when the block moves on`() {
        val tracker = StageTracker()
        val clock = clockAt(wednesday, 10)
        val first = tracker.update(resolve(clock, weekdayEvents(wednesday)), clock.now())
        assertEquals("work_am", first?.to?.stageId)
        assertEquals(null, first?.from)

        clock.advance(Duration.ofMinutes(30))
        assertEquals(null, tracker.update(resolve(clock, weekdayEvents(wednesday)), clock.now()))

        clock.set(LocalDateTime.of(wednesday, LocalTime.of(12, 45)))
        val second = tracker.update(resolve(clock, weekdayEvents(wednesday)), clock.now())
        assertEquals("work_am", second?.from?.stageId)
        assertEquals("lunch", second?.to?.stageId)
    }
}
