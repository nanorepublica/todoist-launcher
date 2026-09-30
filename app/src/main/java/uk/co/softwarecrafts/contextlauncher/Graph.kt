package uk.co.softwarecrafts.contextlauncher

import android.content.Context
import uk.co.softwarecrafts.contextlauncher.core.Clock
import uk.co.softwarecrafts.contextlauncher.core.SystemClock
import uk.co.softwarecrafts.contextlauncher.data.ConfigRepository
import uk.co.softwarecrafts.contextlauncher.data.EventLogRepository
import uk.co.softwarecrafts.contextlauncher.data.db.AppDatabase

/**
 * Process-wide singletons, built lazily on first use. A hand-rolled service
 * locator instead of a DI framework: the app has a handful of dependencies
 * and one process.
 */
object Graph {
    @Volatile private var database: AppDatabase? = null

    val clock: Clock = SystemClock()

    fun db(context: Context): AppDatabase =
        database ?: synchronized(this) {
            database ?: AppDatabase.build(context).also { database = it }
        }

    fun config(context: Context) = ConfigRepository(db(context).configDao())
    fun eventLog(context: Context) = EventLogRepository(db(context).eventDao())
}
