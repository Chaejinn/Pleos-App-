package com.pleos.explorer.domain.usecase

import com.pleos.explorer.domain.GhostTestFixtures.NOW_MS
import com.pleos.explorer.domain.GhostTestFixtures.history
import com.pleos.explorer.domain.GhostTestFixtures.location
import com.pleos.explorer.domain.engine.GhostDetector
import com.pleos.explorer.domain.engine.LocationValidator
import com.pleos.explorer.domain.engine.TrackRecorder
import com.pleos.explorer.domain.model.ExploredRoad
import com.pleos.explorer.domain.model.GhostComparisonInput
import com.pleos.explorer.domain.model.GhostComparisonSnapshot
import com.pleos.explorer.domain.model.GhostComparisonUpdate
import com.pleos.explorer.domain.model.GhostInput
import com.pleos.explorer.domain.model.GhostResult
import com.pleos.explorer.domain.model.GhostSnapshot
import com.pleos.explorer.domain.model.GhostUpdate
import com.pleos.explorer.domain.model.HistoricalTrack
import com.pleos.explorer.domain.model.LocationSample
import com.pleos.explorer.domain.model.LocationValidation
import com.pleos.explorer.domain.model.TrackUpdate
import com.pleos.explorer.domain.port.GhostComparisonStore
import com.pleos.explorer.domain.port.HistoryRepository
import com.pleos.explorer.domain.model.GhostCursor
import com.pleos.explorer.domain.port.IdGenerator
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessGhostComparisonUseCaseTest {
    private class Fixture : GhostComparisonStore, HistoryRepository {
        var state: GhostComparisonSnapshot? = null
        val results = linkedMapOf<String, GhostResult>()
        var history = listOf(history())
        var failRead = false
        var failLoad = false
        var failCommit = false
        var cancelCommit = false
        var loseAck = false
        var queries = 0
        var id = 0
        val recorder = TrackRecorder(IdGenerator { "point-${++id}" })
        var recording = recorder.start("current", NOW_MS, "session").snapshot
        val detector = GhostDetector(IdGenerator { "ghost-${++id}" })
        var ghost = GhostSnapshot("current", "session")
        var sequence = 0L
        var lastInput: GhostComparisonInput? = null
        val useCase = ProcessGhostComparisonUseCase(this, this)

        suspend fun begin() = useCase.start("current", "session")
        suspend fun move(northM: Double, bearing: Double? = 0.0): GhostComparisonUpdate {
            val sample = location(northM, sequence * 1_000, bearing = bearing)
            val validation = LocationValidator().validate(LocationSample(sample.timeMs, sample.elapsedMs,
                sample.point, null, bearing, 5.0), recording.previous)
            val known = history.flatMap { it.points }.associate { p -> p.gridKey to
                ExploredRoad(p.gridKey, "past", 1, 1, 1, 0.0) }
            val trackUpdate = recorder.process(recording, validation, sequence, known)
            val ghostInput = when (validation) {
                is LocationValidation.Accepted -> GhostInput.Location(validation.location,
                    trackUpdate.connectedDistanceM, trackUpdate.newSegment)
                is LocationValidation.Rejected -> GhostInput.ContinuityLost
            }
            val ghostUpdate = detector.process(ghost, ghostInput, sequence, history)
            return process(GhostComparisonInput(trackUpdate, ghostUpdate)).also {
                recording = trackUpdate.snapshot
                ghost = ghostUpdate.snapshot
                sequence++
            }
        }
        suspend fun process(input: GhostComparisonInput): GhostComparisonUpdate {
            lastInput = input
            return useCase.process("current", "session", input.ghost.snapshot.lastInputSequence, input)
        }
        suspend fun finish(): GhostComparisonUpdate {
            val trackUpdate = recorder.finish(recording, NOW_MS + sequence * 1_000, emptyList())
            val ghostUpdate = detector.process(ghost, GhostInput.TripEnded, sequence)
            return process(GhostComparisonInput(trackUpdate, ghostUpdate))
        }
        override suspend fun load(tripId: String): GhostComparisonSnapshot? {
            if (failLoad) throw IOException("snapshot failed")
            return state
        }
        override suspend fun findNearby(gridKeys: Set<String>, excludedTripId: String): List<HistoricalTrack> {
            queries++
            if (failRead) throw IOException("history failed")
            return history
        }
        override suspend fun findForward(cursor: GhostCursor, maxDistanceM: Double): HistoricalTrack =
            error("Comparison must use one batch spatial query")
        override suspend fun commit(expectedRevision: Long?, update: GhostComparisonUpdate) {
            if (failCommit) { failCommit = false; throw IOException("commit failed") }
            if (cancelCommit) { cancelCommit = false; throw CancellationException("cancelled") }
            check(expectedRevision == state?.revision)
            state = update.snapshot
            update.results.forEach { results[it.id] = it }
            if (loseAck) { loseAck = false; throw IOException("ack lost") }
        }
    }

    @Test
    fun `actual F03 and F12 produce one finalized comparison at trip end`() = runBlocking {
        val f = Fixture()
        f.begin()
        (0..24).forEach { f.move(it * 25.0) }
        assertTrue(f.results.isEmpty())
        f.finish()
        val result = f.results.values.single()
        assertEquals(600.0, result.matchedDistanceM, 0.001)
        assertEquals(0.0, result.newRoadM, 0.0)
        assertTrue(result.finalized)
        assertTrue(requireNotNull(f.state).finished)
        assertEquals(1, f.queries)
    }

    @Test
    fun `exit detection includes new road since last match and same id grows until trip end`() = runBlocking {
        val f = Fixture()
        f.history = listOf(history(count = 25))
        f.begin()
        (0..24).forEach { f.move(it * 25.0) }
        (25..34).forEach { f.move(it * 25.0, null) }
        val initial = f.results.values.single()
        assertFalse(initial.finalized)
        assertTrue(initial.newRoadM > 0)
        val atExit = initial.newRoadM
        (35..38).forEach { f.move(it * 25.0, null) }
        val result = f.results.values.single()
        assertEquals(initial.id, result.id)
        assertTrue(result.newRoadM > atExit)
        f.finish()
        assertEquals(1, f.results.size)
        assertTrue(f.results.values.single().finalized)
    }

    @Test
    fun `next ghost cuts discovery at its first match rather than 500 meter activation`() = runBlocking {
        val f = Fixture()
        val second = history("second", 2_000_000).let { it.copy(points = it.points.filter { p -> p.sequence >= 44 }) }
        f.history = listOf(history(count = 25), second)
        f.begin()
        (0..80).forEach { f.move(it * 25.0) }
        val old = f.results.values.single()
        assertTrue(old.finalized)
        assertTrue(old.newRoadM > 0)
        val finalDistance = old.newRoadM
        (81..84).forEach { f.move(it * 25.0) }
        assertEquals(finalDistance, f.results.getValue(old.id).newRoadM, 0.0)
        f.finish()
        assertEquals(2, f.results.size)
    }

    @Test
    fun `duplicate finish cannot create another result or query`() = runBlocking {
        val f = Fixture()
        f.begin()
        (0..24).forEach { f.move(it * 25.0) }
        f.finish()
        val before = f.state
        f.process(requireNotNull(f.lastInput))
        assertEquals(before, f.state)
        assertEquals(1, f.results.size)
        assertEquals(1, f.queries)
    }

    @Test
    fun `lookup commit and cancellation failures preserve state and acknowledgement loss is idempotent`() = runBlocking {
        val f = Fixture()
        f.begin()
        (0..24).forEach { f.move(it * 25.0) }
        val before = f.state
        f.failRead = true
        assertThrows(IOException::class.java) { runBlocking { f.finish() } }
        assertEquals(before, f.state)
        f.failRead = false
        val input = requireNotNull(f.lastInput)
        f.failCommit = true
        assertThrows(IOException::class.java) { runBlocking { f.process(input) } }
        assertEquals(before, f.state)
        f.cancelCommit = true
        assertThrows(CancellationException::class.java) { runBlocking { f.process(input) } }
        assertEquals(before, f.state)
        assertTrue(f.results.isEmpty())
        f.loseAck = true
        assertThrows(IOException::class.java) { runBlocking { f.process(input) } }
        val committed = f.state
        f.process(input)
        assertEquals(committed, f.state)
        assertEquals(1, f.results.size)
    }

    @Test
    fun `resume closes prior active ghost and preserves discovery without reusing old clock`() = runBlocking {
        val f = Fixture()
        f.begin()
        (0..24).forEach { f.move(it * 25.0) }
        val recording = f.recorder.resume(f.recording, "new")
        val ghost = f.detector.resume(f.ghost, "new")
        val update = f.useCase.resume("current", "new", GhostComparisonInput(recording, ghost))
        assertEquals("new", update.snapshot.sessionId)
        assertEquals(1, f.results.size)
        assertNull(update.snapshot.active)
        assertNotNull(update.snapshot.pending)
        f.useCase.resume("current", "new", GhostComparisonInput(recording, ghost))
        assertEquals(1, f.results.size)
    }

    @Test
    fun `invalid lifecycle or incoherent engine outputs fail before commit`() = runBlocking {
        val f = Fixture()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { f.useCase.start("", "session") } }
        f.begin()
        f.move(0.0)
        val before = f.state
        val input = requireNotNull(f.lastInput)
        val wrong = input.copy(ghost = GhostUpdate(input.ghost.snapshot.copy(tripId = "other", lastInputSequence = 1)))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { f.useCase.process("current", "session", 1, wrong) }
        }
        assertEquals(before, f.state)
    }
}
