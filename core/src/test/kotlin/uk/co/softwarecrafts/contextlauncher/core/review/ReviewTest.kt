package uk.co.softwarecrafts.contextlauncher.core.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.softwarecrafts.contextlauncher.core.config.SeedConfig
import uk.co.softwarecrafts.contextlauncher.core.log.EventType
import uk.co.softwarecrafts.contextlauncher.core.log.LogEvent
import java.time.Duration
import java.time.Instant

class ReviewTest {
    private val t0 = Instant.parse("2026-09-28T00:00:00Z")
    private fun at(h: Long, m: Long = 0) = t0.plus(Duration.ofHours(h)).plus(Duration.ofMinutes(m))
    private val config = SeedConfig.default()
    private val labels = mapOf("com.instagram.android" to "Instagram", "com.google.android.youtube" to "YouTube", "com.todoist" to "Todoist")
    private fun label(pkg: String) = labels[pkg] ?: pkg

    private fun stageChange(h: Long, to: String, from: String?) =
        LogEvent(at = at(h), type = EventType.STAGE_CHANGE, stageId = to, detail = from?.let { "from $it" } ?: "start")

    private fun bypass(h: Long, m: Long, pkg: String, stage: String, limit: Int, n: Int, reason: String? = null) =
        LogEvent(at = at(h, m), type = EventType.BYPASS, stageId = stage, packageName = pkg, limitMinutes = limit, bypassNumber = n, reason = reason)

    private fun timesUp(h: Long, m: Long, pkg: String, stage: String, limit: Int) =
        LogEvent(at = at(h, m), type = EventType.TIMES_UP, stageId = stage, packageName = pkg, limitMinutes = limit)

    @Test
    fun `stage timeline covers the period and attributes app time per stage`() {
        val events = listOf(stageChange(9, "work_am", "default_weekday"), stageChange(12, "lunch", "work_am"))
        val segments = StageTimeline.build(events, at(8), at(13))
        assertEquals(listOf("default_weekday", "work_am", "lunch"), segments.map { it.stageId })
        assertEquals(at(8), segments[0].start); assertEquals(at(13), segments[2].end)

        val usage = UsageAttribution.attribute(
            listOf(
                ForegroundInterval("com.instagram.android", at(8, 50), at(9, 10)), // 10 min default, 10 min work
                ForegroundInterval("com.todoist", at(10), at(10, 30)),
                ForegroundInterval("com.instagram.android", at(12, 5), at(12, 20)),
            ),
            segments,
        )
        val insta = usage.first { it.packageName == "com.instagram.android" }
        assertEquals(Duration.ofMinutes(35), insta.total)
        assertEquals(Duration.ofMinutes(10), insta.byStage["default_weekday"])
        assertEquals(Duration.ofMinutes(10), insta.byStage["work_am"])
        assertEquals(Duration.ofMinutes(15), insta.byStage["lunch"])
        assertEquals("com.instagram.android", usage.first().packageName)
    }

    @Test
    fun `report counts bypasses per app and stage with their times-up`() {
        val events = listOf(
            stageChange(9, "work_am", "default_weekday"),
            bypass(9, 10, "com.instagram.android", "work_am", 5, 1, "checking DMs"),
            timesUp(9, 15, "com.instagram.android", "work_am", 5),
            bypass(10, 0, "com.instagram.android", "work_am", 5, 2),
            timesUp(10, 5, "com.instagram.android", "work_am", 5),
            bypass(11, 0, "com.instagram.android", "work_am", 10, 3),
            LogEvent(at = at(11, 30), type = EventType.TASK_COMPLETED, detail = "x"),
            LogEvent(at = at(18), type = EventType.CAPPED_OPEN, stageId = "family", packageName = "com.google.android.youtube", limitMinutes = 15),
            timesUp(18, 15, "com.google.android.youtube", "family", 15),
        )
        val report = ReviewBuilder.build(events, emptyList(), at(0), at(24))
        assertEquals(3, report.totalBypasses)
        assertEquals(3, report.totalTimesUp)
        assertEquals(1, report.tasksCompleted)
        val insta = report.bypasses.single()
        assertEquals(3, insta.count); assertEquals(2, insta.timesUp); assertEquals(listOf(5, 5, 10), insta.limitsChosen)
        assertEquals(listOf("checking DMs"), insta.reasons)
        val yt = report.capped.single()
        assertEquals(1, yt.opens); assertEquals(1, yt.timesUp); assertEquals(15, yt.capMinutes)
    }

