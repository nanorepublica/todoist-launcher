package uk.co.softwarecrafts.contextlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class FakeClockTest {

    @Test
    fun `local time follows the configured zone`() {
        val clock = FakeClock(LocalDateTime.of(2026, 9, 30, 21, 0))
        assertEquals(LocalTime.of(21, 0), clock.localNow().toLocalTime())
        assertEquals(LocalDate.of(2026, 9, 30), clock.today())
    }

    @Test
    fun `advancing across midnight moves today`() {
        val clock = FakeClock(LocalDateTime.of(2026, 9, 30, 23, 30))
        clock.advance(Duration.ofMinutes(45))
        assertEquals(LocalDate.of(2026, 10, 1), clock.today())
        assertEquals(LocalTime.of(0, 15), clock.localNow().toLocalTime())
    }

    @Test
    fun `advancing across the autumn clock change keeps wall time honest`() {
        // BST ends 2026-10-25 at 02:00 BST, clocks go back to 01:00 GMT.
        val clock = FakeClock(LocalDateTime.of(2026, 10, 25, 0, 30))
        clock.advance(Duration.ofHours(2))
        assertEquals(LocalTime.of(1, 30), clock.localNow().toLocalTime())
    }
}
