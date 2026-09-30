package uk.co.softwarecrafts.contextlauncher.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import androidx.core.content.ContextCompat
import uk.co.softwarecrafts.contextlauncher.core.stage.CalendarEvent
import uk.co.softwarecrafts.contextlauncher.core.stage.DefaultSchedule
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Everything the app does with the device calendar provider
 * (CalendarContract). Reads are the source of stage times; writes only
 * happen in onboarding, to create a device calendar and seed the default
 * schedule.
 *
 * A device-local calendar (ACCOUNT_TYPE_LOCAL) lives only on this phone: it
 * shows in calendar apps here but never syncs to Google.
 */
class CalendarStore(private val context: Context) {

    data class CalendarInfo(val id: Long, val name: String, val account: String, val isLocal: Boolean)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun listCalendars(): List<CalendarInfo> {
        if (!hasPermission()) return emptyList()
        val projection = arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE)
        val out = mutableListOf<CalendarInfo>()
        context.contentResolver.query(Calendars.CONTENT_URI, projection, null, null, Calendars.CALENDAR_DISPLAY_NAME)?.use { c ->
            while (c.moveToNext()) {
                out += CalendarInfo(
                    id = c.getLong(0),
                    name = c.getString(1) ?: "",
                    account = c.getString(2) ?: "",
                    isLocal = c.getString(3) == CalendarContract.ACCOUNT_TYPE_LOCAL,
                )
            }
        }
        return out
    }

    fun findByName(name: String): CalendarInfo? =
        listCalendars().firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Creates a device-local calendar and returns it. Fails if one with the name already exists. */
    fun createLocalCalendar(name: String): CalendarInfo {
        findByName(name)?.let { return it }
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, name)
            put(Calendars.CALENDAR_DISPLAY_NAME, name)
            put(Calendars.CALENDAR_COLOR, LOCAL_COLOR)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().id)
        }
        val uri = Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val inserted = context.contentResolver.insert(uri, values) ?: error("calendar provider refused the insert")
        return CalendarInfo(ContentUris.parseId(inserted), name, LOCAL_ACCOUNT, isLocal = true)
    }

    /** Expanded event instances in the calendar between two moments. */
    fun instances(calendarId: Long, from: Instant, to: Instant): List<CalendarEvent> {
        if (!hasPermission()) return emptyList()
        val uri = Instances.CONTENT_URI.buildUpon()
            .appendPath(from.toEpochMilli().toString())
            .appendPath(to.toEpochMilli().toString())
            .build()
        val projection = arrayOf(Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY)
        val out = mutableListOf<CalendarEvent>()
        context.contentResolver.query(uri, projection, "${Instances.CALENDAR_ID} = ?", arrayOf(calendarId.toString()), Instances.BEGIN)
            ?.use { c ->
                while (c.moveToNext()) {
                    out += CalendarEvent(
                        title = c.getString(0) ?: "",
                        start = Instant.ofEpochMilli(c.getLong(1)),
                        end = Instant.ofEpochMilli(c.getLong(2)),
                        allDay = c.getInt(3) == 1,
                    )
                }
            }
        return out
    }

    /** True when the calendar already holds recurring events with the schedule's titles. */
    fun hasDefaultSchedule(calendarId: Long): Boolean {
        val titles = DefaultSchedule.blocks.map { it.title }.distinct()
        val placeholders = titles.joinToString(",") { "?" }
        context.contentResolver.query(
            Events.CONTENT_URI, arrayOf(Events._ID),
            "${Events.CALENDAR_ID} = ? AND ${Events.RRULE} IS NOT NULL AND ${Events.DELETED} = 0 AND ${Events.TITLE} IN ($placeholders)",
            arrayOf(calendarId.toString()) + titles.toTypedArray(), null,
        )?.use { return it.count > 0 }
        return false
    }

    /** Writes DefaultSchedule as weekly recurring events starting this week. Returns how many were added. */
    fun writeDefaultSchedule(calendarId: Long, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): Int {
        var added = 0
        for (block in DefaultSchedule.blocks) {
            val firstDay = block.days.minOf { day -> today.with(TemporalAdjusters.nextOrSame(day)) }
            val start = firstDay.atTime(block.start).atZone(zone).toInstant()
            val minutes = Duration.between(block.start, block.end).toMinutes()
            val byDay = block.days.sortedBy { it.value }.joinToString(",") { rruleDay(it) }
            val values = ContentValues().apply {
                put(Events.CALENDAR_ID, calendarId)
                put(Events.TITLE, block.title)
                put(Events.DTSTART, start.toEpochMilli())
                put(Events.DURATION, "PT${minutes}M")
                put(Events.RRULE, "FREQ=WEEKLY;BYDAY=$byDay")
                put(Events.EVENT_TIMEZONE, zone.id)
                put(Events.DESCRIPTION, "Context launcher stage block")
            }
            if (context.contentResolver.insert(Events.CONTENT_URI, values) != null) added++
        }
        return added
    }

    private fun rruleDay(day: DayOfWeek) = when (day) {
        DayOfWeek.MONDAY -> "MO"; DayOfWeek.TUESDAY -> "TU"; DayOfWeek.WEDNESDAY -> "WE"; DayOfWeek.THURSDAY -> "TH"
        DayOfWeek.FRIDAY -> "FR"; DayOfWeek.SATURDAY -> "SA"; DayOfWeek.SUNDAY -> "SU"
    }

    companion object {
        const val DEFAULT_CALENDAR_NAME = "Phone stages"
        const val LOCAL_ACCOUNT = "Context launcher"
        private const val LOCAL_COLOR = 0xFF546E7A.toInt()
        val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    }
}
