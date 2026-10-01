package uk.co.softwarecrafts.contextlauncher.debug

import android.content.Context
import app.olauncher.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uk.co.softwarecrafts.contextlauncher.Graph
import uk.co.softwarecrafts.contextlauncher.data.AppPrefs
import java.time.Duration
import java.time.LocalDateTime

/**
 * Debug-build time travel. Shifts [Graph.clock], persists the shift, and pokes
 * the engine and gate so the home screen follows at once. Release builds keep
 * the real clock: every entry point is a no-op unless BuildConfig.DEBUG.
 */
object DebugTime {
    private val _changes = MutableStateFlow(0L)
    /** Bumps whenever the shift changes, so screens showing the time can re-render. */
    val changes: StateFlow<Long> = _changes

    val enabled: Boolean get() = BuildConfig.DEBUG

    fun restore(context: Context) {
        if (!enabled) return
        val ms = AppPrefs(context).debugClockOffsetMs
        if (ms != 0L) Graph.clock.offset = Duration.ofMillis(ms)
    }

    fun setLocal(context: Context, target: LocalDateTime) = change(context) { Graph.clock.setLocal(target) }
    fun advance(context: Context, by: Duration) = change(context) { Graph.clock.advance(by) }
    fun reset(context: Context) = change(context) { Graph.clock.reset() }

    private fun change(context: Context, block: () -> Unit) {
        if (!enabled) return
        val app = context.applicationContext
        block()
        AppPrefs(app).debugClockOffsetMs = Graph.clock.offset.toMillis()
        _changes.value = _changes.value + 1
        Graph.stageEngine(app).refresh()
        Graph.gate(app).onLauncherResumed()
    }

    fun describe(): String {
        val o = Graph.clock.offset
        if (o.isZero) return "real time"
        val sign = if (o.isNegative) "-" else "+"
        val abs = o.abs()
        val days = abs.toDays()
        val h = abs.toHours() % 24
        val m = abs.toMinutes() % 60
        return buildString {
            append(sign)
            if (days > 0) append(days).append("d ")
            append(h).append("h ").append(m).append("m")
        }
    }
}
