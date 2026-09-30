package uk.co.softwarecrafts.contextlauncher.core.stage

import uk.co.softwarecrafts.contextlauncher.core.config.Stage
import java.time.Instant

/** Why a stage is active right now. */
sealed class Activation {
    abstract val stage: Stage
    /** When this activation began, for "per stage block" bookkeeping. */
    abstract val since: Instant

    data class CalendarBlock(override val stage: Stage, override val since: Instant, val until: Instant, val eventTitle: String) : Activation()
    data class FixedWindow(override val stage: Stage, override val since: Instant, val until: Instant) : Activation()
    data class TasksDone(override val stage: Stage, override val since: Instant, val until: Instant) : Activation()
    data class TaskDue(override val stage: Stage, override val since: Instant, val taskId: String) : Activation()
    data class Gap(override val stage: Stage, override val since: Instant) : Activation()
}

/** A perk (extra app group) that is open for a while after a stage's tasks were done. */
data class ActivePerk(val stageId: String, val groupId: String, val until: Instant)

/** The engine's answer for one moment in time. */
data class Resolution(
    /** The winning stage: highest rank among [active]. */
    val current: Activation,
    /** Every active stage, highest rank first. */
    val active: List<Activation>,
    /** The earliest moment the answer might change; null when nothing is scheduled. */
    val nextChangeAt: Instant?,
    val perks: List<ActivePerk>,
    /** App groups unlocked by open task-linked labels. */
    val taskLinkedGroups: Set<String>,
    /** Task ids that gate the current stage's early unlock, in the order given. */
    val gatingTaskIds: List<String>,
) {
    val stage: Stage get() = current.stage
}
