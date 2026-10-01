package uk.co.softwarecrafts.contextlauncher.core.review

import uk.co.softwarecrafts.contextlauncher.core.config.AllowedApp
import uk.co.softwarecrafts.contextlauncher.core.config.LauncherConfig

/** A one-tap change to the configuration. Pure: returns the new config. */
sealed class ConfigChange {
    abstract fun apply(config: LauncherConfig): LauncherConfig

    data class SetStageCap(val stageId: String, val minutes: Int) : ConfigChange() {
        override fun apply(config: LauncherConfig) = config.copy(
            stages = config.stages.map { if (it.id == stageId) it.copy(maxBypassMinutes = minutes) else it },
        )
    }

    data class AllowInStage(val stageId: String, val packageName: String, val capMinutes: Int?) : ConfigChange() {
        override fun apply(config: LauncherConfig) = config.copy(
            stages = config.stages.map { stage ->
                if (stage.id != stageId || stage.allowedApps.any { it.packageName == packageName }) stage
                else stage.copy(allowedApps = stage.allowedApps + AllowedApp(packageName, capMinutes))
            },
        )
    }

    /** Lowers every cap this package has, in groups and stage lists alike. */
    data class LowerAppCap(val packageName: String, val minutes: Int) : ConfigChange() {
        override fun apply(config: LauncherConfig) = config.copy(
            appGroups = config.appGroups.map { g -> g.copy(apps = g.apps.map { lower(it) }) },
            stages = config.stages.map { s -> s.copy(allowedApps = s.allowedApps.map { lower(it) }) },
        )
        private fun lower(app: AllowedApp) =
            if (app.packageName == packageName && app.capMinutes != null) app.copy(capMinutes = minutes) else app
    }
}

data class Suggestion(val id: String, val text: String, val change: ConfigChange)

/**
 * Rule-based suggestions from one week's report (SPEC.md: "Instagram bypassed 6x
 * during work, averaging 12 min against a 5-min limit: lower the cap?").
 */
object SuggestionRules {
    const val MIN_BYPASSES = 3
    private val ladder = listOf(1, 2, 5, 10, 15, 30, 60)

    fun generate(config: LauncherConfig, report: ReviewReport, label: (String) -> String): List<Suggestion> {
        val out = mutableListOf<Suggestion>()
        for (b in report.bypasses) {
            if (b.count < MIN_BYPASSES) continue
            val stage = config.stage(b.stageId) ?: continue
            val app = label(b.packageName)
            val avg = b.averageLimit
            val cap = stage.maxBypassMinutes
            when {
                // The limit kept running out: tighten the stage's cap
                b.timesUp * 2 >= b.count && cap != null && cap > 1 -> {
                    val lower = stepDown(cap)
                    out += Suggestion(
                        "cap:${stage.id}:${b.packageName}",
                        "$app bypassed ${b.count}× during ${stage.name}, running out of time ${b.timesUp} times against a $cap-min limit: lower the cap to $lower min?",
                        ConfigChange.SetStageCap(stage.id, lower),
                    )
                }
                // No cap at all in this stage: put one in
                cap == null -> out += Suggestion(
                    "cap:${stage.id}:${b.packageName}",
                    "$app bypassed ${b.count}× during ${stage.name}, averaging ${"%.0f".format(avg)} min each with no limit: set a 15-min cap for ${stage.name}?",
                    ConfigChange.SetStageCap(stage.id, 15),
                )
                // Short, deliberate visits: stop asking and allow it with that cap
                else -> {
                    val chosen = b.limitsChosen.maxOrNull() ?: cap
                    out += Suggestion(
                        "allow:${stage.id}:${b.packageName}",
                        "$app bypassed ${b.count}× during ${stage.name} for short visits (up to $chosen min): allow it there with a $chosen-min cap?",
                        ConfigChange.AllowInStage(stage.id, b.packageName, chosen),
                    )
                }
            }
        }
        for (c in report.capped) {
            val cap = c.capMinutes ?: continue
            if (c.timesUp < MIN_BYPASSES || cap <= 1) continue
            val lower = stepDown(cap)
            out += Suggestion(
                "appcap:${c.packageName}",
                "${label(c.packageName)} hit its $cap-min cap ${c.timesUp} of ${c.opens} times: lower the cap to $lower min?",
                ConfigChange.LowerAppCap(c.packageName, lower),
            )
        }
        return out
    }

    fun stepDown(minutes: Int): Int = ladder.lastOrNull { it < minutes } ?: 1
}
