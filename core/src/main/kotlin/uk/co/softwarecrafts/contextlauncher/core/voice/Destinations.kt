package uk.co.softwarecrafts.contextlauncher.core.voice

import uk.co.softwarecrafts.contextlauncher.core.config.LauncherConfig

/** Where a "speak" transcript can go. */
enum class Destination { TASK, CLAUDE, COPY }

object Destinations {
    /** The destinations offered during [stageId]; Claude is hidden in the configured stages (wind-down). */
    fun available(config: LauncherConfig, stageId: String?, claudeInstalled: Boolean): List<Destination> =
        Destination.values().filter { d ->
            when (d) {
                Destination.CLAUDE -> claudeInstalled && stageId !in config.settings.claudeHiddenStages
                else -> true
            }
        }
}
