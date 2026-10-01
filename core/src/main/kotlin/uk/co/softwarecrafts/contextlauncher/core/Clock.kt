package uk.co.softwarecrafts.contextlauncher.core

import java.time.Duration
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

/**
 * A clock shifted by a settable offset, so a debug build can run the day at any
 * hour. Time keeps ticking at the shifted position. Zero offset is the real clock.
 */
class OffsetClock(private val base: Clock = SystemClock()) : Clock {
    @Volatile var offset: Duration = Duration.ZERO
    override val zone: ZoneId get() = base.zone
    override fun now(): Instant = base.now().plus(offset)

    /** Shifts so that [target] is now, in this zone. */
    fun setLocal(target: LocalDateTime) {
        offset = Duration.between(base.now(), target.atZone(zone).toInstant())
    }

    fun advance(by: Duration) { offset = offset.plus(by) }
    fun reset() { offset = Duration.ZERO }
    val isShifted: Boolean get() = !offset.isZero
}
