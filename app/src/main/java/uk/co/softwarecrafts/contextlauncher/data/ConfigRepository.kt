package uk.co.softwarecrafts.contextlauncher.data

import uk.co.softwarecrafts.contextlauncher.core.config.AllowedApp
import uk.co.softwarecrafts.contextlauncher.core.config.AppGroup
import uk.co.softwarecrafts.contextlauncher.core.config.ConfigFormatException
import uk.co.softwarecrafts.contextlauncher.core.config.GroupKind
import uk.co.softwarecrafts.contextlauncher.core.config.ConfigJson
import uk.co.softwarecrafts.contextlauncher.core.config.ConfigValidator
import uk.co.softwarecrafts.contextlauncher.core.config.LauncherConfig
import uk.co.softwarecrafts.contextlauncher.core.config.SeedConfig
import uk.co.softwarecrafts.contextlauncher.data.db.AlwaysAllowedEntity
import uk.co.softwarecrafts.contextlauncher.data.db.ConfigDao
import uk.co.softwarecrafts.contextlauncher.data.db.HiddenAppEntity
import uk.co.softwarecrafts.contextlauncher.data.db.LabelGroupEntity
import uk.co.softwarecrafts.contextlauncher.data.db.Mappers

/** The only way the app reads or writes configuration. */
class ConfigRepository(private val dao: ConfigDao) {

    suspend fun load(): LauncherConfig = Mappers.toConfig(
        stages = dao.stages(),
        groups = dao.appGroups(),
        labelGroups = dao.labelGroups(),
        alwaysAllowed = dao.alwaysAllowed(),
        settings = dao.settings(),
        hiddenApps = dao.hiddenApps(),
    )

    /** Validates, then replaces everything in one transaction. Returns validation problems, empty on success. */
    suspend fun save(config: LauncherConfig): List<String> {
        val problems = ConfigValidator.validate(config)
        if (problems.isNotEmpty()) return problems
        dao.replaceAll(
            stages = config.stages.mapIndexed { i, s -> Mappers.toEntity(s, i) },
            groups = config.appGroups.mapIndexed { i, g -> Mappers.toEntity(g, i) },
            labelGroups = config.labelGroups.map { LabelGroupEntity(it.label, it.groupId) },
            alwaysAllowed = config.alwaysAllowed.mapIndexed { i, p -> AlwaysAllowedEntity(p, i) },
            settings = Mappers.toSettingRows(config.settings),
            hiddenApps = config.hiddenApps.mapIndexed { i, p -> HiddenAppEntity(p, i) },
        )
        return emptyList()
    }

    /** Load, change, validate, save. Returns validation problems, empty on success. */
    suspend fun update(change: (LauncherConfig) -> LauncherConfig): List<String> = save(change(load()))

    suspend fun setHidden(packageName: String, hidden: Boolean) = update { cfg ->
        val without = cfg.hiddenApps - packageName
        cfg.copy(hiddenApps = if (hidden) without + packageName else without)
    }

    /**
     * Adds or removes a package from the one group of the given kind, creating
     * the group when it does not exist yet. Returns true when the package is a
     * member afterwards.
     */
    suspend fun toggleKindGroup(packageName: String, kind: GroupKind, defaultId: String, defaultName: String): Boolean {
        var member = false
        update { cfg ->
            val group = cfg.groups(kind).firstOrNull() ?: AppGroup(defaultId, defaultName, emptyList(), kind)
            val present = group.apps.any { it.packageName == packageName }
            val apps = if (present) group.apps.filterNot { it.packageName == packageName } else group.apps + AllowedApp(packageName)
            member = !present
            val updated = group.copy(apps = apps)
            val others = cfg.appGroups.filterNot { it.id == group.id }
            cfg.copy(appGroups = if (cfg.appGroups.any { it.id == group.id }) cfg.appGroups.map { if (it.id == group.id) updated else it } else others + updated)
        }
        return member
    }

    /** Writes the seed on first run. */
    suspend fun seedIfEmpty(): Boolean {
        if (dao.stageCount() > 0) return false
        save(SeedConfig.default())
        return true
    }

    suspend fun resetToSeed() {
        save(SeedConfig.default())
    }

    suspend fun exportJson(): String = ConfigJson.encode(load())

    /**
     * Parses and saves a JSON config. Returns the problems found; the database
     * is untouched unless the list is empty.
     */
    suspend fun importJson(text: String): List<String> {
        val config = try {
            ConfigJson.decode(text)
        } catch (e: ConfigFormatException) {
            return listOf("Not a valid config file: ${e.message}")
        }
        return save(config)
    }
}
