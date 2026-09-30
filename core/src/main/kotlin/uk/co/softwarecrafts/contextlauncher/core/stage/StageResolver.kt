package uk.co.softwarecrafts.contextlauncher.core.stage

import uk.co.softwarecrafts.contextlauncher.core.Clock
import uk.co.softwarecrafts.contextlauncher.core.config.DayKind
import uk.co.softwarecrafts.contextlauncher.core.config.LauncherConfig
import uk.co.softwarecrafts.contextlauncher.core.config.Stage
import uk.co.softwarecrafts.contextlauncher.core.config.StageTrigger
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Works out which stage the phone is in. Stateless: give it the config, the
 * calendar events around now and the current task snapshot, and it answers
 * for the clock's "now". Tested with a fake clock.
 *
 * Rules (see SPEC.md and the defaults in CLAUDE.md):
 * - A calendar stage runs for its event, but ends early once every task
 *   carrying its doneLabel that is due today or overdue is complete. With no
 *   such task the block simply runs its course.
 * - A fixed-time stage runs every day in its window; the window may wrap
 *   past midnight.
 * - A tasks-done stage starts when its label's tasks were all completed
 *   today, and ends at its "until" time.
 * - A task-due stage runs while a task with its label is due and open.
 * - A default stage fills any moment on its kind of day when nothing else
 *   is active.
 * - Overlaps: the highest rank wins; ties keep config order.
 */
class StageResolver(private val clock: Clock) {

    fun resolve(config: LauncherConfig, events: List<CalendarEvent>, tasks: List<TaskSnapshot>): Resolution {
        val now = clock.now()
        val zone = clock.zone
        val today = clock.today()
        val boundaries = mutableListOf<Instant>()
        val active = mutableListOf<Activation>()

        val stages = config.stages.filter { it.enabled }
        for (stage in stages) {
            val activation = when (val trigger = stage.trigger) {
                is StageTrigger.Calendar -> resolveCalendar(stage, trigger, events, tasks, now, today, boundaries)
                is StageTrigger.FixedTime -> resolveFixed(stage, trigger, now, today, zone, boundaries)
                is StageTrigger.TasksDone -> resolveTasksDone(stage, trigger, tasks, now, today, zone, boundaries)
                is StageTrigger.TaskDue -> resolveTaskDue(stage, trigger, tasks, now, today, zone, boundaries)
                is StageTrigger.Default -> null
            }
            if (activation != null) active += activation
        }

        if (active.isEmpty()) {
            val kind = dayKind(today)
            stages.firstOrNull { (it.trigger as? StageTrigger.Default)?.days == kind }
                ?.let { active += Activation.Gap(it, since = now) }
        }
        // Tomorrow is a new kind of day, or a new set of events at least
        boundaries += today.plusDays(1).atStartOfDay(zone).toInstant()

        val sorted = active.sortedByDescending { it.stage.rank }
        val current = sorted.firstOrNull()
            ?: throw IllegalStateException("no stage active and no default stage for ${dayKind(today)}")

        val perks = stages.mapNotNull { stage ->
            val perk = stage.onDonePerk ?: return@mapNotNull null
            val label = stage.doneLabel ?: return@mapNotNull null
            val doneAt = allDoneAt(tasks, label, today, zone) ?: return@mapNotNull null
            val until = doneAt.plusSeconds(perk.minutes * 60L)
            if (now < until) {
                boundaries += until
                ActivePerk(stage.id, perk.groupId, until)
            } else null
        }

        val openLabels = tasks.filter { !it.completed && isDueByToday(it, today) }.flatMap { it.labels }.toSet()
        val taskLinked = config.labelGroups.filter { it.label in openLabels }.map { it.groupId }.toSet()

        val gating = current.stage.doneLabel?.let { label -> gatingTasks(tasks, label, today).map { it.id } } ?: emptyList()

        return Resolution(
            current = current,
            active = sorted,
            nextChangeAt = boundaries.filter { it > now }.minOrNull(),
            perks = perks,
            taskLinkedGroups = taskLinked,
            gatingTaskIds = gating,
        )
    }

