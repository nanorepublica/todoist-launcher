package uk.co.softwarecrafts.contextlauncher.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Injectable time source. Everything in `core` that needs "now" takes a [Clock]
 * so tests can pin the engine to any moment of any day.
 */
interface Clock {
    fun now(): Instant
    val zone: ZoneId

    fun localNow(): LocalDateTime = LocalDateTime.ofInstant(now(), zone)
    fun today(): LocalDate = localNow().toLocalDate()
}

/** The real clock, used by the app. */
class SystemClock(override val zone: ZoneId = ZoneId.systemDefault()) : Clock {
    override fun now(): Instant = Instant.now()
}
