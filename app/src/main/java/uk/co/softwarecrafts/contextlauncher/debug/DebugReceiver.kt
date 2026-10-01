package uk.co.softwarecrafts.contextlauncher.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uk.co.softwarecrafts.contextlauncher.Graph
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/**
 * adb entry point, declared only in the debug manifest:
 *
 *   adb shell am broadcast -a uk.co.softwarecrafts.contextlauncher.DEBUG \
 *       -n uk.co.softwarecrafts.contextlauncher.debug/uk.co.softwarecrafts.contextlauncher.debug.DebugReceiver \
 *       --es time 19:05            # today at 19:05 (shifted day if a date was set)
 *       --es date 2026-10-03       # keep the current time of day, move the date
 *       --es advance 15m           # 15m, 2h, 1d
 *       --ez reset true            # back to real time
 *       --es scenario stage_change # run a scenario by id (see Scenarios.kt)
 *       --ez cleanup true          # delete the "(launcher test)" tasks
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!DebugTime.enabled) return
        val app = context.applicationContext
        val now = Graph.clock.localNow()
        var message: String? = null
        if (intent.getBooleanExtra("reset", false)) { DebugTime.reset(app); message = "clock: real time" }
        intent.getStringExtra("date")?.let { d ->
            runCatching { LocalDate.parse(d) }.onSuccess { DebugTime.setLocal(app, it.atTime(now.toLocalTime())); message = "clock: $it" }
        }
        intent.getStringExtra("time")?.let { t ->
            runCatching { LocalTime.parse(t) }.onSuccess { DebugTime.setLocal(app, Graph.clock.today().atTime(it)); message = "clock: ${Graph.clock.localNow()}" }
        }
        intent.getStringExtra("advance")?.let { a ->
            parseDuration(a)?.let { DebugTime.advance(app, it); message = "clock: ${Graph.clock.localNow()}" }
        }
        val scenario = intent.getStringExtra("scenario")
        val cleanup = intent.getBooleanExtra("cleanup", false)
        if (scenario != null || cleanup) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                val result = runCatching {
                    if (cleanup) Scenarios.cleanUp(app) else Scenarios.run(app, scenario!!)
                }.getOrElse { "failed: ${it.message}" }
                Log.i(TAG, result)
                pending.finish()
            }
            return
        }
        message?.let { Log.i(TAG, it); Toast.makeText(app, it, Toast.LENGTH_SHORT).show() }
    }

    private fun parseDuration(text: String): Duration? {
        val m = Regex("""(-?\d+)\s*([mhd])""").matchEntire(text.trim()) ?: return null
        val n = m.groupValues[1].toLong()
        return when (m.groupValues[2]) { "m" -> Duration.ofMinutes(n); "h" -> Duration.ofHours(n); else -> Duration.ofDays(n) }
    }

    private companion object { const val TAG = "ContextLauncherDebug" }
}
