package uk.co.softwarecrafts.contextlauncher.core.gate

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class EscalationTest {

    @Test
    fun `countdown ladder is 0, 10, 15, 30, 30`() {
        assertEquals(listOf(0, 10, 15, 30, 30, 30), (1..6).map(Escalation::waitSeconds))
    }

    @Test
    fun `counter increments within a block and resets when the block changes`() {
        var counter = BypassCounter(BypassCounter.blockKey("work_am", 1000))
        val (first, c1) = counter.forBlock("work_am@1000").next()
        val (second, c2) = c1.forBlock("work_am@1000").next()
        assertEquals(1, first); assertEquals(2, second)
        val (afterChange, c3) = c2.forBlock("lunch@2000").next()
        assertEquals(1, afterChange)
        assertEquals("lunch@2000", c3.blockKey)
        // Same stage, new activation is a new block too
        assertEquals(1, c3.forBlock("lunch@3000").next().first)
    }

    @Test
    fun `time limit options never exceed the cap and always include it`() {
        assertEquals(listOf(1, 2, 5), TimeLimitOptions.minutes(5))
        assertEquals(listOf(1, 2, 5, 7), TimeLimitOptions.minutes(7))
        assertEquals(listOf(1, 2, 5, 10, 15, 30, 60), TimeLimitOptions.minutes(null))
        assertEquals(listOf(1, 2, 5, 10, 15, 30, 60), TimeLimitOptions.minutes(90))
        assertEquals(listOf(1), TimeLimitOptions.minutes(1))
    }

    @Test
    fun `sessions expire and the enforcer only sends home when the app is in front`() {
        val start = Instant.parse("2026-09-30T10:00:00Z")
        val session = TimedSession.start("com.instagram.android", "work_am", SessionKind.BYPASS, start, 5, bypassNumber = 2)
        assertEquals(Instant.parse("2026-09-30T10:05:00Z"), session.endsAt)
        assertEquals(false, session.isExpired(start.plusSeconds(299)))
        assertEquals(true, session.isExpired(start.plusSeconds(300)))
        assertEquals(EnforcementAction.NOTHING, SessionRules.action(session, "com.instagram.android", start.plusSeconds(10)))
        assertEquals(EnforcementAction.RETURN_HOME, SessionRules.action(session, "com.instagram.android", start.plusSeconds(300)))
        assertEquals(EnforcementAction.END_SESSION, SessionRules.action(session, "com.other", start.plusSeconds(300)))
        assertEquals(EnforcementAction.NOTHING, SessionRules.action(null, "x", start))
    }
}
