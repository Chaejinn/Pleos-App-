package com.pleos.explorer.domain.usecase

import com.pleos.explorer.domain.GhostTestFixtures.NOW_MS
import com.pleos.explorer.domain.GhostTestFixtures.history
import com.pleos.explorer.domain.GhostTestFixtures.koreanHistory
import com.pleos.explorer.domain.GhostTestFixtures.location
import com.pleos.explorer.domain.GhostTestFixtures.revisitedHistory
import com.pleos.explorer.domain.RegionTestFixtures
import com.pleos.explorer.domain.engine.GhostDetector
import com.pleos.explorer.domain.engine.LocationValidator
import com.pleos.explorer.domain.engine.TrackRecorder
import com.pleos.explorer.domain.geo.GeoDistance
import com.pleos.explorer.domain.geo.GridProjector
import com.pleos.explorer.domain.model.GhostCursor
import com.pleos.explorer.domain.model.GhostEvent
import com.pleos.explorer.domain.model.GhostInput
import com.pleos.explorer.domain.model.GhostSegment
import com.pleos.explorer.domain.model.GhostSnapshot
import com.pleos.explorer.domain.model.GhostUpdate
import com.pleos.explorer.domain.model.HistoricalTrack
import com.pleos.explorer.domain.model.LocationSample
import com.pleos.explorer.domain.model.LocationValidation
import com.pleos.explorer.domain.model.TrackPoint
import com.pleos.explorer.domain.port.GhostStore
import com.pleos.explorer.domain.port.HistoryRepository
import com.pleos.explorer.domain.port.IdGenerator
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessGhostInputUseCaseTest {
    private class Fixture(var history: List<HistoricalTrack> = listOf(history())) : GhostStore, HistoryRepository {
        var state: GhostSnapshot? = null
        val segments = mutableMapOf<String, GhostSegment>()
        val events = mutableListOf<GhostEvent>()
        var failCommit = false
        var loseAcknowledgement = false
        var cancelCommit = false
        var failHistory = false
        var failLoad = false
        var boundedHistory = false
        var failForward = false
        var forwardQueryCount = 0
        var lastForwardLimitM = 0.0
        var lastForwardPoints = emptyList<TrackPoint>()
        var transformForward: (HistoricalTrack) -> HistoricalTrack = { it }
        var queriedKeys = emptySet<String>()
        var excludedTrip = ""
        private var id = 0
        private val recorder = TrackRecorder(IdGenerator { "point-${++id}" })
        private var recording = recorder.start("current", NOW_MS, "session").snapshot
        val useCase = ProcessGhostInputUseCase(this, this, GhostDetector(IdGenerator { "ghost-${++id}" }))

        suspend fun begin() = useCase.start("current", "session")
        suspend fun approve(sequence: Long, northM: Double, bearing: Double? = 0.0): GhostUpdate {
            val validation = LocationValidator().validate(LocationSample(NOW_MS + sequence * 1_000,
                sequence * 1_000, RegionTestFixtures.point(0.0, northM), null, bearing, 5.0), recording.previous)
            check(validation is LocationValidation.Accepted)
            val update = recorder.process(recording, validation, sequence, emptyMap())
            val result = useCase.process("current", "session", sequence, GhostInput.Location(validation.location,
                update.connectedDistanceM, update.newSegment))
            recording = update.snapshot
            return result
        }
        suspend fun move(sequence: Long, northM: Double, elapsedMs: Long = sequence * 1_000,
            eastM: Double = 0.0, bearing: Double? = 0.0): GhostUpdate {
            val input = location(northM, elapsedMs, eastM, bearing)
            val previous = state?.previous
            val connected = previous != null && input.elapsedMs > previous.elapsedMs &&
                input.elapsedMs - previous.elapsedMs < 10_000
            val distance = if (connected) GeoDistance.meters(requireNotNull(previous).point, input.point) else 0.0
            return useCase.process("current", "session", sequence, GhostInput.Location(input, distance, !connected))
        }
        override suspend fun load(tripId: String): GhostSnapshot? {
            if (failLoad) throw IOException("snapshot read failed")
            return state
        }
        override suspend fun findNearby(gridKeys: Set<String>, excludedTripId: String): List<HistoricalTrack> {
            if (failHistory) throw IOException("history read failed")
            queriedKeys = gridKeys
            excludedTrip = excludedTripId
            if (!boundedHistory) return history
            return history.filter { it.tripId != excludedTripId }.map { track ->
                val selected = mutableSetOf<Int>()
                track.points.forEachIndexed { index, point ->
                    if (point.gridKey in gridKeys) {
                        selected.add(index)
                        if (index > 0) selected.add(index - 1)
                        if (index < track.points.lastIndex) selected.add(index + 1)
                    }
                }
                track.copy(points = selected.sorted().map { track.points[it] })
            }
        }
        override suspend fun findForward(cursor: GhostCursor, maxDistanceM: Double): HistoricalTrack {
            forwardQueryCount++
            lastForwardLimitM = maxDistanceM
            if (failForward) throw IOException("forward history read failed")
            val stored = history.singleOrNull { it.tripId == cursor.key.tripId }?.points.orEmpty()
            val start = stored.indexOfFirst { it.sequence == cursor.from.sequence }
            if (start < 0) return HistoricalTrack(cursor.key.tripId, emptyList())
            val result = mutableListOf(stored[start])
            var remainingM = maxDistanceM + GeoDistance.meters(cursor.from.point, cursor.to.point) * cursor.fraction
            for (index in start + 1..stored.lastIndex) {
                val previous = result.last()
                val next = stored[index]
                if (next.pathSegmentId != cursor.key.pathSegmentId || next.sequence - previous.sequence != 1L) break
                result.add(next)
                remainingM -= GeoDistance.meters(previous.point, next.point)
                if (remainingM <= 0.0) break
            }
            lastForwardPoints = result.toList()
            return transformForward(HistoricalTrack(cursor.key.tripId, lastForwardPoints))
        }
        override suspend fun commit(expectedRevision: Long?, update: GhostUpdate) {
            if (failCommit) { failCommit = false; throw IOException("commit failed") }
            if (cancelCommit) { cancelCommit = false; throw CancellationException("cancelled") }
            check(expectedRevision == state?.revision)
            state = update.snapshot
            events.addAll(update.events)
            update.events.filterIsInstance<GhostEvent.Finished>().forEach { segments[it.segment.id] = it.segment }
            if (loseAcknowledgement) { loseAcknowledgement = false; throw IOException("ack lost") }
        }
    }

    @Test
    fun `single past segment needs 500 matched meters and starts once`() = runBlocking {
        val f = Fixture()
        f.begin()
        (0..19).forEach { f.move(it.toLong(), it * 25.0) }
        assertTrue(f.events.filterIsInstance<GhostEvent.Started>().isEmpty())
        f.move(20, 500.01)
        assertEquals("past", f.events.filterIsInstance<GhostEvent.Started>().single().pastTripId)
        f.move(21, 525.0)
        assertEquals(1, f.events.filterIsInstance<GhostEvent.Started>().size)
        assertFalse(f.events.filterIsInstance<GhostEvent.Updated>().isEmpty())
    }

    @Test
    fun `history queries use neighboring grids and exclude the current trip`() = runBlocking {
        val f = Fixture(listOf(history("current")))
        f.begin()
        f.move(0, 0.0)
        assertEquals(GridProjector().neighbors(GridProjector().key(location(0.0, 0).point)), f.queriedKeys)
        assertEquals("current", f.excludedTrip)
        (1..25).forEach { f.move(it.toLong(), it * 25.0) }
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun `different past trips cannot pool short matching pieces`() = runBlocking {
        val f = Fixture(listOf(history("a", count = 11), history("b").let {
            it.copy(points = it.points.filter { p -> p.sequence >= 10 })
        }))
        f.begin()
        (0..22).forEach { f.move(it.toLong(), it * 25.0) }
        assertTrue(f.events.filterIsInstance<GhostEvent.Started>().isEmpty())
    }

    @Test
    fun `historical sequence gaps and segment changes reset pending progress`() = runBlocking {
        listOf(false, true).forEach { gap ->
            val past = history().let { it.copy(points = it.points.map { p ->
                if (p.sequence < 10) p else if (gap) p.copy(sequence = p.sequence + 1)
                else p.copy(pathSegmentId = "next")
            }) }
            val f = Fixture(listOf(past))
            f.begin()
            (0..22).forEach { f.move(it.toLong(), it * 25.0) }
            assertTrue(f.events.filterIsInstance<GhostEvent.Started>().isEmpty())
        }
    }

    @Test
    fun `most recent eligible trip wins and stays locked while matching`() = runBlocking {
        val f = Fixture(listOf(history("old", 100_000), history("recent", 200_000)))
        f.begin()
        (0..21).forEach { f.move(it.toLong(), it * 25.0) }
        assertEquals("recent", requireNotNull(f.state?.active).cursor.from.tripId)
        f.history = f.history + history("newest", 300_000)
        (22..25).forEach { f.move(it.toLong(), it * 25.0) }
        assertEquals("recent", requireNotNull(f.state?.active).cursor.from.tripId)
        val tie = Fixture(listOf(history("z"), history("a")))
        tie.begin()
        (0..21).forEach { tie.move(it.toLong(), it * 25.0) }
        assertEquals("a", tie.events.filterIsInstance<GhostEvent.Started>().single().pastTripId)
    }

    @Test
    fun `two hundred unmatched meters close at last match without adding exit distance`() = runBlocking {
        val f = Fixture()
        f.begin()
        (0..21).forEach { f.move(it.toLong(), it * 25.0) }
        val matched = requireNotNull(f.state?.active).matchedDistanceM
        f.history = emptyList()
        f.move(22, 724.0, 27_000)
        assertTrue(f.segments.isEmpty())
        f.move(23, 725.01, 28_000)
        val result = f.segments.values.single()
        assertEquals(matched, result.matchedDistanceM, 0.0)
        assertEquals(NOW_MS + 21_000, result.endedAtMs)
        assertNull(f.state?.active)
    }

    @Test
    fun `rematching resets mismatch distance without crediting an unmatched bridge`() = runBlocking {
        val f = Fixture()
        f.begin()
        (0..21).forEach { f.move(it.toLong(), it * 25.0) }
        val matched = requireNotNull(f.state?.active).matchedDistanceM
        val past = f.history
        f.history = emptyList()
        f.move(22, 550.0)
        f.history = past
        f.move(23, 575.0)
        assertEquals(matched, requireNotNull(f.state?.active).matchedDistanceM, 0.0)
        assertEquals(0.0, requireNotNull(f.state?.active).mismatchedDistanceM, 0.0)
        f.move(24, 600.0)
        assertEquals(matched + 25, requireNotNull(f.state?.active).matchedDistanceM, 0.001)
    }

    @Test
    fun `rematching after multiple misses before 200 meters preserves the active ghost`() = runBlocking {
        listOf(3, 7).forEach { misses ->
            val f = Fixture()
            f.begin()
            (0..21).forEach { f.move(it.toLong(), it * 25.0) }
            val active = requireNotNull(f.state?.active)
            val past = f.history
            f.history = emptyList()
            (1..misses).forEach { f.move(21L + it, 525.0 + it * 25) }
            f.history = past
            f.move(22L + misses, 550.0 + misses * 25)
            assertEquals(active.id, f.state?.active?.id)
            assertEquals(active.matchedDistanceM, requireNotNull(f.state?.active).matchedDistanceM, 0.0)
            assertEquals(0.0, requireNotNull(f.state?.active).mismatchedDistanceM, 0.0)
            f.move(23L + misses, 575.0 + misses * 25)
            assertEquals(active.matchedDistanceM + 25, requireNotNull(f.state?.active).matchedDistanceM, 0.001)
            assertTrue(f.segments.isEmpty())
        }
    }

    @Test
    fun `diagonal shortcuts outside historical corridor cannot accumulate 500 matched meters`() = runBlocking {
        val shape = (0..32).map { index ->
            val block = index / 8
            val step = index % 8
            location(block * 100.0 + minOf(step, 4) * 25.0, index * 1_000L,
                block * 100.0 + maxOf(step - 4, 0) * 25.0,
                if (step < 4) 0.0 else 90.0)
        }
        val past = HistoricalTrack("stairs", shape.mapIndexed { index, p ->
            TrackPoint("stairs-$index", "stairs", 1_000_000 + index * 1_000L,
                p.point, null, p.bearingDeg, 5.0, GridProjector().key(p.point), "segment", index.toLong())
        })
        val f = Fixture(listOf(past))
        f.begin()
        (0..4).forEach { f.move(it.toLong(), it * 100.0, it * 5_000L, it * 100.0, 45.0) }
        assertTrue(f.events.filterIsInstance<GhostEvent.Started>().isEmpty())
    }

    @Test
    fun `actual F03 history remains matchable across wall clock corrections`() = runBlocking {
        listOf(-60_000L, 60_000L).forEach { offset ->
            var id = 0
            val recorder = TrackRecorder(IdGenerator { "track-${++id}" })
            var track = recorder.start("past", 1_000_000, "past-session").snapshot
            val points = (0..30).flatMap { index ->
                val location = location(index * 25.0, index * 1_000L)
                val validation = LocationValidator().validate(LocationSample(
                    1_000_000 + index * 1_000L + if (index >= 10) offset else 0,
                    location.elapsedMs, location.point, null, 0.0, 5.0), track.previous)
                assertTrue(validation is LocationValidation.Accepted)
                val update = recorder.process(track, validation, index.toLong(), emptyMap())
                track = update.snapshot
                update.points
            }
            assertEquals(1, points.map { it.pathSegmentId }.distinct().size)
            val f = Fixture(listOf(HistoricalTrack("past", points)))
            f.begin()
            (0..22).forEach { f.move(it.toLong(), it * 25.0) }
            assertEquals(1, f.events.filterIsInstance<GhostEvent.Started>().size)
        }
    }

    @Test
    fun `gap ends active ghost and does not connect pending progress`() = runBlocking {
        val f = Fixture()
        f.begin()
        (0..21).forEach { f.move(it.toLong(), it * 25.0) }
        f.move(22, 550.0, 31_000)
        assertEquals(1, f.segments.size)
        assertNull(f.state?.active)
        assertTrue(requireNotNull(f.state).candidates.all { it.matchedDistanceM == 0.0 })
    }

    @Test
    fun `duplicate input and older observation cannot add distance or events`() = runBlocking {
        val f = Fixture()
        f.begin()
        f.move(0, 0.0)
        f.move(1, 25.0)
        val before = f.state
        f.move(1, 25.0)
        assertEquals(before, f.state)
        f.move(2, 10.0, 999)
        assertEquals(before?.candidates, f.state?.candidates)
    }

    @Test
    fun `commit failure cancellation and lost acknowledgement cannot double start or finish`() = runBlocking {
        val f = Fixture()
        f.begin()
        (0..19).forEach { f.move(it.toLong(), it * 25.0) }
        val before = f.state
        f.failCommit = true
        assertThrows(IOException::class.java) { runBlocking { f.move(20, 500.01) } }
        assertEquals(before, f.state)
        f.cancelCommit = true
        assertThrows(CancellationException::class.java) { runBlocking { f.move(20, 500.01) } }
        assertEquals(before, f.state)
        f.loseAcknowledgement = true
        assertThrows(IOException::class.java) { runBlocking { f.move(20, 500.01) } }
        f.move(20, 500.01)
        assertEquals(1, f.events.filterIsInstance<GhostEvent.Started>().size)
        f.loseAcknowledgement = true
        assertThrows(IOException::class.java) {
            runBlocking { f.useCase.process("current", "session", 21, GhostInput.TripEnded) }
        }
        f.useCase.process("current", "session", 21, GhostInput.TripEnded)
        assertEquals(1, f.segments.size)
    }

    @Test
    fun `resume closes at last match clears old clock and can be retried`() = runBlocking {
        val f = Fixture()
        f.begin()
        (0..21).forEach { f.move(it.toLong(), it * 25.0) }
        f.loseAcknowledgement = true
        assertThrows(IOException::class.java) { runBlocking { f.useCase.resume("current", "new") } }
        f.useCase.resume("current", "new")
        assertEquals(1, f.segments.size)
        assertEquals(NOW_MS + 21_000, f.segments.values.single().endedAtMs)
        assertNull(f.state?.previous)
        assertTrue(requireNotNull(f.state).candidates.isEmpty())
        f.useCase.process("current", "new", 0, GhostInput.Location(location(550.0, 0), 0.0, true))
        assertNull(f.state?.active)
    }

    @Test
    fun `history failure propagates without advancing snapshot`() = runBlocking {
        val f = Fixture()
        f.begin()
        val before = f.state
        f.failHistory = true
        assertThrows(IOException::class.java) { runBlocking { f.move(0, 0.0) } }
        assertEquals(before, f.state)
    }

    @Test
    fun `actual F03 rejection closes ghost and next approved segment starts fresh`() = runBlocking {
        val f = Fixture()
        f.begin()
        var id = 0
        val recorder = TrackRecorder(IdGenerator { "current-${++id}" })
        var track = recorder.start("current", NOW_MS, "session").snapshot
        suspend fun sample(sequence: Long, northM: Double, accuracyM: Double) {
            val p = location(northM, sequence * 1_000)
            val validation = LocationValidator().validate(LocationSample(p.timeMs, p.elapsedMs, p.point,
                null, p.bearingDeg, accuracyM), track.previous)
            val update = recorder.process(track, validation, sequence, emptyMap())
            val input = when (validation) {
                is LocationValidation.Accepted -> GhostInput.Location(validation.location,
                    update.connectedDistanceM, update.newSegment)
                is LocationValidation.Rejected -> GhostInput.ContinuityLost
            }
            f.useCase.process("current", "session", sequence, input)
            track = update.snapshot
        }
        (0..21).forEach { sample(it.toLong(), it * 25.0, 5.0) }
        assertEquals(1, f.events.filterIsInstance<GhostEvent.Started>().size)
        sample(22, 550.0, 100.0)
        assertEquals(1, f.segments.size)
        assertNull(f.state?.previous)
        sample(23, 575.0, 5.0)
        assertNull(f.state?.active)
        assertTrue(requireNotNull(f.state).candidates.all { it.matchedDistanceM == 0.0 })
    }

    @Test
    fun `repeated motion around one historical position cannot manufacture a ghost`() = runBlocking {
        val f = Fixture()
        f.begin()
        (0..80).forEach { f.move(it.toLong(), if (it % 2 == 0) 0.0 else 25.0) }
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun `startup retries and snapshot read failure preserve committed state`() = runBlocking {
        val f = Fixture()
        f.loseAcknowledgement = true
        assertThrows(IOException::class.java) { runBlocking { f.begin() } }
        f.begin()
        val before = f.state
        f.failLoad = true
        assertThrows(IOException::class.java) { runBlocking { f.useCase.resume("current", "new") } }
        assertEquals(before, f.state)
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun `invalid lifecycle and disconnected distances fail before committing`() = runBlocking<Unit> {
        val f = Fixture()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { f.useCase.start("", "session") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { f.useCase.start("current", "") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { f.useCase.resume("current", "new") } }
        f.begin()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { f.useCase.start("current", "other") } }
        val before = f.state
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, 1.0).forEach { distance ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { f.useCase.process("current", "session", 0,
                    GhostInput.Location(location(0.0, 0), distance, true)) }
            }
            assertEquals(before, f.state)
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { f.useCase.process("current", "wrong", 0, GhostInput.TripEnded) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { f.useCase.process("current", "session", -1, GhostInput.TripEnded) }
        }
        f.move(0, 0.0)
        val located = f.state
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { f.useCase.process("current", "session", 1,
                GhostInput.Location(location(25.0, 1_000), 50.0)) }
        }
        assertEquals(located, f.state)
        f.useCase.process("current", "session", 1, GhostInput.TripEnded)
        assertTrue(requireNotNull(f.state).finished)
        assertTrue(f.events.isEmpty())
        assertThrows(IllegalStateException::class.java) { runBlocking { f.move(2, 50.0) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { f.useCase.resume("current", "new") } }
    }

    @Test
    fun `current wall clock reversal preserves raw observations and ordered segment summary`() = runBlocking {
        val f = Fixture()
        f.begin()
        (0..22).forEach { index ->
            val sample = location(index * 25.0, index * 1_000L)
            val validation = LocationValidator().validate(LocationSample(
                sample.timeMs - if (index >= 10) 60_000 else 0, sample.elapsedMs, sample.point,
                null, sample.bearingDeg, 5.0), f.state?.previous) as LocationValidation.Accepted
            val distance = f.state?.previous?.let { GeoDistance.meters(it.point, sample.point) } ?: 0.0
            f.useCase.process("current", "session", index.toLong(),
                GhostInput.Location(validation.location, distance, index == 0))
        }
        f.useCase.process("current", "session", 23, GhostInput.TripEnded)
        val segment = f.segments.values.single()
        assertEquals(NOW_MS, segment.startedAtMs)
        assertEquals(NOW_MS, segment.endedAtMs)
        assertEquals(NOW_MS + 22_000 - 60_000, segment.sections.last().currentTo.timeMs)
    }

    @Test
    fun `later pass in one past segment qualifies without pooling separate passes`() = runBlocking {
        val past = revisitedHistory()
        assertEquals(1, past.points.map { it.pathSegmentId }.distinct().size)
        val f = Fixture(listOf(past))
        f.begin()
        f.approve(0, 0.0)
        assertTrue(requireNotNull(f.state).candidates.any { it.cursor.from.sequence >= 32 })
        (1..19).forEach { f.approve(it.toLong(), it * 25.0) }
        assertTrue(f.events.filterIsInstance<GhostEvent.Started>().isEmpty())
        (20..24).forEach { f.approve(it.toLong(), it * 25.0) }
        assertEquals(1, f.events.filterIsInstance<GhostEvent.Started>().size)
        assertTrue(requireNotNull(f.state?.active).sections.all { it.pastFrom.from.sequence >= 32 })
    }

    @Test
    fun `bounded history retains active ghost after 175 meter direction outage and stationary recovery`() = runBlocking {
        val f = Fixture(listOf(koreanHistory()))
        f.boundedHistory = true
        f.begin()
        (0..21).forEach { f.approve(it.toLong(), it * 25.0) }
        val active = requireNotNull(f.state?.active)
        (22..28).forEach { f.approve(it.toLong(), it * 25.0, null) }
        assertEquals(175.0, requireNotNull(f.state?.active).mismatchedDistanceM, 0.001)
        val queriesBeforeRecovery = f.forwardQueryCount
        f.approve(29, 700.0)
        assertEquals(queriesBeforeRecovery + 1, f.forwardQueryCount)
        assertEquals(235.0, f.lastForwardLimitM, 0.001)
        assertTrue(f.lastForwardPoints.any { GeoDistance.meters(it.point, RegionTestFixtures.point(0.0, 550.0)) < 0.001 })
        assertTrue(f.lastForwardPoints.size < f.history.single().points.size)
        assertEquals(active.id, f.state?.active?.id)
        assertEquals(0.0, requireNotNull(f.state?.active).mismatchedDistanceM, 0.0)
        assertEquals(active.matchedDistanceM, requireNotNull(f.state?.active).matchedDistanceM, 0.0)
        f.approve(30, 725.0)
        assertTrue(f.segments.isEmpty())
        assertEquals(active.matchedDistanceM + 25, requireNotNull(f.state?.active).matchedDistanceM, 0.001)
    }

    @Test
    fun `forward read failure preserves state and retry recovers without duplicate events`() = runBlocking {
        val f = Fixture(listOf(koreanHistory()))
        f.boundedHistory = true
        f.begin()
        (0..21).forEach { f.approve(it.toLong(), it * 25.0) }
        f.approve(22, 550.0, null)
        val before = f.state
        val events = f.events.toList()
        f.failForward = true
        assertThrows(IOException::class.java) { runBlocking { f.approve(23, 575.0) } }
        assertEquals(before, f.state)
        assertEquals(events, f.events)
        assertTrue(f.segments.isEmpty())
        f.failForward = false
        f.approve(23, 575.0)
        assertEquals(before?.active?.id, f.state?.active?.id)
        assertEquals(0.0, requireNotNull(f.state?.active).mismatchedDistanceM, 0.0)
        assertEquals(events, f.events)
    }

    @Test
    fun `conflicting unsorted or wrong trip forward data fails before commit`() = runBlocking {
        val corruptions: List<(HistoricalTrack) -> HistoricalTrack> = listOf(
            { track -> track.copy(tripId = "unexpected") },
            { track -> track.copy(points = track.points.reversed()) },
            { track -> track.copy(points = track.points.map { it.copy(timeMs = it.timeMs + 1) }) },
        )
        corruptions.forEach { corrupt ->
            val f = Fixture(listOf(koreanHistory()))
            f.boundedHistory = true
            f.begin()
            (0..21).forEach { f.approve(it.toLong(), it * 25.0) }
            f.approve(22, 550.0, null)
            val before = f.state
            val events = f.events.toList()
            f.transformForward = corrupt
            assertThrows(IllegalArgumentException::class.java) { runBlocking { f.approve(23, 575.0) } }
            assertEquals(before, f.state)
            assertEquals(events, f.events)
            assertTrue(f.segments.isEmpty())
        }
    }

    @Test
    fun `duplicate gap and continuity loss skip obsolete forward lookup`() = runBlocking {
        val inputs = listOf(
            GhostInput.Location(location(575.0, 32_000), 0.0),
            GhostInput.Location(location(575.0, 23_000), 0.0, true),
            GhostInput.ContinuityLost,
            GhostInput.TripEnded,
        )
        inputs.forEach { input ->
            val f = Fixture()
            f.begin()
            (0..21).forEach { f.move(it.toLong(), it * 25.0) }
            f.move(22, 550.0, bearing = null)
            f.failForward = true
            val before = f.state
            f.useCase.process("current", "session", 22, input)
            assertEquals(before, f.state)
            f.useCase.process("current", "session", 23, input)
            assertEquals(0, f.forwardQueryCount)
            assertNull(f.state?.active)
            assertEquals(1, f.segments.size)
        }
    }
}
