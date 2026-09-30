package uk.co.softwarecrafts.contextlauncher.core.gate

/** SPEC.md: countdown per stage block: 1st bypass no wait, 2nd 10 s, 3rd 15 s, 4th+ 30 s. */
object Escalation {
    fun waitSeconds(bypassNumber: Int): Int = when {
        bypassNumber <= 1 -> 0
        bypassNumber == 2 -> 10
        bypassNumber == 3 -> 15
        else -> 30
    }
}

/**
 * Counts bypasses within one stage block. State is a plain value so the app
 * can persist it across process death.
 */
data class BypassCounter(val blockKey: String, val count: Int = 0) {

    /** The counter for [key]: this one if the block is unchanged, a fresh one otherwise. */
    fun forBlock(key: String): BypassCounter = if (key == blockKey) this else BypassCounter(key)

    /** The next bypass's number and the state after taking it. */
    fun next(): Pair<Int, BypassCounter> {
        val number = count + 1
        return number to copy(count = number)
    }

    companion object {
        fun blockKey(stageId: String, sinceEpochMillis: Long) = "$stageId@$sinceEpochMillis"
    }
}

/** The time-limit choices the friction screen offers, never above the stage's cap. */
object TimeLimitOptions {
    private val ladder = listOf(1, 2, 5, 10, 15, 30, 60)

    fun minutes(maxMinutes: Int?): List<Int> {
        if (maxMinutes == null) return ladder
        val within = ladder.filter { it <= maxMinutes }
        return if (within.lastOrNull() == maxMinutes || maxMinutes > ladder.last()) within else within + maxMinutes
    }
}
