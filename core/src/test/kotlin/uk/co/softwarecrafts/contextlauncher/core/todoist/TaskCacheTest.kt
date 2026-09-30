package uk.co.softwarecrafts.contextlauncher.core.todoist

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskCacheTest {
    private fun item(id: String, content: String = "t$id", deleted: Boolean = false, checked: Boolean = false) =
        ItemDto(id = id, content = content, isDeleted = deleted, checked = checked)

    @Test
    fun `full sync replaces the cache`() {
        val existing = mapOf("1" to item("1"), "2" to item("2"))
        val merged = TaskCache.merge(existing, SyncResponse("t", fullSync = true, items = listOf(item("3"))))
        assertEquals(setOf("3"), merged.keys)
    }

    @Test
    fun `incremental sync upserts and deletes`() {
        val existing = mapOf("1" to item("1"), "2" to item("2"))
        val merged = TaskCache.merge(existing, SyncResponse("t", items = listOf(item("1", "renamed"), item("2", deleted = true), item("4"))))
        assertEquals(setOf("1", "4"), merged.keys)
        assertEquals("renamed", merged["1"]?.content)
    }

    @Test
    fun `completed fetch fills gaps without overriding an active rolled-forward copy`() {
        val existing = mapOf("1" to item("1"))
        val merged = TaskCache.addCompleted(existing, listOf(item("1", checked = true), item("9", checked = true), item("8", deleted = true)))
        assertEquals(false, merged["1"]?.checked)
        assertEquals(true, merged["9"]?.checked)
        assertEquals(null, merged["8"])
    }

    @Test
    fun `local completion is optimistic`() {
        val merged = TaskCache.markCompleted(mapOf("1" to item("1")), "1", "2026-09-30T10:00:00Z")
        assertEquals(true, merged["1"]?.checked)
        assertEquals("2026-09-30T10:00:00Z", merged["1"]?.completedAt)
        assertEquals(mapOf("1" to item("1")), TaskCache.markCompleted(mapOf("1" to item("1")), "nope", "x"))
    }

    @Test
    fun `sync response decodes with command statuses and unknown keys`() {
        val r = TodoistJson.decodeSync("""{"sync_token":"abc","full_sync":false,"items":[],"labels":[{"id":"7","name":"phone/morning"}],
            "sync_status":{"u1":"ok","u2":{"error":"bad","error_code":1}},"projects":[]}""")
        assertEquals("abc", r.syncToken)
        assertEquals("phone/morning", r.labels.single().name)
        assertEquals(2, r.syncStatus.size)
        val cmds = TodoistJson.encodeCommands(listOf(CommandDto("item_close", "u1", mapOf("id" to "5"))))
        assertEquals("""[{"type":"item_close","uuid":"u1","args":{"id":"5"}}]""", cmds)
    }
}