    private fun resolveCalendar(
        stage: Stage, trigger: StageTrigger.Calendar, events: List<CalendarEvent>, tasks: List<TaskSnapshot>,
        now: Instant, today: LocalDate, boundaries: MutableList<Instant>,
    ): Activation? {
        val matching = events.filter { it.title.trim().equals(trigger.eventTitle.trim(), ignoreCase = true) }
        matching.forEach { boundaries += it.start; boundaries += it.end }
        val running = matching.firstOrNull { now >= it.start && now < it.end } ?: return null
        val label = stage.doneLabel
        if (label != null && gatingTasks(tasks, label, today).let { it.isNotEmpty() && it.all { t -> t.completed } })
            return null
        return Activation.CalendarBlock(stage, since = running.start, until = running.end, eventTitle = running.title)
    }

    private fun resolveFixed(
        stage: Stage, trigger: StageTrigger.FixedTime, now: Instant, today: LocalDate, zone: ZoneId,
        boundaries: MutableList<Instant>,
    ): Activation? {
        // Consider the window starting yesterday, today and tomorrow so wrap-around is handled uniformly
        for (dayOffset in -1L..1L) {
            val day = today.plusDays(dayOffset)
            val start = day.atTime(trigger.start).atZone(zone).toInstant()
            val endDay = if (trigger.end > trigger.start) day else day.plusDays(1)
            val end = endDay.atTime(trigger.end).atZone(zone).toInstant()
            boundaries += start; boundaries += end
            if (now >= start && now < end) return Activation.FixedWindow(stage, since = start, until = end)
        }
        return null
    }

    private fun resolveTasksDone(
        stage: Stage, trigger: StageTrigger.TasksDone, tasks: List<TaskSnapshot>, now: Instant, today: LocalDate,
        zone: ZoneId, boundaries: MutableList<Instant>,
    ): Activation? {
        val doneAt = allDoneAt(tasks, trigger.label, today, zone) ?: return null
        val until = today.atTime(trigger.until).atZone(zone).toInstant()
        boundaries += until
        if (now < doneAt || now >= until) return null
        return Activation.TasksDone(stage, since = doneAt, until = until)
    }

    private fun resolveTaskDue(
        stage: Stage, trigger: StageTrigger.TaskDue, tasks: List<TaskSnapshot>, now: Instant, today: LocalDate,
        zone: ZoneId, boundaries: MutableList<Instant>,
    ): Activation? {
        val candidates = tasks.filter { !it.completed && trigger.label in it.labels && it.due != null }
        candidates.forEach { task -> dueInstant(task, zone)?.let { boundaries += it } }
        val due = candidates.filter { isDueNow(it, now, today, zone) }
            .minByOrNull { dueInstant(it, zone) ?: Instant.MIN } ?: return null
        val since = dueInstant(due, zone)?.takeIf { it <= now } ?: today.atStartOfDay(zone).toInstant()
        return Activation.TaskDue(stage, since = since, taskId = due.id)
    }

    /** Tasks with the label that are due today or overdue, completed or not. */
    private fun gatingTasks(tasks: List<TaskSnapshot>, label: String, today: LocalDate): List<TaskSnapshot> =
        tasks.filter { label in it.labels && isDueByToday(it, today) }

    /** The moment the last gating task was completed, if all were completed today. */
    private fun allDoneAt(tasks: List<TaskSnapshot>, label: String, today: LocalDate, zone: ZoneId): Instant? {
        val gating = gatingTasks(tasks, label, today)
        if (gating.isEmpty() || gating.any { !it.completed }) return null
        val doneAt = gating.mapNotNull { it.completedAt }.maxOrNull() ?: return null
        return doneAt.takeIf { LocalDateTime.ofInstant(it, zone).toLocalDate() == today }
    }

    private fun isDueByToday(task: TaskSnapshot, today: LocalDate): Boolean =
        task.due != null && !task.due.isAfter(today)

    private fun isDueNow(task: TaskSnapshot, now: Instant, today: LocalDate, zone: ZoneId): Boolean {
        val due = task.due ?: return false
        if (due.isBefore(today)) return true
        if (due.isAfter(today)) return false
        val time = task.dueTime ?: return true
        return !due.atTime(time).atZone(zone).toInstant().isAfter(now)
    }

    private fun dueInstant(task: TaskSnapshot, zone: ZoneId): Instant? {
        val due = task.due ?: return null
        return due.atTime(task.dueTime ?: LocalTime.MIDNIGHT).atZone(zone).toInstant()
    }

    companion object {
        fun dayKind(date: LocalDate): DayKind =
            if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) DayKind.WEEKEND else DayKind.WEEKDAY
    }
}
