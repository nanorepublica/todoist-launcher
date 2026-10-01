package uk.co.softwarecrafts.contextlauncher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import uk.co.softwarecrafts.contextlauncher.core.gate.BypassCounter
import uk.co.softwarecrafts.contextlauncher.core.gate.SessionKind
import uk.co.softwarecrafts.contextlauncher.core.gate.TimedSession
import java.time.Instant

/** Small flags and transient gate state that are not part of the exportable config. */
class AppPrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("context_launcher", Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = prefs.getBoolean(ONBOARDING_DONE, false)
        set(value) = prefs.edit { putBoolean(ONBOARDING_DONE, value) }

    /** The @phone/review task the review screen was last opened for automatically, so it opens once per due task. */
    var reviewOpenedForTask: String?
        get() = prefs.getString(REVIEW_OPENED_TASK, null)
        set(value) = prefs.edit { if (value == null) remove(REVIEW_OPENED_TASK) else putString(REVIEW_OPENED_TASK, value) }

    /** Debug builds only: the clock shift in milliseconds, so it survives a restart. */
    var debugClockOffsetMs: Long
        get() = prefs.getLong(DEBUG_OFFSET, 0L)
        set(value) = prefs.edit { if (value == 0L) remove(DEBUG_OFFSET) else putLong(DEBUG_OFFSET, value) }

    fun loadBypassCounter(): BypassCounter =
        BypassCounter(prefs.getString(COUNTER_KEY, "") ?: "", prefs.getInt(COUNTER_COUNT, 0))

    fun saveBypassCounter(counter: BypassCounter) = prefs.edit {
        putString(COUNTER_KEY, counter.blockKey)
        putInt(COUNTER_COUNT, counter.count)
    }

    fun loadSession(): TimedSession? {
        val pkg = prefs.getString(S_PACKAGE, null) ?: return null
        return TimedSession(
            packageName = pkg,
            stageId = prefs.getString(S_STAGE, "") ?: "",
            kind = runCatching { SessionKind.valueOf(prefs.getString(S_KIND, "BYPASS") ?: "BYPASS") }.getOrDefault(SessionKind.BYPASS),
            startedAt = Instant.ofEpochMilli(prefs.getLong(S_START, 0)),
            endsAt = Instant.ofEpochMilli(prefs.getLong(S_END, 0)),
            limitMinutes = prefs.getInt(S_LIMIT, 0),
            bypassNumber = prefs.getInt(S_NUMBER, -1).takeIf { it >= 0 },
            reason = prefs.getString(S_REASON, null),
        )
    }

    fun saveSession(session: TimedSession?) = prefs.edit {
        if (session == null) {
            listOf(S_PACKAGE, S_STAGE, S_KIND, S_START, S_END, S_LIMIT, S_NUMBER, S_REASON).forEach { remove(it) }
        } else {
            putString(S_PACKAGE, session.packageName)
            putString(S_STAGE, session.stageId)
            putString(S_KIND, session.kind.name)
            putLong(S_START, session.startedAt.toEpochMilli())
            putLong(S_END, session.endsAt.toEpochMilli())
            putInt(S_LIMIT, session.limitMinutes)
            putInt(S_NUMBER, session.bypassNumber ?: -1)
            if (session.reason != null) putString(S_REASON, session.reason) else remove(S_REASON)
        }
    }

    private companion object {
        const val ONBOARDING_DONE = "onboarding_done"
        const val REVIEW_OPENED_TASK = "review_opened_task"
        const val DEBUG_OFFSET = "debug_clock_offset_ms"
        const val COUNTER_KEY = "bypass_block"
        const val COUNTER_COUNT = "bypass_count"
        const val S_PACKAGE = "session_package"
        const val S_STAGE = "session_stage"
        const val S_KIND = "session_kind"
        const val S_START = "session_start"
        const val S_END = "session_end"
        const val S_LIMIT = "session_limit"
        const val S_NUMBER = "session_number"
        const val S_REASON = "session_reason"
    }
}
