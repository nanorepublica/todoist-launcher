package uk.co.softwarecrafts.contextlauncher.usage

import android.content.Context
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.usageStats.EventLogWrapper
import uk.co.softwarecrafts.contextlauncher.core.review.ForegroundInterval
import java.time.Instant

/**
 * Per-app foreground time from UsageStatsManager, as core intervals. Needs the
 * "usage access" special permission (Setup step 8); without it the list is
 * empty and the review shows bypass counts only.
 */
class UsageReader(private val context: Context) {

    fun hasPermission(): Boolean = runCatching { context.appUsagePermissionGranted() }.getOrDefault(false)

    /** Blocking: call on Dispatchers.IO. Returns nothing without the permission. */
    fun intervals(from: Instant, to: Instant): List<ForegroundInterval> {
        if (!hasPermission()) return emptyList()
        return runCatching {
            EventLogWrapper(context).getForegroundStatsByTimestamps(from.toEpochMilli(), to.toEpochMilli())
                .filter { it.endTime > it.beginTime }
                .map { ForegroundInterval(it.packageName, Instant.ofEpochMilli(it.beginTime), Instant.ofEpochMilli(it.endTime)) }
        }.getOrDefault(emptyList())
    }
}