    @Test
    fun `rules - tighten the cap when limits keep running out`() {
        val events = (1..4).flatMap { i -> listOf(bypass(9 + i.toLong(), 0, "com.instagram.android", "family", 5, i), timesUp(9 + i.toLong(), 5, "com.instagram.android", "family", 5)) }
        val report = ReviewBuilder.build(events, emptyList(), at(0), at(24))
        val s = SuggestionRules.generate(config, report, ::label).single()
        assertTrue(s.text, s.text.startsWith("Instagram bypassed 4× during Family, running out of time 4 times against a 5-min limit: lower the cap to 2 min?"))
        val applied = s.change.apply(config)
        assertEquals(2, applied.stage("family")?.maxBypassMinutes)
    }

    @Test
    fun `rules - uncapped stage gets a cap, short visits get allowed`() {
        val uncapped = (1..3).map { i -> bypass(9 + i.toLong(), 0, "com.instagram.android", "work_am", 10, i) }
        val r1 = SuggestionRules.generate(config, ReviewBuilder.build(uncapped, emptyList(), at(0), at(24)), ::label).single()
        assertTrue(r1.text, r1.text.contains("no limit: set a 15-min cap for Work AM?"))
        assertEquals(15, r1.change.apply(config).stage("work_am")?.maxBypassMinutes)

        val short = (1..3).map { i -> bypass(17 + i.toLong() / 2, (i * 7).toLong(), "com.todoist", "family", 2, i) }
        val r2 = SuggestionRules.generate(config, ReviewBuilder.build(short, emptyList(), at(0), at(24)), ::label).single()
        assertTrue(r2.text, r2.text.contains("allow it there with a 2-min cap?"))
        val applied = r2.change.apply(config)
        assertEquals(2, applied.stage("family")?.allowedApps?.single { it.packageName == "com.todoist" }?.capMinutes)
    }

    @Test
    fun `rules - capped allowed app that keeps hitting its cap gets a lower one everywhere`() {
        val events = (1..3).flatMap { i -> listOf(
            LogEvent(at = at(17 + i.toLong()), type = EventType.CAPPED_OPEN, stageId = "family", packageName = "com.google.android.youtube", limitMinutes = 15),
            timesUp(17 + i.toLong(), 15, "com.google.android.youtube", "family", 15),
        ) }
        val s = SuggestionRules.generate(config, ReviewBuilder.build(events, emptyList(), at(0), at(24)), ::label).single()
        assertTrue(s.text, s.text.startsWith("YouTube hit its 15-min cap 3 of 3 times: lower the cap to 10 min?"))
        val applied = s.change.apply(config)
        assertEquals(10, applied.group(SeedConfig.FAMILY_MEDIA)?.apps?.single { it.packageName == "com.google.android.youtube" }?.capMinutes)
    }

    @Test
    fun `fewer than three bypasses produce nothing, and export is valid JSON with labels`() {
        val events = listOf(bypass(9, 0, "com.instagram.android", "work_am", 5, 1), bypass(10, 0, "com.instagram.android", "work_am", 5, 2))
        val report = ReviewBuilder.build(events, listOf(ForegroundInterval("com.instagram.android", at(9), at(9, 5))), at(0), at(24))
        assertEquals(emptyList<Suggestion>(), SuggestionRules.generate(config, report, ::label))
        val text = UsageExport.encode(report, events, ::label)
        assertTrue(text.contains("\"app\": \"Instagram\""))
        assertTrue(text.contains("\"totalBypasses\": 2"))
        assertTrue(text.contains("\"totalMinutes\": 5"))
    }
}
