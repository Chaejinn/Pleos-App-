package com.pleos.explorer.domain.usecase

import com.pleos.explorer.domain.GhostTestFixtures.NOW_MS
import com.pleos.explorer.domain.GhostTestFixtures.history
import com.pleos.explorer.domain.GhostTestFixtures.location
import com.pleos.explorer.domain.engine.GhostDetector
import com.pleos.explorer.domain.geo.GeoDistance
import com.pleos.explorer.domain.geo.GridProjector
import com.pleos.explorer.domain.model.GhostEvent
import com.pleos.explorer.domain.model.GhostInput
import com.pleos.explorer.domain.model.GhostSegment
import com.pleos.explorer.domain.model.GhostSnapshot
import com.pleos.explorer.domain.model.GhostUpdate
import com.pleos.explorer.domain.model.HistoricalTrack
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
        var queriedKeys = emptySet<String>()
        var excludedTrip = ""
        private var id = 0
        val useCase = ProcessGhostInputUseCase(this, this, GhostDetector(IdGenerator { "ghost-${++id}" }))

        suspend fun begin() = useCase.start("current", "session")
        suspend fun move(sequence: Long, northM: Double, elapsedMs: Long = sequence * 1_000,
            eastM: Double = 0.0, bearing: Double? = 0.0): GhostUpdate {
            val input = location(northM, elapsedMs, eastM, bearing)
            val previous = state?.previous
            val connected = previous != null && input.elapsedMs > previous.elapsedMs &&
                input.elapsedMs - previous.elapsedMs < 10_000
            val distance = if (connected) GeoDistance.meters(requireNotNull(previous).point, input.point) else 0.0
            return useCase.process("current", "session", sequence, GhostInput.Location(input, distance, !connected))
        }
        override suspend fun load(tripId: String): GhostSnapshot? = state
        override suspend fun findNearby(gridKeys: Set<String>, excludedTripId: String): List<HistoricalTrack> {
            if (failHistory) throw IOException("history read failed")
            queriedKeys = gridKeys
            excludedTrip = excludedTripId
            return history
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
    fun `historical gaps and segment changes reset pending progress`() = runBlocking {
        listOf(false, true).forEach { gap ->
            val past = history().let { it.copy(points = it.points.map { p ->
                if (p.sequence < 10) p else if (gap) p.copy(timeMs = p.timeMs + 10_000)
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
}
