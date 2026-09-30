package uk.co.softwarecrafts.contextlauncher.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room tables. Lists and the trigger are stored as JSON text columns through
 * [Converters] (think Django JSONField); everything else is a plain column.
 */
@Entity(tableName = "stages")
data class StageEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** JSON of core StageTrigger. */
    val trigger: String,
    val rank: Int,
    val doneLabel: String?,
    val maxBypassMinutes: Int?,
    /** JSON list of core AllowedApp. */
    val allowedApps: String,
    /** JSON list of group ids. */
    val allowedGroups: String,
    val perkGroupId: String?,
    val perkMinutes: Int?,
    val enabled: Boolean,
    /** Position in the config file, so export order is stable. */
    val sortOrder: Int,
)

@Entity(tableName = "app_groups")
data class AppGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** JSON list of core AllowedApp. */
    val apps: String,
    val sortOrder: Int,
    /** core GroupKind name. */
    @ColumnInfo(defaultValue = "NORMAL") val kind: String = "NORMAL",
)

/** Background-only apps that never appear in the app list. */
@Entity(tableName = "hidden_apps")
data class HiddenAppEntity(
    @PrimaryKey val packageName: String,
    val sortOrder: Int,
)

@Entity(tableName = "label_groups")
data class LabelGroupEntity(
    @PrimaryKey val label: String,
    val groupId: String,
)

@Entity(tableName = "always_allowed")
data class AlwaysAllowedEntity(
    @PrimaryKey val packageName: String,
    val sortOrder: Int,
)

/** Small key/value table for the scalar settings in GeneralSettings. */
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String?,
)

@Entity(tableName = "events", indices = [Index("at"), Index("type")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Epoch millis. */
    val at: Long,
    val type: String,
    val stageId: String?,
    val packageName: String?,
    val reason: String?,
    val limitMinutes: Int?,
    val bypassNumber: Int?,
    val detail: String?,
)

/** Cached Todoist item: the raw API JSON plus two queryable columns. */
@Entity(tableName = "tasks", indices = [Index("dueDate"), Index("completed")])
data class TaskEntity(
    @PrimaryKey val id: String,
    val json: String,
    /** "YYYY-MM-DD" in the user's zone, null when undated. */
    val dueDate: String?,
    val completed: Boolean,
)

/** Todoist sync bookkeeping (sync token, last sync time). Not exported with the config. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    val value: String,
)
