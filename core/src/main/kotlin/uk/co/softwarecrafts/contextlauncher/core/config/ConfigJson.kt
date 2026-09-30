package uk.co.softwarecrafts.contextlauncher.core.config

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The one JSON configuration used for the export file and for Room columns. */
object ConfigJson {
    val json: Json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    fun encode(config: LauncherConfig): String = json.encodeToString(LauncherConfig.serializer(), config)

    /** Throws [ConfigFormatException] on malformed input. */
    fun decode(text: String): LauncherConfig = try {
        json.decodeFromString(LauncherConfig.serializer(), text)
    } catch (e: SerializationException) {
        throw ConfigFormatException(e.message ?: "invalid config JSON", e)
    } catch (e: IllegalArgumentException) {
        throw ConfigFormatException(e.message ?: "invalid config JSON", e)
    }

    fun encodeTrigger(trigger: StageTrigger): String = json.encodeToString(StageTrigger.serializer(), trigger)
    fun decodeTrigger(text: String): StageTrigger = json.decodeFromString(StageTrigger.serializer(), text)

    fun encodeApps(apps: List<AllowedApp>): String = json.encodeToString(ListSerializer(AllowedApp.serializer()), apps)
    fun decodeApps(text: String): List<AllowedApp> = json.decodeFromString(ListSerializer(AllowedApp.serializer()), text)

    fun encodeStrings(values: List<String>): String = json.encodeToString(ListSerializer(String.serializer()), values)
    fun decodeStrings(text: String): List<String> = json.decodeFromString(ListSerializer(String.serializer()), text)
}

class ConfigFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
