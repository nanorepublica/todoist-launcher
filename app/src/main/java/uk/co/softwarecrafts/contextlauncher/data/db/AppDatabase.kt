package uk.co.softwarecrafts.contextlauncher.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The launcher's SQLite database. Bump [VERSION] and add a Migration in
 * [build] whenever an entity changes; the schema JSON under app/schemas is
 * the committed history.
 */
@Database(
    entities = [
        StageEntity::class,
        AppGroupEntity::class,
        LabelGroupEntity::class,
        AlwaysAllowedEntity::class,
        SettingEntity::class,
        EventEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun configDao(): ConfigDao
    abstract fun eventDao(): EventDao

    companion object {
        const val VERSION = 1
        const val NAME = "context_launcher.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .build()
    }
}
