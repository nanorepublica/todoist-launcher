package uk.co.softwarecrafts.contextlauncher

import android.Manifest
import android.app.Application
import androidx.navigation.findNavController
import app.olauncher.MainActivity
import app.olauncher.R
import app.olauncher.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import uk.co.softwarecrafts.contextlauncher.data.AppPrefs
import uk.co.softwarecrafts.contextlauncher.engine.StageEngine
import uk.co.softwarecrafts.contextlauncher.engine.StageState

/**
 * Boots the launcher in the JVM the way the phone does: onCreate, onStart,
 * onResume, then the engine's first resolve. Catches inflation, Room and
 * navigation crashes without a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchSmokeTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        Graph.resetForTests()
        app = RuntimeEnvironment.getApplication() as Application
        // Skip Olauncher's 4-hourly recreate() on the very first start
        Prefs(app).launcherRestartTimestamp = System.currentTimeMillis()
    }

    @Test
    fun `first run boots and opens onboarding`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        ShadowLooper.idleMainLooper()
        val nav = controller.get().findNavController(R.id.nav_host_fragment)
        assertEquals(R.id.onboardingFragment, nav.currentDestination?.id)
        controller.pause().stop().destroy()
    }

    @Test
    fun `after setup the home screen resolves a stage state without crashing`() {
        AppPrefs(app).onboardingDone = true
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        ShadowLooper.idleMainLooper()
        val nav = controller.get().findNavController(R.id.nav_host_fragment)
        assertEquals(R.id.mainFragment, nav.currentDestination?.id)

        val state = awaitResolved(Graph.stageEngine(app))
        // No stage calendar chosen yet, so setup is needed rather than a crash
        assertTrue("expected SetupNeeded, got $state", state is StageState.SetupNeeded)
        assertEquals("Choose a stage calendar", (state as StageState.SetupNeeded).reason)
        controller.pause().stop().destroy()
    }

    private fun awaitResolved(engine: StageEngine): StageState {
        engine.refresh()
        val deadline = System.currentTimeMillis() + 10_000
        while (engine.state.value is StageState.Loading && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            ShadowLooper.idleMainLooper()
        }
        return engine.state.value
    }
}
