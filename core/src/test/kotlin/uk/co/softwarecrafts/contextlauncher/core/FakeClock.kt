package uk.co.softwarecrafts.contextlauncher.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** A clock the tests can set and advance by hand. */
class FakeClock(
    start: LocalDateTime,
    override val zone: ZoneId = ZoneId.of("Europe/London"),
) : Clock {
    private var current: Instant = start.atZone(zone).toInstant()

    override fun now(): Instant = current

    fun advance(by: Duration) {
        current = current.plus(by)
    }

    fun set(to: LocalDateTime) {
        current = to.atZone(zone).toInstant()
    }
}
