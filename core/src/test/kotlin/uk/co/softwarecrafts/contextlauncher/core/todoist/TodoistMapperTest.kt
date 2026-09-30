package uk.co.softwarecrafts.contextlauncher.core.todoist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class TodoistMapperTest {
    private val london: ZoneId = ZoneId.of("Europe/London")

    @Test
    fun `date-only due has no time`() {
        assertEquals(TodoistMapper.Due(LocalDate.of(2026, 9, 30), null), TodoistMapper.parseDue(DueDto("2026-09-30"), london))
    }

    @Test
    fun `floating due keeps its wall-clock time`() {
        assertEquals(
            TodoistMapper.Due(LocalDate.of(2026, 9, 30), LocalTime.of(19, 30)),
            TodoistMapper.parseDue(DueDto("2026-09-30T19:30:00"), london),
        )
    }

    @Test
    fun `fixed UTC due is converted into the user's zone`() {
        // 18:30Z is 19:30 BST
        assertEquals(
            TodoistMapper.Due(LocalDate.of(2026, 9, 30), LocalTime.of(19, 30)),
            TodoistMapper.parseDue(DueDto("2026-09-30T18:30:00Z", timezone = "Europe/London"), london),
        )
        // 23:30Z on the 30th is 00:30 on the 1st in BST
        assertEquals(LocalDate.of(2026, 10, 1), TodoistMapper.parseDue(DueDto("2026-09-30T23:30:00Z"), london)?.date)
    }

    @Test
    fun `garbage due is ignored rather than thrown`() {
        assertNull(TodoistMapper.parseDue(DueDto("soon"), london))
        assertNull(TodoistMapper.parseDue(null, london))
    }

    @Test
    fun `item becomes a snapshot with labels stripped of @ and completion instant`() {
        val item = TodoistJson.decodeItem(
            """{"id":"1","content":"Exercise","labels":["@phone/morning","fitness"],
                "due":{"date":"2026-09-30","string":"today"},"checked":true,
                "completed_at":"2026-09-30T06:55:00.000000Z","priority":3,"unknown":1}"""
        )
        val snap = TodoistMapper.toSnapshot(item, london)
        assertEquals(setOf("phone/morning", "fitness"), snap.labels)
        assertEquals(LocalDate.of(2026, 9, 30), snap.due)
        assertNull(snap.dueTime)
        assertEquals(true, snap.completed)
        assertEquals(Instant.parse("2026-09-30T06:55:00Z"), snap.completedAt)
    }
}
