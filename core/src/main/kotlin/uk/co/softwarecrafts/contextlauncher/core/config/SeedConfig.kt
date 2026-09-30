package uk.co.softwarecrafts.contextlauncher.core.config

import java.time.LocalTime

/**
 * The starting configuration, built from the stage table in SPEC.md. Package
 * names are best guesses to be corrected in settings or by editing the
 * exported JSON.
 */
object SeedConfig {

    const val ESSENTIALS = "essentials"
    const val WORK = "work"
    const val FAMILY_MEDIA = "family_media"
    const val EVENING = "evening"
    const val GAMES = "games"
    const val BANKING = "banking"
    const val UNRESTRICTED = "unrestricted"
    const val OCCASIONAL = "occasional"

    fun default(): LauncherConfig = LauncherConfig(
        appGroups = listOf(
            AppGroup(ESSENTIALS, "Essentials", listOf(
                AllowedApp("com.todoist"),
                AllowedApp("com.google.android.calendar"),
            )),
            AppGroup(WORK, "Work", listOf(
                AllowedApp("com.todoist"),
                AllowedApp("com.google.android.calendar"),
                AllowedApp("com.google.android.gm"),
                AllowedApp("com.Slack"),
                AllowedApp("com.android.chrome"),
                AllowedApp("com.anthropic.claude"),
            )),
            AppGroup(FAMILY_MEDIA, "Family media", listOf(
                AllowedApp("com.anthropic.claude"),
                AllowedApp("com.spotify.music"),
                AllowedApp("com.google.android.youtube", capMinutes = 15),
            )),
            AppGroup(EVENING, "Evening", listOf(
                AllowedApp("com.anthropic.claude"),
                AllowedApp("com.spotify.music"),
            )),
            AppGroup(GAMES, "Daily games", emptyList()),
            AppGroup(BANKING, "Banking", emptyList()),
            AppGroup(UNRESTRICTED, "Unrestricted", emptyList(), kind = GroupKind.UNRESTRICTED),
            AppGroup(OCCASIONAL, "Occasional", listOf(AllowedApp("com.android.vending")), kind = GroupKind.OCCASIONAL),
        ),
        stages = listOf(
            Stage(
                id = "weekly_review", name = "Weekly review",
                trigger = StageTrigger.TaskDue("phone/review"), rank = 100,
                doneLabel = "phone/review", maxBypassMinutes = 5,
                allowedGroups = listOf(ESSENTIALS),
            ),
            Stage(
                id = "wind_down", name = "Wind-down",
                trigger = StageTrigger.FixedTime(LocalTime.of(21, 0), LocalTime.of(6, 0)), rank = 90,
                maxBypassMinutes = 5, allowedGroups = listOf(ESSENTIALS),
            ),
            Stage(
                id = "kids_bedtime", name = "Kids' bedtime",
                trigger = StageTrigger.Calendar("Kids' bedtime"), rank = 85,
                doneLabel = "phone/kidsdown", maxBypassMinutes = 5,
                allowedGroups = listOf(ESSENTIALS),
            ),
            Stage(
                id = "family", name = "Family",
                trigger = StageTrigger.Calendar("Family"), rank = 80,
                maxBypassMinutes = 5, allowedGroups = listOf(ESSENTIALS, FAMILY_MEDIA),
            ),
            Stage(
                id = "default_weekend", name = "Weekend",
                trigger = StageTrigger.Default(DayKind.WEEKEND), rank = 75,
                maxBypassMinutes = 5, allowedGroups = listOf(ESSENTIALS, FAMILY_MEDIA),
            ),
            Stage(
                id = "morning_routine", name = "Morning routine",
                trigger = StageTrigger.Calendar("Morning routine"), rank = 70,
                doneLabel = "phone/morning", maxBypassMinutes = 5,
                allowedGroups = listOf(ESSENTIALS),
                onDonePerk = Perk(GAMES, minutes = 10),
            ),
            Stage(
                id = "after_bedtime", name = "After bedtime",
                trigger = StageTrigger.TasksDone("phone/kidsdown", until = LocalTime.of(21, 0)), rank = 60,
                maxBypassMinutes = 5, allowedGroups = listOf(ESSENTIALS, EVENING),
            ),
            Stage(
                id = "work_am", name = "Work AM",
                trigger = StageTrigger.Calendar("Work AM"), rank = 50,
                allowedGroups = listOf(WORK),
            ),
            Stage(
                id = "lunch", name = "Lunch",
                trigger = StageTrigger.Calendar("Lunch"), rank = 45,
                allowedGroups = listOf(WORK),
            ),
            Stage(
                id = "work_pm", name = "Work PM",
                trigger = StageTrigger.Calendar("Work PM"), rank = 50,
                allowedGroups = listOf(WORK),
            ),
            Stage(
                id = "default_weekday", name = "Default",
                trigger = StageTrigger.Default(DayKind.WEEKDAY), rank = 10,
                allowedGroups = listOf(WORK),
            ),
        ),
        labelGroups = listOf(LabelGroup("banking", BANKING)),
        alwaysAllowed = emptyList(),
    )
}
