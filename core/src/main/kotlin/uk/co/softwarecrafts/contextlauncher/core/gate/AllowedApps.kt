package uk.co.softwarecrafts.contextlauncher.core.gate

import uk.co.softwarecrafts.contextlauncher.core.config.GroupKind
import uk.co.softwarecrafts.contextlauncher.core.config.LauncherConfig
import uk.co.softwarecrafts.contextlauncher.core.stage.Resolution

/** Where an allowance came from, for the home screen and the log. */
enum class AllowSource { STAGE, GROUP, TASK_LINKED, PERK, ALWAYS, PHONE_CAMERA, UNRESTRICTED }

/** One app the current stage lets through, with its fixed cap when it has one. */
data class AllowedEntry(val packageName: String, val capMinutes: Int?, val source: AllowSource)

/** What happens when a package is launched from the launcher. */
sealed class Decision {
    /** Open it, no timer. */
    object Allowed : Decision()
    /** Open it with a fixed timer; not a bypass. */
    data class AllowedCapped(val minutes: Int) : Decision()
    /** Off-list: the friction screen decides. [maxMinutes] null means the stage sets no limit. */
    data class Friction(val maxMinutes: Int?) : Decision()
}

/**
 * Builds the allowlist for a resolution and answers launch decisions. Pure:
 * the app passes in the packages it resolved for Phone and Camera.
 */
object Gatekeeper {

    fun allowed(config: LauncherConfig, resolution: Resolution, phoneAndCamera: Set<String>): List<AllowedEntry> {
        val stage = resolution.stage
        val entries = mutableListOf<AllowedEntry>()
        stage.allowedApps.forEach { entries += AllowedEntry(it.packageName, it.capMinutes, AllowSource.STAGE) }
        stage.allowedGroups.forEach { groupId ->
            config.group(groupId)?.apps?.forEach { entries += AllowedEntry(it.packageName, it.capMinutes, AllowSource.GROUP) }
        }
        resolution.taskLinkedGroups.forEach { groupId ->
            config.group(groupId)?.apps?.forEach { entries += AllowedEntry(it.packageName, it.capMinutes, AllowSource.TASK_LINKED) }
        }
        resolution.perks.forEach { perk ->
            config.group(perk.groupId)?.apps?.forEach { entries += AllowedEntry(it.packageName, it.capMinutes, AllowSource.PERK) }
        }
        config.alwaysAllowed.forEach { entries += AllowedEntry(it, null, AllowSource.ALWAYS) }
        config.groups(GroupKind.UNRESTRICTED).forEach { group ->
            group.apps.forEach { entries += AllowedEntry(it.packageName, null, AllowSource.UNRESTRICTED) }
        }
        phoneAndCamera.forEach { entries += AllowedEntry(it, null, AllowSource.PHONE_CAMERA) }
        return dedupe(entries)
    }

    /** One entry per package; an uncapped allowance beats a capped one, and the first source is kept. */
    private fun dedupe(entries: List<AllowedEntry>): List<AllowedEntry> {
        val byPackage = LinkedHashMap<String, AllowedEntry>()
        for (entry in entries) {
            val current = byPackage[entry.packageName]
            byPackage[entry.packageName] = when {
                current == null -> entry
                current.capMinutes == null -> current
                entry.capMinutes == null -> entry.copy(source = current.source)
                else -> if (entry.capMinutes > current.capMinutes) entry.copy(source = current.source) else current
            }
        }
        return byPackage.values.toList()
    }

    fun decide(packageName: String, allowed: List<AllowedEntry>, resolution: Resolution): Decision {
        val entry = allowed.firstOrNull { it.packageName == packageName }
            ?: return Decision.Friction(resolution.stage.maxBypassMinutes)
        val cap = entry.capMinutes ?: return Decision.Allowed
        return Decision.AllowedCapped(cap)
    }
}
