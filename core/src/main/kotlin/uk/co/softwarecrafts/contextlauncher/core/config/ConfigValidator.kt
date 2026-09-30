package uk.co.softwarecrafts.contextlauncher.core.config

/** Checks a config for problems that JSON decoding cannot catch. Empty result means valid. */
object ConfigValidator {

    fun validate(config: LauncherConfig): List<String> {
        val problems = mutableListOf<String>()
        val groupIds = config.appGroups.map { it.id }
        val stageIds = config.stages.map { it.id }

        if (config.version > LauncherConfig.CURRENT_VERSION)
            problems += "config version ${config.version} is newer than this app understands (${LauncherConfig.CURRENT_VERSION})"

        duplicates(stageIds).forEach { problems += "duplicate stage id '$it'" }
        duplicates(groupIds).forEach { problems += "duplicate app group id '$it'" }
        duplicates(config.labelGroups.map { it.label }).forEach { problems += "label '$it' is mapped more than once" }

        config.stages.forEach { stage ->
            if (stage.id.isBlank()) problems += "a stage has a blank id"
            if (stage.name.isBlank()) problems += "stage '${stage.id}' has a blank name"
            stage.allowedGroups.filter { it !in groupIds }
                .forEach { problems += "stage '${stage.id}' allows unknown group '$it'" }
            stage.onDonePerk?.let { perk ->
                if (perk.groupId !in groupIds) problems += "stage '${stage.id}' perk names unknown group '${perk.groupId}'"
                if (perk.minutes <= 0) problems += "stage '${stage.id}' perk minutes must be positive"
                if (stage.doneLabel == null) problems += "stage '${stage.id}' has a perk but no doneLabel to trigger it"
            }
            stage.maxBypassMinutes?.let { if (it <= 0) problems += "stage '${stage.id}' maxBypassMinutes must be positive or absent" }
            stage.allowedApps.forEach { checkApp(it, "stage '${stage.id}'", problems) }
            val trigger = stage.trigger
            when (trigger) {
                is StageTrigger.Calendar -> if (trigger.eventTitle.isBlank()) problems += "stage '${stage.id}' has a blank calendar title"
                is StageTrigger.TasksDone -> if (trigger.label.isBlank()) problems += "stage '${stage.id}' has a blank trigger label"
                is StageTrigger.TaskDue -> if (trigger.label.isBlank()) problems += "stage '${stage.id}' has a blank trigger label"
                is StageTrigger.FixedTime -> if (trigger.start == trigger.end) problems += "stage '${stage.id}' fixed window is empty"
                is StageTrigger.Default -> Unit
            }
        }

        DayKind.values().forEach { kind ->
            val defaults = config.stages.filter { it.enabled && (it.trigger as? StageTrigger.Default)?.days == kind }
            if (defaults.size > 1) problems += "more than one enabled default stage for $kind"
        }

        config.appGroups.forEach { group ->
            if (group.id.isBlank()) problems += "an app group has a blank id"
            group.apps.forEach { checkApp(it, "group '${group.id}'", problems) }
        }
        config.labelGroups.forEach {
            if (it.label.isBlank()) problems += "a label mapping has a blank label"
            if (it.label.startsWith("@")) problems += "label '${it.label}' should be stored without the leading @"
            if (it.groupId !in groupIds) problems += "label '${it.label}' maps to unknown group '${it.groupId}'"
        }
        if (config.alwaysAllowed.size > LauncherConfig.MAX_ALWAYS_ALLOWED)
            problems += "alwaysAllowed lists ${config.alwaysAllowed.size} apps; the limit is ${LauncherConfig.MAX_ALWAYS_ALLOWED}"
        duplicates(config.alwaysAllowed).forEach { problems += "'$it' is listed twice in alwaysAllowed" }
        return problems
    }

    private fun checkApp(app: AllowedApp, where: String, problems: MutableList<String>) {
        if (app.packageName.isBlank()) problems += "$where has an app with a blank package name"
        app.capMinutes?.let { if (it <= 0) problems += "$where: '${app.packageName}' capMinutes must be positive or absent" }
    }

    private fun duplicates(values: List<String>): Set<String> =
        values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
}
