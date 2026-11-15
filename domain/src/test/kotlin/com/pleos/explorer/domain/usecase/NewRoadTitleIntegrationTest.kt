package com.pleos.explorer.domain.usecase

import com.pleos.explorer.domain.RegionTestFixtures
import com.pleos.explorer.domain.TitleTestFixtures.NOW_MS
import com.pleos.explorer.domain.engine.LocationValidator
import com.pleos.explorer.domain.engine.TrackRecorder
import com.pleos.explorer.domain.model.AchievementFact
import com.pleos.explorer.domain.model.AchievementTitle
import com.pleos.explorer.domain.model.ExploredRoad
import com.pleos.explorer.domain.model.LocationSample
import com.pleos.explorer.domain.model.PendingReward
import com.pleos.explorer.domain.model.TitleSnapshot
import com.pleos.explorer.domain.model.TitleUpdate
import com.pleos.explorer.domain.model.TrackPoint
import com.pleos.explorer.domain.model.TrackUpdate
import com.pleos.explorer.domain.port.IdGenerator
import com.pleos.explorer.domain.port.TitleStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewRoadTitleIntegrationTest {
    private fun drive(id: String, north: List<Double>, known: Map<String, ExploredRoad>): TrackUpdate {
        var sequence = 0
        val recorder = TrackRecorder(IdGenerator { "$id-point-${++sequence}" })
        var state = recorder.start(id, NOW_MS, "session").snapshot
        val points = mutableListOf<TrackPoint>()
        north.forEachIndexed { index, meters ->
            val sample = LocationSample(NOW_MS + index * 1_000L, index * 1_000L,
                RegionTestFixtures.point(0.0, meters), 25.0, 0.0, 5.0)
            val validation = LocationValidator().validate(sample, state.previous)
            val update = recorder.process(state, validation, index.toLong(), known)
            state = update.snapshot
            points.addAll(update.points)
        }
        return recorder.finish(state, NOW_MS + north.size * 1_000L, points)
    }

    private class Store : TitleStore {
        val roads = linkedMapOf<String, ExploredRoad>()
        val titles = linkedMapOf<String, AchievementTitle>()
        val rewards = linkedMapOf<String, PendingReward>()
        var snapshot = TitleSnapshot()
        fun commitTrack(update: TrackUpdate) {
            roads.putAll(update.roads)
            snapshot = snapshot.copy(revision = snapshot.revision + 1)
        }
        override suspend fun load() = snapshot.copy(progress = snapshot.progress.copy(
            newRoadDistanceM = roads.values.sumOf { it.firstExploredDistanceM }))
        override suspend fun commit(expectedRevision: Long, update: TitleUpdate) {
            check(snapshot.revision == expectedRevision)
            update.titles.forEach { check(it.titleId !in titles) }
            update.rewards.forEach { check(it.deduplicationKey !in rewards) }
            titles.putAll(update.titles.associateBy { it.titleId })
            rewards.putAll(update.rewards.associateBy { it.deduplicationKey })
            snapshot = update.snapshot
        }
    }

    @Test
    fun `actual F03 loop credits only new road and duplicate completion never grows cumulative distance`() = runBlocking {
        val store = Store()
        store.roads["historical"] = ExploredRoad("historical", "old", 0, 0, 1, 98_000.0)
        val path = (0..120).map { it * 25.0 } + (119 downTo 0).map { it * 25.0 }
        val finished = drive("trip", path, store.roads)
        val fact = requireNotNull(AchievementFact.TripFinished.from(finished))
        assertEquals(6_000.0, finished.snapshot.trip.distanceM, 0.001)
        assertEquals(3_000.0, fact.newRoadM, 0.001)
        store.commitTrack(finished)
        val useCase = ProcessTitleFactUseCase(store, IdGenerator { "reward" })
        assertEquals("NEW_ROAD_DISCOVERER", useCase.process(fact).titles.single().titleId)
        assertEquals(finished.snapshot.trip.endedAtMs, store.titles.values.single().acquiredAtMs)
        repeat(3) {
            store.commitTrack(finished)
            assertTrue(useCase.process(fact).rewards.isEmpty())
        }
        assertEquals(101_000.0, store.load().progress.newRoadDistanceM, 0.001)
        assertEquals(1, store.rewards.size)
        store.roads.replaceAll { _, road -> road.copy(firstTripId = null) }
        assertEquals(101_000.0, store.load().progress.newRoadDistanceM, 0.001)
        assertTrue(ProcessTitleFactUseCase(store, IdGenerator { "other" }).process(fact).titles.isEmpty())
    }

    @Test
    fun `actual short trip is excluded and revisiting known grids never recredits their stored distances`() = runBlocking {
        val store = Store()
        val discarded = drive("short", (0..4).map { it * 25.0 }, store.roads)
        assertNull(AchievementFact.TripFinished.from(discarded))
        assertTrue(discarded.roads.isEmpty())
        val first = drive("first", (0..12).map { it * 25.0 }, store.roads)
        store.commitTrack(first)
        val useCase = ProcessTitleFactUseCase(store, IdGenerator { "reward" })
        assertTrue(useCase.process(requireNotNull(AchievementFact.TripFinished.from(first))).titles.isEmpty())
        val revisited = drive("second", (0..12).map { it * 25.0 }, store.roads)
        val fact = requireNotNull(AchievementFact.TripFinished.from(revisited))
        assertEquals(0.0, fact.newRoadM, 0.0)
        store.commitTrack(revisited)
        assertEquals(300.0, store.load().progress.newRoadDistanceM, 0.001)
        assertTrue(useCase.process(fact).titles.isEmpty())
        assertTrue(store.rewards.isEmpty())
    }
}
