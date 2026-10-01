package uk.co.softwarecrafts.contextlauncher.debug

import android.content.Context
import uk.co.softwarecrafts.contextlauncher.Graph
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Debug-build scenarios: each shifts the clock to a moment of the default
 * schedule and creates the Todoist tasks it needs, so a feature can be seen
 * working without waiting for the day to come round. Every task it creates
 * ends in [TAG]; [cleanUp] deletes them and resets the clock.
 */
object Scenarios {
    const val TAG = "(launcher test)"

    data class TaskSpec(val content: String, val label: String, val time: LocalTime? = null)

    data class Scenario(
        val id: String,
        val title: String,
        val time: LocalTime,
        val weekday: Boolean,
        val tasks: List<TaskSpec>,
        /** What to do and what to expect, shown before running. */
        val steps: String,
    )

    val all: List<Scenario> = listOf(
        Scenario(
            "stage_change", "Stage change on the clock", LocalTime.of(16, 58), weekday = true, tasks = emptyList(),
            steps = "Clock jumps to a weekday 16:58, inside Work PM.\n\nExpect: stage line \"Work PM · until 17:00\" with the Work apps. At 17:00 it changes to \"Family · until 19:00\" on its own and the apps list switches to the Family set (Claude, Spotify, YouTube 15 min).",
        ),
        Scenario(
            "morning_done", "Task stage unlocks early with a perk", LocalTime.of(6, 40), weekday = true,
            tasks = listOf(TaskSpec("Exercise $TAG", "phone/morning")),
            steps = "Clock jumps to a weekday 06:40, inside Morning routine. Creates \"Exercise\" with @phone/morning due today.\n\nExpect: stage \"Morning routine\", the task listed with ● (it holds the stage). Tap it and confirm. The stage ends at once and the phone falls to \"Default\"; the Games group appears for 10 minutes as the perk.",
        ),
        Scenario(
            "bypass", "Friction screen and escalation", LocalTime.of(10, 0), weekday = true, tasks = emptyList(),
            steps = "Clock jumps to a weekday 10:00, inside Work AM.\n\nSwipe up, open an app Work does not allow (YouTube): the friction screen asks for a reason and a time limit, no wait the first time. Pick 1 min. Back on the home the session line counts down. Repeat: the second bypass in this block waits 10 s, the third 15 s, the fourth 30 s. When the minute runs out the launcher comes back (accessibility service) or notifies you.",
        ),
        Scenario(
            "capped", "Capped allowed app and time's up", LocalTime.of(17, 30), weekday = true, tasks = emptyList(),
            steps = "Clock jumps to a weekday 17:30, inside Family.\n\nTap YouTube on the home (listed with 15 min): it opens straight away with a running timer, no reason asked, not counted as a bypass. To skip the wait run the adb command from TESTING.md to advance the clock 15 minutes: the launcher returns (or notifies) with \"time's up\".",
        ),
        Scenario(
            "label_group", "Todoist label unlocks an app group", LocalTime.of(10, 0), weekday = true,
            tasks = listOf(TaskSpec("Pay the gas bill $TAG", "banking")),
            steps = "Clock jumps to a weekday 10:00 (Work AM). Creates \"Pay the gas bill\" with @banking due today.\n\nExpect: the Banking group's apps appear under Apps even though Work does not allow them, and the task shows with ○. Complete the task: they disappear.",
        ),
        Scenario(
            "kids_down", "Kids' bedtime done, then After bedtime", LocalTime.of(19, 10), weekday = true,
            tasks = listOf(TaskSpec("Kids down $TAG", "phone/kidsdown")),
            steps = "Clock jumps to a weekday 19:10, inside Kids' bedtime. Creates \"Kids down\" with @phone/kidsdown due today.\n\nExpect: stage \"Kids' bedtime\" with the task marked ●. Complete it: the stage becomes \"After bedtime · until 21:00\" with the Evening apps. At 21:00 Wind-down takes over.",
        ),
        Scenario(
            "review_due", "Weekly review falls due", LocalTime.of(10, 0), weekday = true,
            tasks = listOf(TaskSpec("Weekly phone review $TAG", "phone/review", LocalTime.of(9, 0))),
            steps = "Clock jumps to a weekday 10:00. Creates \"Weekly phone review\" with @phone/review due today at 09:00, so it is already due.\n\nExpect: the stage line reads \"Weekly review\" and the review screen opens itself. Back out and tap the stage line to reopen it. \"Finish and complete task\" closes the task and the stage falls back to Work AM.",
        ),
        Scenario(
            "wind_down", "Wind-down hides Claude", LocalTime.of(21, 30), weekday = true, tasks = emptyList(),
            steps = "Clock jumps to a weekday 21:30.\n\nExpect: stage \"Wind-down · until 06:00\". Tap speak: Claude is not offered as a destination (Settings > Context settings > Claude > Hidden during).",
        ),
        Scenario(
            "weekend", "Weekend default", LocalTime.of(7, 15), weekday = false, tasks = emptyList(),
            steps = "Clock jumps to a weekend day 07:15.\n\nExpect: \"Morning routine · until 08:00\", then \"Weekend\" (the weekend default stage) with no end time, since no other block follows.",
        ),
    )

    fun byId(id: String): Scenario? = all.firstOrNull { it.id == id }

    /** Shifts the clock and creates the tasks. Returns a one-line result for a toast or log. */
    suspend fun run(context: Context, id: String): String {
        val scenario = byId(id) ?: return "unknown scenario '$id'"
        val app = context.applicationContext
        val date = targetDate(scenario.weekday, Graph.clock.zone)
        DebugTime.setLocal(app, date.atTime(scenario.time))
        if (scenario.tasks.isEmpty()) return "clock set to $date ${scenario.time}"
        val todoist = Graph.todoist(app)
        if (!todoist.hasToken) return "clock set; no Todoist token, so no tasks created"
        val existing = todoist.openItems()
        var created = 0
        scenario.tasks.forEach { spec ->
            if (existing.any { it.content == spec.content && it.due?.date?.startsWith(date.toString()) == true }) return@forEach
            todoist.addTask(spec.content, date, spec.time, listOf(spec.label))
            created++
        }
        return "clock set to $date ${scenario.time}; $created task(s) created"
    }

    /** Deletes every open task tagged by a scenario and returns to real time. */
    suspend fun cleanUp(context: Context): String {
        val app = context.applicationContext
        DebugTime.reset(app)
        val todoist = Graph.todoist(app)
        if (!todoist.hasToken) return "clock reset; no Todoist token"
        val mine = todoist.openItems().filter { it.content.endsWith(TAG) }
        mine.forEach { todoist.delete(it.id) }
        return "clock reset; ${mine.size} test task(s) deleted"
    }

    /** Today when its kind matches, else the next day of that kind. Based on the real date, not the shifted one. */
    fun targetDate(weekday: Boolean, zone: ZoneId, today: LocalDate = LocalDate.now(zone)): LocalDate {
        var d = today
        while (isWeekend(d) == weekday) d = d.plusDays(1)
        return d
    }

    private fun isWeekend(d: LocalDate) = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY
}
