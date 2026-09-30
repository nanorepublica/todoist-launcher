package uk.co.softwarecrafts.contextlauncher.core.todoist

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

object TodoistJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun decodeSync(text: String): SyncResponse = json.decodeFromString(SyncResponse.serializer(), text)
    fun decodeCompleted(text: String): CompletedResponse = json.decodeFromString(CompletedResponse.serializer(), text)
    fun decodeItem(text: String): ItemDto = json.decodeFromString(ItemDto.serializer(), text)
    fun encodeCommands(commands: List<CommandDto>): String =
        json.encodeToString(ListSerializer(CommandDto.serializer()), commands)
    fun encodeStrings(values: List<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), values)
}
