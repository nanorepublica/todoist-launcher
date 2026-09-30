package uk.co.softwarecrafts.contextlauncher.core.todoist

/**
 * Pure merge rules for the local copy of Todoist items. A full sync replaces
 * everything; an incremental sync upserts changed items and drops deleted ones.
 */
object TaskCache {

    fun merge(existing: Map<String, ItemDto>, response: SyncResponse): Map<String, ItemDto> {
        val base = if (response.fullSync) emptyMap() else existing
        val out = base.toMutableMap()
        for (item in response.items) {
            if (item.isDeleted) out.remove(item.id) else out[item.id] = item
        }
        return out
    }

    /** Completed items fetched separately (full sync omits them) fill in today's completions. */
    fun addCompleted(existing: Map<String, ItemDto>, completed: List<ItemDto>): Map<String, ItemDto> {
        val out = existing.toMutableMap()
        for (item in completed) {
            if (item.isDeleted) continue
            val current = out[item.id]
            // A recurring task that was completed and rolled forward is active again with a new due;
            // keep the active copy and only add completed ones we don't otherwise know about.
            if (current == null) out[item.id] = item.copy(checked = true)
        }
        return out
    }

    /** Marks a task complete locally while the server call is in flight. */
    fun markCompleted(existing: Map<String, ItemDto>, id: String, completedAt: String): Map<String, ItemDto> {
        val item = existing[id] ?: return existing
        return existing + (id to item.copy(checked = true, completedAt = completedAt))
    }
}
