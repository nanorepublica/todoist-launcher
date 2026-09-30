package uk.co.softwarecrafts.contextlauncher.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class ConfigJsonTest {

    @Test
    fun `seed config survives a JSON round trip`() {
        val seed = SeedConfig.default()
        val text = ConfigJson.encode(seed)
        assertEquals(seed, ConfigJson.decode(text))
    }

    @Test
    fun `times are written as HH mm and triggers carry a type tag`() {
        val text = ConfigJson.encode(SeedConfig.default())
        assertTrue(text.contains("\"start\": \"21:00\""))
        assertTrue(text.contains("\"type\": \"fixed\""))
        assertTrue(text.contains("\"type\": \"calendar\""))
        assertTrue(text.contains("\"kind\": \"occasional\""))
        assertTrue(text.contains("\"hiddenApps\""))
    }

    @Test
    fun `unknown keys are ignored and missing optional fields take defaults`() {
        val text = """
            {
              "stages": [
                {"id": "x", "name": "X", "rank": 1, "future_field": true,
                 "trigger": {"type": "fixed", "start": "09:00", "end": "17:00"}}
              ]
            }
        """.trimIndent()
        val config = ConfigJson.decode(text)
        assertEquals(LauncherConfig.CURRENT_VERSION, config.version)
        val stage = config.stages.single()
        assertEquals(StageTrigger.FixedTime(LocalTime.of(9, 0), LocalTime.of(17, 0)), stage.trigger)
        assertEquals(true, stage.enabled)
        assertEquals(null, stage.maxBypassMinutes)
    }

    @Test(expected = ConfigFormatException::class)
    fun `malformed JSON is reported as a config format problem`() {
        ConfigJson.decode("{\"stages\": [{\"id\": 1}]")
    }

    @Test
    fun `column codecs round trip`() {
        val trigger = StageTrigger.TasksDone("phone/kidsdown", LocalTime.of(21, 0))
        assertEquals(trigger, ConfigJson.decodeTrigger(ConfigJson.encodeTrigger(trigger)))
        val apps = listOf(AllowedApp("a"), AllowedApp("b", 15))
        assertEquals(apps, ConfigJson.decodeApps(ConfigJson.encodeApps(apps)))
        assertEquals(listOf("x", "y"), ConfigJson.decodeStrings(ConfigJson.encodeStrings(listOf("x", "y"))))
    }
}
