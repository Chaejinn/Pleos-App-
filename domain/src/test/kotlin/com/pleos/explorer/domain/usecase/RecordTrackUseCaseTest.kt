package com.pleos.explorer.domain.usecase

import com.pleos.explorer.domain.engine.LocationValidator
import com.pleos.explorer.domain.engine.TrackRecorder
import com.pleos.explorer.domain.geo.GridProjector
import com.pleos.explorer.domain.geo.PathSimplifier
import com.pleos.explorer.domain.model.ExploredRoad
import com.pleos.explorer.domain.model.GeoPoint
import com.pleos.explorer.domain.model.LocationRejection
import com.pleos.explorer.domain.model.LocationSample
import com.pleos.explorer.domain.model.LocationValidation
import com.pleos.explorer.domain.model.RecordingSnapshot
import com.pleos.explorer.domain.model.TrackPoint
import com.pleos.explorer.domain.model.TrackUpdate
import com.pleos.explorer.domain.model.TripStatus
import com.pleos.explorer.domain.port.IdGenerator
import com.pleos.explorer.domain.port.TrackRecordingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.math.PI

class RecordTrackUseCaseTest {
    private val earthRadiusM = 6_371_008.8

    private fun sample(meters: Double, elapsedMs: Long, accuracyM: Double? = 5.0) = LocationSample(
        timeMs = 1_000_000 + elapsedMs,
        elapsedMs = elapsedMs,
        point = GeoPoint(36.0 + meters / earthRadiusM * 180 / PI, 127.0),
        speedMps = null,
        bearingDeg = null,
        accuracyM = accuracyM,
    )

    private fun fixture(): Pair<MemoryStore, RecordTrackUseCase> {
        val store = MemoryStore()
        var id = 0
        return store to RecordTrackUseCase(store, TrackRecorder(IdGenerator { "id-${++id}" }))
    }

    private suspend fun RecordTrackUseCase.begin() = start("trip", 1_000_000, "session")

    @Test
    fun `accuracy boundary is inclusive and missing invalid values are rejected`() {
        val validator = LocationValidator()
        assertTrue(validator.validate(sample(0.0, 0, 50.0), null) is LocationValidation.Accepted)
        val cases = listOf(
            sample(0.0, 0, 50.001) to LocationRejection.LOW_ACCURACY,
            sample(0.0, 0, null) to LocationRejection.MISSING_ACCURACY,
            sample(0.0, 0, -1.0) to LocationRejection.INVALID_VALUE,
            sample(0.0, 0, Double.NaN) to LocationRejection.INVALID_VALUE,
            sample(0.0, -1) to LocationRejection.INVALID_VALUE,
            sample(0.0, 0).copy(point = GeoPoint(91.0, 127.0)) to LocationRejection.INVALID_VALUE,
            sample(0.0, 0).copy(point = GeoPoint(36.0, Double.POSITIVE_INFINITY)) to
                LocationRejection.INVALID_VALUE,
            sample(0.0, 0).copy(speedMps = -1.0) to LocationRejection.INVALID_VALUE,
            sample(0.0, 0).copy(bearingDeg = 360.0) to LocationRejection.INVALID_VALUE,
        )
        cases.forEach { (input, reason) ->
            assertEquals(LocationValidation.Rejected(reason), validator.validate(input, null))
        }
    }

    @Test
    fun `monotonic order and jumps use last accepted location`() {
        val validator = LocationValidator()
        val previous = (validator.validate(sample(0.0, 1_000), null) as LocationValidation.Accepted).location
        assertEquals(LocationValidation.Rejected(LocationRejection.OUT_OF_ORDER),
            validator.validate(sample(1.0, 1_000), previous))
        assertEquals(LocationValidation.Rejected(LocationRejection.OUT_OF_ORDER),
            validator.validate(sample(1.0, 999), previous))
        assertTrue(validator.validate(sample(55.55, 2_000), previous) is LocationValidation.Accepted)
        assertEquals(LocationValidation.Rejected(LocationRejection.JUMP),
            validator.validate(sample(55.57, 2_000), previous))
        assertTrue(validator.validate(sample(5000.0, 11_000), previous) is LocationValidation.Accepted)
        assertTrue(validator.validate(sample(10.0, 2_000).copy(timeMs = 1), previous)
            is LocationValidation.Accepted)
    }

    @Test
    fun `distance or elapsed time saves while every approved sample adds distance`() = runBlocking {
        val (store, useCase) = fixture()
        useCase.begin()
        useCase.process("trip", "session", 0, sample(0.0, 0))
        useCase.process("trip", "session", 1, sample(19.9, 1_000))
        assertEquals(1, store.points.size)
        assertEquals(19.9, store.snapshot.trip.distanceM, 0.001)
        useCase.process("trip", "session", 2, sample(20.1, 2_000))
        assertEquals(2, store.points.size)
        useCase.process("trip", "session", 3, sample(20.1, 6_999))
        assertEquals(2, store.points.size)
        useCase.process("trip", "session", 4, sample(20.1, 7_000))
        assertEquals(3, store.points.size)
    }

