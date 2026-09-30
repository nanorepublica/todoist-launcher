package uk.co.softwarecrafts.contextlauncher.data.todoist

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import uk.co.softwarecrafts.contextlauncher.Graph
import java.util.concurrent.TimeUnit

/** Background refresh every 15 minutes (WorkManager's minimum), network permitting. */
class TodoistSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = Graph.todoist(applicationContext)
        if (!repo.hasToken) return Result.success()
        return when (repo.sync()) {
            is SyncStatus.Failed -> if (runAttemptCount < 3) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }

    companion object {
        private const val NAME = "todoist_sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TodoistSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(NAME)
        }
    }
}
