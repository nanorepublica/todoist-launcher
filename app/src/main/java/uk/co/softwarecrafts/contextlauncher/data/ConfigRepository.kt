package uk.co.softwarecrafts.contextlauncher.data

import uk.co.softwarecrafts.contextlauncher.core.config.ConfigFormatException
import uk.co.softwarecrafts.contextlauncher.core.config.ConfigJson
import uk.co.softwarecrafts.contextlauncher.core.config.ConfigValidator
import uk.co.softwarecrafts.contextlauncher.core.config.LauncherConfig
import uk.co.softwarecrafts.contextlauncher.core.config.SeedConfig
import uk.co.softwarecrafts.contextlauncher.data.db.AlwaysAllowedEntity
import uk.co.softwarecrafts.contextlauncher.data.db.ConfigDao
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
        )
        return emptyList()
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
