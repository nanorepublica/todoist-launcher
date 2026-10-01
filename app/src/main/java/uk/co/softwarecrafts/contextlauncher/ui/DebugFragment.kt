package uk.co.softwarecrafts.contextlauncher.ui

import android.app.DatePickerDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.olauncher.R
import app.olauncher.helper.createDialog
import app.olauncher.helper.showToast
import kotlinx.coroutines.launch
import uk.co.softwarecrafts.contextlauncher.Graph
import uk.co.softwarecrafts.contextlauncher.debug.DebugTime
import uk.co.softwarecrafts.contextlauncher.debug.Scenarios
import uk.co.softwarecrafts.contextlauncher.ui.settings.FormDialogs
import uk.co.softwarecrafts.contextlauncher.ui.settings.FormFragment
import java.time.Duration
import java.time.format.DateTimeFormatter

/** Debug builds only: time travel and the scenario runner. Reached from Settings > Debug. */
class DebugFragment : FormFragment() {

    override val title: String get() = "Debug"
    override val showSave: Boolean = false

    override fun load() {
        render()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { DebugTime.changes.collect { render() } }
        }
    }

    private fun render() {
        val ctx = requireContext()
        val app = ctx.applicationContext
        val now = Graph.clock.localNow()
        form {
            header("Clock")
            row(now.format(STAMP), DebugTime.describe())
            action("Set time") { FormDialogs.time(ctx, now.toLocalTime()) { t -> DebugTime.setLocal(app, now.toLocalDate().atTime(t)) } }
            action("Set date") {
                DatePickerDialog(ctx, { _, y, m, d -> DebugTime.setLocal(app, java.time.LocalDate.of(y, m + 1, d).atTime(now.toLocalTime())) }, now.year, now.monthValue - 1, now.dayOfMonth).show()
            }
            action("+15 min") { DebugTime.advance(app, Duration.ofMinutes(15)) }
            action("+1 hour") { DebugTime.advance(app, Duration.ofHours(1)) }
            action("+1 day") { DebugTime.advance(app, Duration.ofDays(1)) }
            action("Real time") { DebugTime.reset(app) }
            note("Shifts stages, sessions, task due dates and the review period. Survives a restart. Also from adb, see docs/TESTING.md.")

            header("Scenarios")
            note("Each one sets the clock and creates the Todoist tasks it needs, tagged ${Scenarios.TAG}.")
            Scenarios.all.forEach { s ->
                row(s.title, (if (s.weekday) "weekday " else "weekend ") + s.time) {
                    ctx.createDialog(title = s.title, action = "Run", message = s.steps, onAction = {
                        launch {
                            val result = runCatching { Scenarios.run(app, s.id) }.getOrElse { "failed: ${it.message}" }
                            app.showToast(result)
                            render()
                        }
                    }).showRespectingStatusBar()
                }
            }
            action("Clean up test tasks, real time") {
                launch {
                    val result = runCatching { Scenarios.cleanUp(app) }.getOrElse { "failed: ${it.message}" }
                    app.showToast(result)
                    render()
                }
            }

            header("Event log")
            action("Show last 20 events") {
                launch {
                    val events = Graph.eventLog(app).latest(20)
                    val text = if (events.isEmpty()) "No events yet" else events.joinToString("\n") { e ->
                        "${java.time.LocalDateTime.ofInstant(e.at, Graph.clock.zone).format(STAMP)} ${e.type} ${e.stageId ?: ""} ${e.packageName ?: ""} ${e.detail ?: ""}".trim()
                    }
                    ctx.createDialog(title = "Last events", action = getString(R.string.close), message = text).showRespectingStatusBar()
                }
            }
        }
    }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")
    }
}
