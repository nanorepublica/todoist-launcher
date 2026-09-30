package uk.co.softwarecrafts.contextlauncher.core.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.softwarecrafts.contextlauncher.core.FakeClock
import uk.co.softwarecrafts.contextlauncher.core.config.SeedConfig
import uk.co.softwarecrafts.contextlauncher.core.stage.CalendarEvent
import uk.co.softwarecrafts.contextlauncher.core.stage.StageResolver
import uk.co.softwarecrafts.contextlauncher.core.stage.TaskSnapshot
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class GatekeeperTest {
    private val zone: ZoneId = ZoneId.of("Europe/London")
    private val config = SeedConfig.default().copy(alwaysAllowed = listOf("com.example.maps"))
    private val day = LocalDate.of(2026, 9, 30)
    private val phoneCamera = setOf("com.android.dialer", "com.android.camera")
    private fun at(t: LocalTime) = day.atTime(t).atZone(zone).toInstant()

    private fun resolveAt(hour: Int, minute: Int = 0, tasks: List<TaskSnapshot> = emptyList()) =
        StageResolver(FakeClock(LocalDateTime.of(day, LocalTime.of(hour, minute)), zone)).resolve(
            config,
            listOf(
                CalendarEvent("Work AM", at(LocalTime.of(9, 0)), at(LocalTime.of(12, 30))),
                CalendarEvent("Family", at(LocalTime.of(17, 0)), at(LocalTime.of(19, 0))),
            ),
            tasks,
        )

    @Test
    fun `family allows essentials, media with the YouTube cap, always-allowed and phone plus camera`() {
        val allowed = Gatekeeper.allowed(config, resolveAt(18), phoneCamera)
        val byPackage = allowed.associateBy { it.packageName }
        assertEquals(15, byPackage["com.google.android.youtube"]?.capMinutes)
        assertEquals(AllowSource.GROUP, byPackage["com.google.android.youtube"]?.source)
        assertEquals(AllowSource.ALWAYS, byPackage["com.example.maps"]?.source)
        assertEquals(AllowSource.PHONE_CAMERA, byPackage["com.android.dialer"]?.source)
        assertTrue("com.android.chrome" !in byPackage)
    }

    @Test
    fun `decisions - allowed, capped, friction with the stage cap`() {
        val r = resolveAt(18)
        val allowed = Gatekeeper.allowed(config, r, phoneCamera)
        assertEquals(Decision.Allowed, Gatekeeper.decide("com.todoist", allowed, r))
        assertEquals(Decision.AllowedCapped(15), Gatekeeper.decide("com.google.android.youtube", allowed, r))
        assertEquals(Decision.Friction(5), Gatekeeper.decide("com.instagram.android", allowed, r))

        val work = resolveAt(10)
        assertEquals(Decision.Friction(null), Gatekeeper.decide("com.instagram.android", Gatekeeper.allowed(config, work, phoneCamera), work))
    }

    @Test
    fun `task-linked groups unlock during any stage and an uncapped allowance beats a capped one`() {
        val banking = config.copy(appGroups = config.appGroups.map {
            if (it.id == SeedConfig.BANKING) it.copy(apps = listOf(uk.co.softwarecrafts.contextlauncher.core.config.AllowedApp("com.bank"), uk.co.softwarecrafts.contextlauncher.core.config.AllowedApp("com.google.android.youtube"))) else it
        })
        val task = TaskSnapshot("b", "Pay bill", setOf("banking"), due = day)
        val r = StageResolver(FakeClock(LocalDateTime.of(day, LocalTime.of(18, 0)), zone))
            .resolve(banking, listOf(CalendarEvent("Family", at(LocalTime.of(17, 0)), at(LocalTime.of(19, 0)))), listOf(task))
        val allowed = Gatekeeper.allowed(banking, r, emptySet()).associateBy { it.packageName }
        assertEquals(AllowSource.TASK_LINKED, allowed["com.bank"]?.source)
        assertEquals(null, allowed["com.google.android.youtube"]?.capMinutes)
    }

    @Test
    fun `unrestricted groups are allowed in every stage, uncapped, and hidden apps are just absent`() {
        val cfg = config.copy(appGroups = config.appGroups.map {
            if (it.id == SeedConfig.UNRESTRICTED) it.copy(apps = listOf(uk.co.softwarecrafts.contextlauncher.core.config.AllowedApp("com.drone", capMinutes = 3))) else it
        }, hiddenApps = listOf("com.background.sync"))
        val r = resolveAt(18)
        val allowed = Gatekeeper.allowed(cfg, r, emptySet()).associateBy { it.packageName }
        assertEquals(AllowSource.UNRESTRICTED, allowed["com.drone"]?.source)
        assertEquals(null, allowed["com.drone"]?.capMinutes)
        assertEquals(Decision.Allowed, Gatekeeper.decide("com.drone", allowed.values.toList(), r))
        assertEquals(Decision.Friction(5), Gatekeeper.decide("com.background.sync", allowed.values.toList(), r))
        assertEquals(setOf("com.android.vending"), cfg.packagesIn(uk.co.softwarecrafts.contextlauncher.core.config.GroupKind.OCCASIONAL))
    }

    @Test
    fun `perk group is allowed while the perk runs`() {
        val games = config.copy(appGroups = config.appGroups.map {
            if (it.id == SeedConfig.GAMES) it.copy(apps = listOf(uk.co.softwarecrafts.contextlauncher.core.config.AllowedApp("com.wordle"))) else it
        })
        val done = TaskSnapshot("m", "Exercise", setOf("phone/morning"), due = day, completed = true, completedAt = at(LocalTime.of(6, 55)))
        val clock = FakeClock(LocalDateTime.of(day, LocalTime.of(7, 0)), zone)
        val r = StageResolver(clock).resolve(games, listOf(CalendarEvent("Morning routine", at(LocalTime.of(6, 30)), at(LocalTime.of(7, 30)))), listOf(done))
        val allowed = Gatekeeper.allowed(games, r, emptySet()).associateBy { it.packageName }
        assertEquals(AllowSource.PERK, allowed["com.wordle"]?.source)
    }
}
