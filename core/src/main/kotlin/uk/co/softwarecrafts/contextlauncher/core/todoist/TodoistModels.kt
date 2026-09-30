package uk.co.softwarecrafts.contextlauncher.core.todoist

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shapes of the Todoist unified API v1 (https://developer.todoist.com/api/v1/).
 * Only the fields the launcher uses; unknown keys are ignored on decode.
 */
@Serializable
data class SyncResponse(
    @SerialName("sync_token") val syncToken: String,
    @SerialName("full_sync") val fullSync: Boolean = false,
    val items: List<ItemDto> = emptyList(),
    val labels: List<LabelDto> = emptyList(),
    /** Per-command results when commands were sent: uuid -> "ok" or an error object. */
    @SerialName("sync_status") val syncStatus: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
)

@Serializable
data class ItemDto(
    val id: String,
    val content: String = "",
    val description: String = "",
    @SerialName("project_id") val projectId: String? = null,
    val labels: List<String> = emptyList(),
    val due: DueDto? = null,
    val checked: Boolean = false,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    /** 1 (normal) to 4 (urgent) in the API; the apps show p4..p1. */
    val priority: Int = 1,
    @SerialName("child_order") val childOrder: Int = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class DueDto(
    /** "YYYY-MM-DD", "YYYY-MM-DDTHH:MM:SS" (floating) or "YYYY-MM-DDTHH:MM:SSZ" (fixed, with [timezone]). */
    val date: String,
    val timezone: String? = null,
    val string: String = "",
    @SerialName("is_recurring") val isRecurring: Boolean = false,
)

@Serializable
data class LabelDto(
    val id: String,
    val name: String,
    @SerialName("is_deleted") val isDeleted: Boolean = false,
)

/** GET /tasks/completed/by_completion_date */
@Serializable
data class CompletedResponse(
    val items: List<ItemDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/** One entry of the `commands` array sent to /sync. */
@Serializable
data class CommandDto(
    val type: String,
    val uuid: String,
    val args: Map<String, String>,
    @SerialName("temp_id") val tempId: String? = null,
)
