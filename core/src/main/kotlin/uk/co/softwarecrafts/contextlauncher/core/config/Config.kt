package uk.co.softwarecrafts.contextlauncher.core.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Everything the launcher needs to know about stages, apps and labels. This is
 * the shape of the JSON import/export file and of what Room stores.
 *
 * Todoist labels are stored without the leading `@`.
 */
@Serializable
data class LauncherConfig(
    val version: Int = CURRENT_VERSION,
    val stages: List<Stage> = emptyList(),
    val appGroups: List<AppGroup> = emptyList(),
    /** Todoist label to app group: while such a task is open, the group is allowed in any stage. */
    val labelGroups: List<LabelGroup> = emptyList(),
    /** Package names always allowed on top of Phone and Camera. At most [MAX_ALWAYS_ALLOWED]. */
    val alwaysAllowed: List<String> = emptyList(),
    /** Installed for background duty only; never shown in the app list. */
    val hiddenApps: List<String> = emptyList(),
    val settings: GeneralSettings = GeneralSettings(),
) {
    fun stage(id: String): Stage? = stages.firstOrNull { it.id == id }
    fun group(id: String): AppGroup? = appGroups.firstOrNull { it.id == id }
    fun groups(kind: GroupKind): List<AppGroup> = appGroups.filter { it.kind == kind }
    fun packagesIn(kind: GroupKind): Set<String> = groups(kind).flatMap { g -> g.apps.map { it.packageName } }.toSet()

    companion object {
        const val CURRENT_VERSION = 1
        const val MAX_ALWAYS_ALLOWED = 4
    }
}

@Serializable
data class GeneralSettings(
    /** Display name of the dedicated Google calendar that carries the stages. */
    val calendarName: String? = null,
)

@Serializable
data class Stage(
    val id: String,
    val name: String,
    val trigger: StageTrigger,
    /** Higher wins when stages overlap ("most restrictive wins"). */
    val rank: Int,
    /** Tasks carrying this label define "done"; when all are complete the stage unlocks early. */
    val doneLabel: String? = null,
    /** Longest time limit the friction screen offers. Null means no limit. */
    val maxBypassMinutes: Int? = null,
    val allowedApps: List<AllowedApp> = emptyList(),
    /** Ids of [AppGroup]s allowed in this stage. */
    val allowedGroups: List<String> = emptyList(),
    /** Something that unlocks once [doneLabel] tasks are all ticked. */
    val onDonePerk: Perk? = null,
    val enabled: Boolean = true,
)

/** How a stage starts and ends. */
@Serializable
sealed class StageTrigger {
    /** Runs for the duration of a calendar event with this title (case-insensitive). */
    @Serializable
    @SerialName("calendar")
    data class Calendar(val eventTitle: String) : StageTrigger()

    /** Runs every day between two wall-clock times; [end] before [start] wraps past midnight. */
    @Serializable
    @SerialName("fixed")
    data class FixedTime(
        @Serializable(with = LocalTimeSerializer::class) val start: LocalTime,
        @Serializable(with = LocalTimeSerializer::class) val end: LocalTime,
    ) : StageTrigger()

    /** Starts once every task carrying [label] that is due today is complete; ends at [until]. */
    @Serializable
    @SerialName("tasksDone")
    data class TasksDone(
        val label: String,
        @Serializable(with = LocalTimeSerializer::class) val until: LocalTime,
    ) : StageTrigger()

    /** Starts when a task carrying [label] falls due; ends when it is completed. */
    @Serializable
    @SerialName("taskDue")
    data class TaskDue(val label: String) : StageTrigger()

    /** Fills gaps with no other stage on the given kind of day. */
    @Serializable
    @SerialName("default")
    data class Default(val days: DayKind) : StageTrigger()
}

@Serializable
enum class DayKind { WEEKDAY, WEEKEND }

@Serializable
data class AllowedApp(
    val packageName: String,
    /** Fixed session length in minutes for an app that is allowed but capped. Null means uncapped. */
    val capMinutes: Int? = null,
)

/** How a group behaves outside the stage allowlists that reference it. */
@Serializable
enum class GroupKind {
    /** Only allowed when a stage, label or perk names it. */
    @SerialName("normal") NORMAL,
    /** Allowed in every stage with no restrictions; listed on the home screen under its name. */
    @SerialName("unrestricted") UNRESTRICTED,
    /** Hidden from the app list until a search is typed; gated like any other app. */
    @SerialName("occasional") OCCASIONAL,
}

@Serializable
data class AppGroup(
    val id: String,
    val name: String,
    val apps: List<AllowedApp> = emptyList(),
    val kind: GroupKind = GroupKind.NORMAL,
)

@Serializable
data class LabelGroup(val label: String, val groupId: String)

@Serializable
data class Perk(val groupId: String, val minutes: Int)

/** "HH:mm" in JSON. */
object LocalTimeSerializer : KSerializer<LocalTime> {
    private val format = DateTimeFormatter.ofPattern("HH:mm")
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalTime) = encoder.encodeString(value.format(format))
    override fun deserialize(decoder: Decoder): LocalTime = LocalTime.parse(decoder.decodeString(), format)
}
