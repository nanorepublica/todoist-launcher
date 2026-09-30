package uk.co.softwarecrafts.contextlauncher.core.gate

import java.time.Duration
import java.time.Instant

enum class SessionKind { BYPASS, CAPPED }

/** An app opened with a time limit. Only one runs at a time. */
data class TimedSession(
    val packageName: String,
    val stageId: String,
    val kind: SessionKind,
    val startedAt: Instant,
    val endsAt: Instant,
    val limitMinutes: Int,
    val bypassNumber: Int? = null,
    val reason: String? = null,
) {
    fun isExpired(now: Instant): Boolean = !now.isBefore(endsAt)
    fun remaining(now: Instant): Duration = Duration.between(now, endsAt).let { if (it.isNegative) Duration.ZERO else it }

    companion object {
        fun start(
            packageName: String, stageId: String, kind: SessionKind, now: Instant, limitMinutes: Int,
            bypassNumber: Int? = null, reason: String? = null,
        ) = TimedSession(packageName, stageId, kind, now, now.plusSeconds(limitMinutes * 60L), limitMinutes, bypassNumber, reason)
    }
}

/** What the enforcer should do when it learns which app is in front. */
enum class EnforcementAction { NOTHING, RETURN_HOME, END_SESSION }

object SessionRules {
    /**
     * [foregroundPackage] is the app in front, or null when unknown. An expired
     * session whose app is in front is sent home; an expired session whose app
     * is not in front just ends.
     */
    fun action(session: TimedSession?, foregroundPackage: String?, now: Instant): EnforcementAction {
        if (session == null || !session.isExpired(now)) return EnforcementAction.NOTHING
        return if (foregroundPackage == session.packageName) EnforcementAction.RETURN_HOME else EnforcementAction.END_SESSION
    }
}
