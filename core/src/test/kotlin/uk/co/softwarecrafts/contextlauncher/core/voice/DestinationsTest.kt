package uk.co.softwarecrafts.contextlauncher.core.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import uk.co.softwarecrafts.contextlauncher.core.config.SeedConfig

class DestinationsTest {
    private val config = SeedConfig.default()

    @Test
    fun `claude is offered outside wind-down when installed`() {
        assertEquals(listOf(Destination.TASK, Destination.CLAUDE, Destination.COPY), Destinations.available(config, "work_am", claudeInstalled = true))
    }

    @Test
    fun `claude is hidden during wind-down and when not installed`() {
        assertEquals(listOf(Destination.TASK, Destination.COPY), Destinations.available(config, "wind_down", claudeInstalled = true))
        assertEquals(listOf(Destination.TASK, Destination.COPY), Destinations.available(config, "work_am", claudeInstalled = false))
        assertEquals(listOf(Destination.TASK, Destination.COPY), Destinations.available(config, null, claudeInstalled = false))
    }
}
