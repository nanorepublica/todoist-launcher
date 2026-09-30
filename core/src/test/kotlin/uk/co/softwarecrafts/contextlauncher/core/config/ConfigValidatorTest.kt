package uk.co.softwarecrafts.contextlauncher.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class ConfigValidatorTest {

    private val seed = SeedConfig.default()

    @Test
    fun `seed config is valid`() {
        assertEquals(emptyList<String>(), ConfigValidator.validate(seed))
    }

    @Test
    fun `unknown group references are reported`() {
        val bad = seed.copy(stages = seed.stages + Stage(
            id = "odd", name = "Odd", trigger = StageTrigger.Calendar("Odd"), rank = 1,
            allowedGroups = listOf("nope"),
        ))
        val problems = ConfigValidator.validate(bad)
        assertTrue(problems.any { it.contains("unknown group 'nope'") })
    }

    @Test
    fun `more than four always-allowed apps is rejected`() {
        val bad = seed.copy(alwaysAllowed = listOf("a", "b", "c", "d", "e"))
        assertTrue(ConfigValidator.validate(bad).any { it.contains("limit is 4") })
        assertEquals(emptyList<String>(), ConfigValidator.validate(seed.copy(alwaysAllowed = listOf("a", "b", "c", "d"))))
    }

    @Test
    fun `duplicate ids, labels with @, and a second weekday default are reported`() {
        val dup = seed.stages.first()
        val bad = seed.copy(
            stages = seed.stages + dup.copy(name = "Again") + Stage(
                id = "default2", name = "Default 2", trigger = StageTrigger.Default(DayKind.WEEKDAY), rank = 5,
            ),
            labelGroups = listOf(LabelGroup("@banking", SeedConfig.BANKING)),
        )
        val problems = ConfigValidator.validate(bad)
        assertTrue(problems.any { it.contains("duplicate stage id '${dup.id}'") })
        assertTrue(problems.any { it.contains("without the leading @") })
        assertTrue(problems.any { it.contains("more than one enabled default stage for WEEKDAY") })
    }

    @Test
    fun `perk without a done label and non-positive minutes are reported`() {
        val bad = seed.copy(stages = listOf(Stage(
            id = "s", name = "S", trigger = StageTrigger.FixedTime(LocalTime.NOON, LocalTime.NOON), rank = 1,
            maxBypassMinutes = 0, onDonePerk = Perk(SeedConfig.GAMES, 0),
            allowedApps = listOf(AllowedApp("pkg", capMinutes = -1)),
        )))
        val problems = ConfigValidator.validate(bad)
        assertTrue(problems.any { it.contains("no doneLabel") })
        assertTrue(problems.any { it.contains("perk minutes must be positive") })
        assertTrue(problems.any { it.contains("maxBypassMinutes must be positive") })
        assertTrue(problems.any { it.contains("capMinutes must be positive") })
        assertTrue(problems.any { it.contains("fixed window is empty") })
    }
}
