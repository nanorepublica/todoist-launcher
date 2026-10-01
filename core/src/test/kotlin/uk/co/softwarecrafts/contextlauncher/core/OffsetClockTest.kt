package uk.co.softwarecrafts.contextlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

class OffsetClockTest {
    private val base = FakeClock(LocalDateTime.of(2026, 10, 1, 9, 0))

    @Test
    fun `set to a local time, keeps ticking, and resets`() {
        val clock = OffsetClock(base)
        assertFalse(clock.isShifted)
        clock.setLocal(LocalDateTime.of(2026, 10, 1, 19, 5))
        assertEquals(LocalTime.of(19, 5), clock.localNow().toLocalTime())
        base.advance(Duration.ofMinutes(10))
        assertEquals(LocalTime.of(19, 15), clock.localNow().toLocalTime())
        clock.advance(Duration.ofHours(2))
        assertEquals(LocalTime.of(21, 15), clock.localNow().toLocalTime())
        assertTrue(clock.isShifted)
        clock.reset()
        assertEquals(base.now(), clock.now())
    }
}
