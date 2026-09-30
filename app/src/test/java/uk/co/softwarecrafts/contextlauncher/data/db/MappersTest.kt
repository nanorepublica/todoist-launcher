package uk.co.softwarecrafts.contextlauncher.data.db

import org.junit.Assert.assertEquals
import org.junit.Test
import uk.co.softwarecrafts.contextlauncher.core.config.LabelGroup
import uk.co.softwarecrafts.contextlauncher.core.config.SeedConfig
import uk.co.softwarecrafts.contextlauncher.core.log.EventType
import uk.co.softwarecrafts.contextlauncher.core.log.LogEvent
import java.time.Instant

class MappersTest {

    @Test
    fun `seed config survives a trip through the row types`() {
        val seed = SeedConfig.default().copy(alwaysAllowed = listOf("com.example.a", "com.example.b"), hiddenApps = listOf("com.bg"))
        val back = Mappers.toConfig(
            stages = seed.stages.mapIndexed { i, s -> Mappers.toEntity(s, i) },
            groups = seed.appGroups.mapIndexed { i, g -> Mappers.toEntity(g, i) },
            labelGroups = seed.labelGroups.map { LabelGroupEntity(it.label, it.groupId) },
            alwaysAllowed = seed.alwaysAllowed.mapIndexed { i, p -> AlwaysAllowedEntity(p, i) },
            settings = Mappers.toSettingRows(seed.settings),
            hiddenApps = seed.hiddenApps.mapIndexed { i, p -> HiddenAppEntity(p, i) },
        )
        assertEquals(seed, back)
        assertEquals(listOf(LabelGroup("banking", SeedConfig.BANKING)), back.labelGroups)
    }

    @Test
    fun `events keep every field including millisecond timestamps`() {
        val event = LogEvent(
            id = 7, at = Instant.ofEpochMilli(1_700_000_000_123), type = EventType.BYPASS,
            stageId = "work_am", packageName = "com.instagram.android", reason = "checking DMs",
            limitMinutes = 5, bypassNumber = 2, detail = null,
        )
        assertEquals(event, Mappers.toEvent(Mappers.toEntity(event)))
    }
}