    @Test
    fun `gap preserves unsaved tail and creates separate segment without phantom distance`() = runBlocking {
        val (store, useCase) = fixture()
        useCase.begin()
        useCase.process("trip", "session", 0, sample(0.0, 0))
        useCase.process("trip", "session", 1, sample(10.0, 1_000))
        useCase.process("trip", "session", 2, sample(1000.0, 11_000))
        assertEquals(10.0, store.snapshot.trip.distanceM, 0.001)
        assertEquals(3, store.points.size)
        assertEquals(store.points[0].pathSegmentId, store.points[1].pathSegmentId)
        assertNotEquals(store.points[1].pathSegmentId, store.points[2].pathSegmentId)
        assertEquals(listOf(0L, 1L, 2L), store.points.map { it.sequence })
    }

    @Test
    fun `rejected point does not replace reference or connect a later point`() = runBlocking {
        val (store, useCase) = fixture()
        useCase.begin()
        useCase.process("trip", "session", 0, sample(0.0, 0))
        useCase.process("trip", "session", 1, sample(10.0, 1_000))
        val rejected = useCase.process("trip", "session", 2, sample(500.0, 2_000))
        assertEquals(LocationValidation.Rejected(LocationRejection.JUMP), rejected.validation)
        useCase.process("trip", "session", 3, sample(30.0, 3_000))
        assertEquals(10.0, store.snapshot.trip.distanceM, 0.001)
        assertNotEquals(store.points.first().pathSegmentId, store.points.last().pathSegmentId)
    }

