package uk.co.softwarecrafts.contextlauncher

import android.content.Context
import uk.co.softwarecrafts.contextlauncher.calendar.CalendarStore
import uk.co.softwarecrafts.contextlauncher.core.Clock
import uk.co.softwarecrafts.contextlauncher.core.SystemClock
import uk.co.softwarecrafts.contextlauncher.data.ConfigRepository
import uk.co.softwarecrafts.contextlauncher.data.EventLogRepository
import uk.co.softwarecrafts.contextlauncher.data.db.AppDatabase
import uk.co.softwarecrafts.contextlauncher.engine.StageEngine

/**
 * Process-wide singletons, built lazily on first use. A hand-rolled service
 * locator instead of a DI framework: the app has a handful of dependencies
 * and one process.
 */
object Graph {
    @Volatile private var database: AppDatabase? = null
    @Volatile private var engine: StageEngine? = null

    val clock: Clock = SystemClock()

    fun db(context: Context): AppDatabase =
        database ?: synchronized(this) {
            database ?: AppDatabase.build(context).also { database = it }
        }

    fun config(context: Context) = ConfigRepository(db(context).configDao())
    fun eventLog(context: Context) = EventLogRepository(db(context).eventDao())
    fun calendar(context: Context) = CalendarStore(context.applicationContext)

    fun stageEngine(context: Context): StageEngine =
        engine ?: synchronized(this) {
            engine ?: StageEngine(
                context = context.applicationContext,
                clock = clock,
                config = config(context),
                eventLog = eventLog(context),
                calendar = calendar(context),
            ).also { engine = it }
        }
}
