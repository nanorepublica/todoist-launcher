package uk.co.softwarecrafts.contextlauncher.core.stage

import java.time.Instant

/**
 * Remembers which stage block we are in so the app can reset per-block
 * counters (countdown escalation) and log stage changes. A block changes
 * when the winning stage changes or the same stage starts a new activation.
 */
class StageTracker {
    data class Block(val stageId: String, val since: Instant)
    data class Change(val from: Block?, val to: Block, val at: Instant)

    var block: Block? = null
        private set

    /** Feeds a new resolution in; returns a [Change] when the block moved on. */
    fun update(resolution: Resolution, at: Instant): Change? {
        val next = Block(resolution.stage.id, resolution.current.since)
        val previous = block
        if (previous == next) return null
        block = next
        return Change(previous, next, at)
    }
}