    @Test
    fun `finish retains last approved endpoint and raw points but simplifies display path`() = runBlocking<Unit> {
        val (store, useCase) = fixture()
        useCase.begin()
        (0..10).forEach { useCase.process("trip", "session", it.toLong(), sample(it * 25.0, it * 1_000L)) }
        useCase.process("trip", "session", 11, sample(255.0, 11_000))
        val finished = useCase.finish("trip", 1_020_000)
        assertEquals(TripStatus.FINISHED, finished.snapshot.status)
        assertEquals(255.0, finished.snapshot.trip.distanceM, 0.001)
        assertEquals(12, store.points.size)
        assertEquals(2, finished.snapshot.trip.simplifiedPath.single().points.size)
        assertEquals(store.points.last().point, finished.snapshot.trip.simplifiedPath.single().points.last())
        assertEquals(1_011_000L, store.points.last().timeMs)
        assertTrue(store.roads.isNotEmpty())
        assertEquals(255.0, store.roads.values.sumOf { it.firstExploredDistanceM }, 0.001)
        val revision = store.snapshot.revision
        useCase.finish("trip", 1_030_000)
        assertEquals(revision, store.snapshot.revision)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { useCase.process("trip", "session", 12, sample(260.0, 12_000)) }
        }
    }

    @Test
    fun `short trip discards raw points and pending explored roads`() = runBlocking {
        val (store, useCase) = fixture()
        useCase.begin()
        (0..7).forEach { useCase.process("trip", "session", it.toLong(), sample(it * 25.0, it * 1_000L)) }
        val finished = useCase.finish("trip", 1_010_000)
        assertEquals(TripStatus.DISCARDED, finished.snapshot.status)
        assertTrue(store.points.isEmpty())
        assertTrue(store.roads.isEmpty())
        assertTrue(finished.snapshot.trip.simplifiedPath.isEmpty())
    }

    @Test
    fun `restart with reset monotonic clock flushes tail and does not bridge sessions`() = runBlocking<Unit> {
        val (store, first) = fixture()
        first.begin()
        first.process("trip", "session", 0, sample(0.0, 50_000))
        first.process("trip", "session", 1, sample(10.0, 51_000))
        val second = RecordTrackUseCase(store, TrackRecorder(IdGenerator { "resumed-id" }))
        second.resume("trip", "session-2")
        second.process("trip", "session-2", 0, sample(1000.0, 0))
        assertEquals(10.0, store.snapshot.trip.distanceM, 0.001)
        assertEquals(3, store.points.size)
        assertNotEquals(store.points.first().pathSegmentId, store.points.last().pathSegmentId)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { second.process("trip", "session", 2, sample(1010.0, 1000)) }
        }
    }

    @Test
    fun `failed commit preserves persisted state and retry has no duplicate distance or visit`() = runBlocking {
        val (store, useCase) = fixture()
        useCase.begin()
        useCase.process("trip", "session", 0, sample(0.0, 0))
        val before = store.snapshot
        store.failNext = true
        assertThrows(IOException::class.java) {
            runBlocking { useCase.process("trip", "session", 1, sample(25.0, 1_000)) }
        }
        assertEquals(before, store.snapshot)
        useCase.process("trip", "session", 1, sample(25.0, 1_000))
        val committed = store.snapshot
        useCase.process("trip", "session", 1, sample(25.0, 1_000))
        useCase.process("trip", "session", 0, sample(0.0, 0))
        assertEquals(committed, store.snapshot)
        assertEquals(2, store.points.size)
        assertEquals(25.0, committed.trip.distanceM, 0.001)
        assertEquals(1, committed.pendingRoads.values.single().visitCount)
    }

    @Test
    fun `cancellation propagates without changing snapshot`() = runBlocking {
        val (store, useCase) = fixture()
        useCase.begin()
        val before = store.snapshot
        store.cancelNext = true
        assertThrows(CancellationException::class.java) {
            runBlocking { useCase.process("trip", "session", 0, sample(0.0, 0)) }
        }
        assertEquals(before, store.snapshot)
    }

    @Test
    fun `crossed cells receive actual distance and reverse traversal is not new distance`() = runBlocking {
        val (store, useCase) = fixture()
        useCase.begin()
        val positions = (0..10).map { it * 25.0 } + (9 downTo 0).map { it * 25.0 }
        positions.forEachIndexed { i, m -> useCase.process("trip", "session", i.toLong(), sample(m, i * 1_000L)) }
        useCase.finish("trip", 1_030_000)
        assertEquals(500.0, store.snapshot.trip.distanceM, 0.001)
        assertEquals(250.0, store.roads.values.sumOf { it.firstExploredDistanceM }, 0.001)
        assertTrue(store.roads.values.all { it.visitCount == 1 })
    }

    @Test
    fun `previously explored cell gets a visit but no new distance`() = runBlocking {
        val (store, useCase) = fixture()
        val key = GridProjector().key(sample(0.0, 0).point)
        store.roads[key] = ExploredRoad(key, "past", 10, 20, 2, 77.0)
        useCase.begin()
        (0..10).forEach { useCase.process("trip", "session", it.toLong(), sample(it * 25.0, it * 1_000L)) }
        useCase.finish("trip", 1_020_000)
        assertEquals(3, store.roads.getValue(key).visitCount)
        assertEquals(77.0, store.roads.getValue(key).firstExploredDistanceM, 0.0)
        assertEquals("past", store.roads.getValue(key).firstTripId)
    }

    @Test
    fun `grid floor handles negative coordinates and line includes intermediate cells`() {
        val grid = GridProjector()
        assertEquals("v1:0:0", grid.key(GeoPoint(36.0, 127.0)))
        assertEquals("v1:0:-1", grid.key(sample(-1.0, 0).point))
        val pieces = grid.split(sample(10.0, 0).point, sample(350.0, 0).point)
        assertEquals(listOf("v1:0:0", "v1:0:1", "v1:0:2", "v1:0:3"), pieces.map { it.gridKey })
        assertEquals(340.0, pieces.sumOf { it.distanceM }, 0.001)
        assertEquals(9, grid.neighbors("v1:0:-1").size)
    }

    @Test
    fun `simplifier preserves a corner beyond five meters and handles repeated points`() {
        val straight = (0..10).map { sample(it * 10.0, 0).point }
        assertEquals(listOf(straight.first(), straight.last()), PathSimplifier().simplify(straight))
        val corner = listOf(GeoPoint(36.0, 127.0), GeoPoint(36.001, 127.0), GeoPoint(36.001, 127.001))
        assertEquals(corner, PathSimplifier().simplify(corner))
        assertTrue(PathSimplifier().simplify(emptyList()).isEmpty())
        assertEquals(listOf(corner.first()), PathSimplifier().simplify(listOf(corner.first())))
        assertFalse(PathSimplifier().simplify(List(100) { corner.first() }).isEmpty())
    }

    private class MemoryStore : TrackRecordingStore {
        lateinit var snapshot: RecordingSnapshot
        val points = mutableListOf<TrackPoint>()
        val roads = mutableMapOf<String, ExploredRoad>()
        var failNext = false
        var cancelNext = false

        override suspend fun load(tripId: String): RecordingSnapshot? =
            if (::snapshot.isInitialized && snapshot.trip.id == tripId) snapshot else null

        override suspend fun loadPoints(tripId: String): List<TrackPoint> = points.toList()

        override suspend fun findRoads(gridKeys: Set<String>): Map<String, ExploredRoad> =
            roads.filterKeys { it in gridKeys }

        override suspend fun commit(expectedRevision: Long?, update: TrackUpdate) {
            if (cancelNext) {
                cancelNext = false
                throw CancellationException("cancelled")
            }
            if (failNext) {
                failNext = false
                throw IOException("disk full")
            }
            check(expectedRevision == if (::snapshot.isInitialized) snapshot.revision else null)
            if (update.snapshot.status == TripStatus.DISCARDED) points.clear() else points.addAll(update.points)
            roads.putAll(update.roads)
            snapshot = update.snapshot
        }
    }
}
