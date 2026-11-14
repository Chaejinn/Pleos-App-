package com.pleos.explorer.domain.usecase

import com.pleos.explorer.domain.RegionTestFixtures
import com.pleos.explorer.domain.TitleTestFixtures.NOW_MS
import com.pleos.explorer.domain.TitleTestFixtures.THIRTY_DAYS_MS
import com.pleos.explorer.domain.engine.GhostDetector
import com.pleos.explorer.domain.engine.GhostResultBuilder
import com.pleos.explorer.domain.engine.LocationValidator
import com.pleos.explorer.domain.engine.RegionVisitDetector
import com.pleos.explorer.domain.engine.TrackRecorder
import com.pleos.explorer.domain.engine.TitleEvaluator
import com.pleos.explorer.domain.geo.IndexedRegionCatalog
import com.pleos.explorer.domain.model.AchievementFact
import com.pleos.explorer.domain.model.AchievementProgress
import com.pleos.explorer.domain.model.BadgeType
import com.pleos.explorer.domain.model.GhostEvent
import com.pleos.explorer.domain.model.GhostInput
import com.pleos.explorer.domain.model.GhostSnapshot
import com.pleos.explorer.domain.model.HistoricalTrack
import com.pleos.explorer.domain.model.LocationSample
import com.pleos.explorer.domain.model.LocationValidation
import com.pleos.explorer.domain.model.RegionBadge
import com.pleos.explorer.domain.model.RegionInput
import com.pleos.explorer.domain.model.RegionVisitSnapshot
import com.pleos.explorer.domain.model.RegionVisitUpdate
import com.pleos.explorer.domain.model.TitleSnapshot
import com.pleos.explorer.domain.model.TitleUpdate
import com.pleos.explorer.domain.model.TrackPoint
import com.pleos.explorer.domain.port.IdGenerator
import com.pleos.explorer.domain.port.RegionVisitStore
import com.pleos.explorer.domain.port.TitleStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleIntegrationTest {
    @Test
    fun `actual F05 thirtieth distinct region grants one title while university and revisits do not count`() = runBlocking {
        val badges = (0..28).associate { "REGION:$it" to
            RegionBadge("REGION:$it", "$it", BadgeType.REGION, "past", NOW_MS - 1, 1) }.toMutableMap()
        badges["UNIVERSITY:U"] = RegionBadge("UNIVERSITY:U", "U", BadgeType.UNIVERSITY, null, NOW_MS - 1, 100)
        var visits: RegionVisitSnapshot? = null
        var titleState = TitleSnapshot()
        var id = 0
        val ids = IdGenerator { "id-${++id}" }
        val titleStore = object : TitleStore {
            override suspend fun load() = titleState.copy(progress = titleState.progress.copy(
                regionBadgeCount = badges.values.filter { it.type == BadgeType.REGION }.map { it.regionCode }.distinct().size))
            override suspend fun commit(expectedRevision: Long, update: TitleUpdate) {
                check(expectedRevision == titleState.revision)
                assertEquals(1, update.titles.size)
                assertEquals(1, update.rewards.size)
                titleState = update.snapshot
            }
        }
        val titles = ProcessTitleFactUseCase(titleStore, ids)
        assertTrue(titles.process(AchievementFact.BadgeGranted("REGION:28", "past", NOW_MS - 1)).titles.isEmpty())
        val regionStore = object : RegionVisitStore {
            override suspend fun load() = visits
            override suspend fun findBadges(regionCodes: Set<String>) = badges.filterValues { it.regionCode in regionCodes }
            override suspend fun commit(expectedRevision: Long?, update: RegionVisitUpdate) {
                check(expectedRevision == visits?.revision)
                badges.putAll(update.badges)
                visits = update.snapshot
                titleState = titleState.copy(revision = titleState.revision + 1)
            }
        }
        val regions = ProcessRegionVisitUseCase(regionStore,
            RegionVisitDetector(IndexedRegionCatalog(listOf(RegionTestFixtures.region("new")))), ids)
        regions.start("session", "trip")
        regions.process("session", 0, RegionInput.Location(RegionTestFixtures.location(0.0, 0), 0.0, true))
        var qualified: RegionVisitUpdate? = null
        (1..40).forEach { index ->
            val update = regions.process("session", index.toLong(),
                RegionInput.Location(RegionTestFixtures.location(index * 25.0, index * 1_000L), 25.0))
            if (update.rewards.isNotEmpty()) qualified = update
        }
        val badgeReward = requireNotNull(qualified).rewards.single()
        val fact = AchievementFact.BadgeGranted(badgeReward.subjectId, badgeReward.tripId, badgeReward.occurredAtMs)
        assertEquals("REGION_EXPLORER", titles.process(fact).titles.single().titleId)
        assertEquals(30, titleStore.load().progress.regionBadgeCount)
        assertTrue(ProcessTitleFactUseCase(titleStore, ids).process(fact).rewards.isEmpty())
        assertTrue(regions.process("session", 41,
            RegionInput.Location(RegionTestFixtures.location(1_025.0, 41_000), 25.0)).rewards.isEmpty())
    }

    @Test
    fun `actual F03 F12 and F13 six kilometer revisit grants the title at the last matched timestamp`() {
        var id = 0
        val ids = IdGenerator { "id-${++id}" }
        val validator = LocationValidator()
        val recorder = TrackRecorder(ids)
        var old = recorder.start("past", NOW_MS - THIRTY_DAYS_MS, "old").snapshot
        val points = mutableListOf<TrackPoint>()
        (0..250).forEach { index ->
            val sample = LocationSample(NOW_MS - THIRTY_DAYS_MS + index * 1_000L, index * 1_000L,
                RegionTestFixtures.point(0.0, index * 25.0), null, 0.0, 5.0)
            val update = recorder.process(old, validator.validate(sample, old.previous), index.toLong(), emptyMap())
            points.addAll(update.points)
            old = update.snapshot
        }
        points.addAll(recorder.finish(old, NOW_MS - THIRTY_DAYS_MS + 251_000, points).points)
        val history = listOf(HistoricalTrack("past", points))
        var current = recorder.start("trip", NOW_MS, "now").snapshot
        val detector = GhostDetector(ids)
        var ghost = GhostSnapshot("trip", "now")
        (0..240).forEach { index ->
            val sample = LocationSample(NOW_MS + index * 1_000L, index * 1_000L,
                RegionTestFixtures.point(0.0, index * 25.0), null, 0.0, 5.0)
            val validation = validator.validate(sample, current.previous) as LocationValidation.Accepted
            val track = recorder.process(current, validation, index.toLong(), emptyMap())
            ghost = detector.process(ghost, GhostInput.Location(validation.location, track.connectedDistanceM,
                track.newSegment), index.toLong(), history).snapshot
            current = track.snapshot
        }
        val finished = detector.process(ghost, GhostInput.TripEnded, 241).events
            .filterIsInstance<GhostEvent.Finished>().single().segment
        val result = GhostResultBuilder().build(finished, history)
        val qualified = TitleEvaluator().evaluate(
            AchievementFact.GhostFinished(result, finished.endedAtMs),
            AchievementProgress())
        assertEquals(6_000.0, result.matchedDistanceM, 0.001)
        assertEquals("ROAD_REVISITOR", qualified.single().titleId)
        assertEquals(NOW_MS + 240_000, qualified.single().acquiredAtMs)
    }
}
