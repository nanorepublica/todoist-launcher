package uk.co.softwarecrafts.contextlauncher.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ConfigDao {
    @Query("SELECT * FROM stages ORDER BY sortOrder")
    suspend fun stages(): List<StageEntity>

    @Query("SELECT * FROM app_groups ORDER BY sortOrder")
    suspend fun appGroups(): List<AppGroupEntity>

    @Query("SELECT * FROM label_groups ORDER BY label")
    suspend fun labelGroups(): List<LabelGroupEntity>

    @Query("SELECT * FROM always_allowed ORDER BY sortOrder")
    suspend fun alwaysAllowed(): List<AlwaysAllowedEntity>

    @Query("SELECT * FROM settings")
    suspend fun settings(): List<SettingEntity>

    @Query("SELECT COUNT(*) FROM stages")
    suspend fun stageCount(): Int

    /** Emits whenever any stage row changes; the home screen will use this. */
    @Query("SELECT COUNT(*) FROM stages")
    fun stageCountFlow(): Flow<Int>

    @Upsert suspend fun upsertStage(stage: StageEntity)
    @Upsert suspend fun upsertGroup(group: AppGroupEntity)
    @Upsert suspend fun upsertSetting(setting: SettingEntity)

    @Query("DELETE FROM stages") suspend fun clearStages()
    @Query("DELETE FROM app_groups") suspend fun clearGroups()
    @Query("DELETE FROM label_groups") suspend fun clearLabelGroups()
    @Query("DELETE FROM always_allowed") suspend fun clearAlwaysAllowed()
    @Query("DELETE FROM settings") suspend fun clearSettings()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertStages(stages: List<StageEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertGroups(groups: List<AppGroupEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertLabelGroups(rows: List<LabelGroupEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAlwaysAllowed(rows: List<AlwaysAllowedEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSettings(rows: List<SettingEntity>)

    /** Replaces the whole configuration atomically. */
    @Transaction
    suspend fun replaceAll(
        stages: List<StageEntity>,
        groups: List<AppGroupEntity>,
        labelGroups: List<LabelGroupEntity>,
        alwaysAllowed: List<AlwaysAllowedEntity>,
        settings: List<SettingEntity>,
    ) {
        clearStages(); clearGroups(); clearLabelGroups(); clearAlwaysAllowed(); clearSettings()
        insertStages(stages); insertGroups(groups); insertLabelGroups(labelGroups)
        insertAlwaysAllowed(alwaysAllowed); insertSettings(settings)
    }
}

@Dao
interface EventDao {
    @Insert suspend fun insert(event: EventEntity): Long

    @Query("SELECT * FROM events WHERE at >= :fromMillis AND at < :toMillis ORDER BY at")
    suspend fun between(fromMillis: Long, toMillis: Long): List<EventEntity>

    @Query("SELECT * FROM events ORDER BY at DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<EventEntity>

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Int

    @Query("DELETE FROM events WHERE at < :beforeMillis")
    suspend fun deleteBefore(beforeMillis: Long): Int
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks")
    suspend fun all(): List<TaskEntity>

    @Query("SELECT * FROM tasks")
    fun observe(): Flow<List<TaskEntity>>

    @Query("DELETE FROM tasks") suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(rows: List<TaskEntity>)

    @Transaction
    suspend fun replaceAll(rows: List<TaskEntity>) {
        clear()
        insertAll(rows)
    }

    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    suspend fun state(key: String): String?

    @Upsert suspend fun putState(row: SyncStateEntity)

    @Query("DELETE FROM sync_state") suspend fun clearState()
}
