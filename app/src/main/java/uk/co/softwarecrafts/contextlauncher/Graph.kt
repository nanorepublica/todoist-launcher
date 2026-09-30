package uk.co.softwarecrafts.contextlauncher

import android.content.Context
import uk.co.softwarecrafts.contextlauncher.calendar.CalendarStore
import uk.co.softwarecrafts.contextlauncher.core.Clock
import uk.co.softwarecrafts.contextlauncher.core.SystemClock
import uk.co.softwarecrafts.contextlauncher.data.ConfigRepository
import uk.co.softwarecrafts.contextlauncher.data.EventLogRepository
import uk.co.softwarecrafts.contextlauncher.data.db.AppDatabase
import uk.co.softwarecrafts.contextlauncher.data.todoist.TodoistApi
import uk.co.softwarecrafts.contextlauncher.data.todoist.TodoistRepository
import uk.co.softwarecrafts.contextlauncher.data.todoist.TokenStore
import uk.co.softwarecrafts.contextlauncher.engine.StageEngine
import uk.co.softwarecrafts.contextlauncher.gate.GateController
import uk.co.softwarecrafts.contextlauncher.data.AppPrefs

/**
 * Process-wide singletons, built lazily on first use. A hand-rolled service
 * locator instead of a DI framework: the app has a handful of dependencies
 * and one process.
 */
object Graph {
    @Volatile private var database: AppDatabase? = null
    @Volatile private var engine: StageEngine? = null
    @Volatile private var todoist: TodoistRepository? = null
    @Volatile private var gate: GateController? = null

    val clock: Clock = SystemClock()

    fun db(context: Context): AppDatabase =
        database ?: synchronized(this) {
            database ?: AppDatabase.build(context).also { database = it }
        }

    /** Tests only: drop every singleton so the next test starts from a fresh process state. */
    fun resetForTests() {
        synchronized(this) {
            engine?.close()
            engine = null
            todoist = null
            gate = null
            database?.close()
            database = null
        }
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
            ).also { created ->
                engine = created
                created.taskSource = todoist(context)
            }
        }

    fun gate(context: Context): GateController =
        gate ?: synchronized(this) {
            gate ?: GateController(
                context = context.applicationContext,
                clock = clock,
                config = config(context),
                engine = stageEngine(context),
                eventLog = eventLog(context),
                prefs = AppPrefs(context.applicationContext),
            ).also { gate = it }
        }

    fun todoist(context: Context): TodoistRepository =
        todoist ?: synchronized(this) {
            todoist ?: TodoistRepository(
                api = TodoistApi(),
                tokens = TokenStore(context.applicationContext),
                dao = db(context).taskDao(),
                config = config(context),
                eventLog = eventLog(context),
                clock = clock,
                onTasksChanged = { engine?.refresh() },
            ).also { todoist = it }
        }
}
