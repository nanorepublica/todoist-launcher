package uk.co.softwarecrafts.contextlauncher.data.db

import uk.co.softwarecrafts.contextlauncher.core.config.AppGroup
import uk.co.softwarecrafts.contextlauncher.core.config.ConfigJson
import uk.co.softwarecrafts.contextlauncher.core.config.GeneralSettings
import uk.co.softwarecrafts.contextlauncher.core.config.LabelGroup
import uk.co.softwarecrafts.contextlauncher.core.config.LauncherConfig
import uk.co.softwarecrafts.contextlauncher.core.config.Perk
import uk.co.softwarecrafts.contextlauncher.core.config.Stage
import uk.co.softwarecrafts.contextlauncher.core.log.EventType
import uk.co.softwarecrafts.contextlauncher.core.log.LogEvent
import java.time.Instant

/** Core model <-> Room rows. Pure functions, unit-tested without Android. */
object Mappers {

    private const val KEY_CALENDAR_NAME = "calendarName"

    fun toEntity(stage: Stage, sortOrder: Int) = StageEntity(
        id = stage.id,
        name = stage.name,
        trigger = ConfigJson.encodeTrigger(stage.trigger),
        rank = stage.rank,
        doneLabel = stage.doneLabel,
        maxBypassMinutes = stage.maxBypassMinutes,
        allowedApps = ConfigJson.encodeApps(stage.allowedApps),
        allowedGroups = ConfigJson.encodeStrings(stage.allowedGroups),
        perkGroupId = stage.onDonePerk?.groupId,
        perkMinutes = stage.onDonePerk?.minutes,
        enabled = stage.enabled,
        sortOrder = sortOrder,
    )

    fun toStage(row: StageEntity) = Stage(
        id = row.id,
        name = row.name,
        trigger = ConfigJson.decodeTrigger(row.trigger),
        rank = row.rank,
        doneLabel = row.doneLabel,
        maxBypassMinutes = row.maxBypassMinutes,
        allowedApps = ConfigJson.decodeApps(row.allowedApps),
        allowedGroups = ConfigJson.decodeStrings(row.allowedGroups),
        onDonePerk = if (row.perkGroupId != null && row.perkMinutes != null) Perk(row.perkGroupId, row.perkMinutes) else null,
        enabled = row.enabled,
    )

    fun toEntity(group: AppGroup, sortOrder: Int) =
        AppGroupEntity(id = group.id, name = group.name, apps = ConfigJson.encodeApps(group.apps), sortOrder = sortOrder)

    fun toGroup(row: AppGroupEntity) = AppGroup(id = row.id, name = row.name, apps = ConfigJson.decodeApps(row.apps))

    fun toSettingRows(settings: GeneralSettings): List<SettingEntity> =
        listOf(SettingEntity(KEY_CALENDAR_NAME, settings.calendarName))

    fun toSettings(rows: List<SettingEntity>): GeneralSettings {
        val byKey = rows.associate { it.key to it.value }
        return GeneralSettings(calendarName = byKey[KEY_CALENDAR_NAME])
    }

    fun toConfig(
        stages: List<StageEntity>,
        groups: List<AppGroupEntity>,
        labelGroups: List<LabelGroupEntity>,
        alwaysAllowed: List<AlwaysAllowedEntity>,
        settings: List<SettingEntity>,
    ) = LauncherConfig(
        stages = stages.map(::toStage),
        appGroups = groups.map(::toGroup),
        labelGroups = labelGroups.map { LabelGroup(it.label, it.groupId) },
        alwaysAllowed = alwaysAllowed.map { it.packageName },
        settings = toSettings(settings),
    )

    fun toEntity(event: LogEvent) = EventEntity(
        id = event.id,
        at = event.at.toEpochMilli(),
        type = event.type.name,
        stageId = event.stageId,
        packageName = event.packageName,
        reason = event.reason,
        limitMinutes = event.limitMinutes,
        bypassNumber = event.bypassNumber,
        detail = event.detail,
    )

    fun toEvent(row: EventEntity) = LogEvent(
        id = row.id,
        at = Instant.ofEpochMilli(row.at),
        type = EventType.valueOf(row.type),
        stageId = row.stageId,
        packageName = row.packageName,
        reason = row.reason,
        limitMinutes = row.limitMinutes,
        bypassNumber = row.bypassNumber,
        detail = row.detail,
    )
}
