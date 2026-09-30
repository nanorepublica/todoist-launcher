package uk.co.softwarecrafts.contextlauncher.data

import uk.co.softwarecrafts.contextlauncher.core.log.LogEvent
import uk.co.softwarecrafts.contextlauncher.data.db.EventDao
import uk.co.softwarecrafts.contextlauncher.data.db.Mappers
import java.time.Instant

/** Append-only usage log. */
class EventLogRepository(private val dao: EventDao) {

    suspend fun record(event: LogEvent): Long = dao.insert(Mappers.toEntity(event))

    suspend fun between(from: Instant, to: Instant): List<LogEvent> =
        dao.between(from.toEpochMilli(), to.toEpochMilli()).map(Mappers::toEvent)

    suspend fun latest(limit: Int = 50): List<LogEvent> = dao.latest(limit).map(Mappers::toEvent)

    suspend fun count(): Int = dao.count()

    suspend fun pruneBefore(cutoff: Instant): Int = dao.deleteBefore(cutoff.toEpochMilli())
}
