package uk.co.softwarecrafts.contextlauncher.core.todoist

import org.junit.Assert.assertEquals
import org.junit.Test
import uk.co.softwarecrafts.contextlauncher.core.stage.TaskSnapshot
import java.time.LocalDate
import java.time.LocalTime

class TodayTasksTest {
    private val today = LocalDate.of(2026, 9, 30)
    private fun t(id: String, due: LocalDate? = today, time: LocalTime? = null, labels: Set<String> = emptySet(), done: Boolean = false) =
        TaskSnapshot(id, "task $id", labels, due, time, done)

    @Test
    fun `today only - gating first, then timed, then the rest by name`() {
        val rows = TodayTasks.rows(
            listOf(
                t("z"), t("a"), t("timed", time = LocalTime.of(14, 0)),
                t("old", due = today.minusDays(2)), t("gate", labels = setOf("phone/morning")),
                t("future", due = today.plusDays(1)), t("done", done = true), t("undated", due = null),
            ),
            today, gatingLabel = "phone/morning",
        )
        assertEquals(listOf("gate", "timed", "a", "z"), rows.map { it.task.id })
        assertEquals(true, rows[0].gating)
    }

    @Test
    fun `an overdue task that gates the current stage is still shown, other overdue tasks are not`() {
        val rows = TodayTasks.rows(
            listOf(t("oldgate", due = today.minusDays(1), labels = setOf("phone/morning")), t("old", due = today.minusDays(1)), t("now")),
            today, gatingLabel = "phone/morning",
        )
        assertEquals(listOf("oldgate", "now"), rows.map { it.task.id })
        assertEquals(true, rows[0].overdue)
        // With a different stage active the same overdue labelled task is hidden
        assertEquals(listOf("now"), TodayTasks.rows(listOf(t("oldgate", due = today.minusDays(1), labels = setOf("phone/morning")), t("now")), today, gatingLabel = null).map { it.task.id })
    }
}
