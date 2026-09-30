package uk.co.softwarecrafts.contextlauncher.core.todoist

import uk.co.softwarecrafts.contextlauncher.core.stage.TaskSnapshot
import java.time.LocalDate

/** What the home screen lists: open tasks due today or overdue, gating ones first. */
object TodayTasks {

    data class Row(val task: TaskSnapshot, val gating: Boolean, val overdue: Boolean)

    fun rows(tasks: List<TaskSnapshot>, today: LocalDate, gatingLabel: String?): List<Row> =
        tasks.asSequence()
            .filter { !it.completed && it.due != null && !it.due.isAfter(today) }
            .map { Row(it, gating = gatingLabel != null && gatingLabel in it.labels, overdue = it.due!!.isBefore(today)) }
            .sortedWith(
                compareByDescending<Row> { it.gating }
                    .thenByDescending { it.overdue }
                    .thenBy { it.task.dueTime == null }
                    .thenBy { it.task.dueTime }
                    .thenBy { it.task.content.lowercase() }
            )
            .toList()
}
