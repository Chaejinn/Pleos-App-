package com.pleos.explorer.domain.usecase

import com.pleos.explorer.domain.RegionTestFixtures.location
import com.pleos.explorer.domain.RegionTestFixtures.point
import com.pleos.explorer.domain.RegionTestFixtures.region
import com.pleos.explorer.domain.engine.LocationValidator
import com.pleos.explorer.domain.engine.RegionVisitDetector
import com.pleos.explorer.domain.engine.TrackRecorder
import com.pleos.explorer.domain.geo.IndexedRegionCatalog
import com.pleos.explorer.domain.model.BadgeType
import com.pleos.explorer.domain.model.GeoBounds
import com.pleos.explorer.domain.model.GeoPoint
import com.pleos.explorer.domain.model.LocationSample
import com.pleos.explorer.domain.model.LocationValidation
import com.pleos.explorer.domain.model.PendingReward
import com.pleos.explorer.domain.model.RegionBadge
import com.pleos.explorer.domain.model.RegionDefinition
import com.pleos.explorer.domain.model.RegionInput
import com.pleos.explorer.domain.model.RegionVisitSnapshot
import com.pleos.explorer.domain.model.RegionVisitUpdate
import com.pleos.explorer.domain.model.TripRegionVisit
import com.pleos.explorer.domain.port.IdGenerator
import com.pleos.explorer.domain.port.RegionCatalog
import com.pleos.explorer.domain.port.RegionVisitStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ProcessRegionVisitUseCaseTest {
    private fun fixture(regions: List<RegionDefinition> = listOf(region("A"))): Pair<MemoryStore, ProcessRegionVisitUseCase> {
        return fixture(IndexedRegionCatalog(regions))
    }

    private fun fixture(catalog: RegionCatalog): Pair<MemoryStore, ProcessRegionVisitUseCase> {
        val store = MemoryStore()
        var id = 0
        return store to ProcessRegionVisitUseCase(store, RegionVisitDetector(catalog),
            IdGenerator { "id-${++id}" })
    }

    private suspend fun ProcessRegionVisitUseCase.begin(tripId: String? = "trip") = start("session", tripId)

    private suspend fun ProcessRegionVisitUseCase.move(sequence: Long, northM: Double, elapsedMs: Long,
        distanceM: Double = 0.0, eastM: Double = 0.0) =
        process("session", sequence, RegionInput.Location(location(northM, elapsedMs, eastM), distanceM))

    @Test
    fun `distance threshold grants one badge reward and trip visit per entry`() = runBlocking {
        val (store, useCase) = fixture()
        useCase.begin()
        useCase.move(0, 0.0, 0)
        (1..39).forEach { useCase.move(it.toLong(), it * 25.0, it * 1_000L, 25.0) }
        assertTrue(store.badges.isEmpty())
        val qualified = useCase.move(40, 1000.0, 40_000, 25.0)
        assertEquals("A", qualified.qualifiedVisits.single().regionCode)
        assertEquals(1, store.badges.getValue("REGION:A").visitCount)
        assertEquals(1, store.rewards.size)
        assertEquals(1, store.visits.size)
        useCase.move(41, 1025.0, 41_000, 25.0)
        assertEquals(1, store.rewards.size)
        assertEquals(1, store.visits.size)
    }

    @Test
    fun `dwell threshold needs two fresh observations and is inclusive at 180 seconds`() = runBlocking {
        val (store, useCase) = fixture()
        useCase.begin()
        useCase.move(0, 0.0, 0)
        (1..35).forEach { useCase.move(it.toLong(), 0.0, it * 5_000L) }
        assertTrue(store.badges.isEmpty())
        useCase.move(36, 0.0, 180_000)
        assertEquals(1_180_000L, store.badges.getValue("REGION:A").acquiredAtMs)
    }

    @Test
    fun `ticks cannot manufacture dwell from stale last location`() = runBlocking {
        val (store, useCase) = fixture()
        useCase.begin()
        useCase.move(0, 0.0, 0)
        (1..40).forEach { useCase.process("session", it.toLong(), RegionInput.Tick(it * 5_000L, 1_000_000 + it * 5_000L)) }
        assertTrue(store.badges.isEmpty())
        useCase.move(41, 0.0, 205_000)
        assertEquals(0L, requireNotNull(store.snapshot?.candidate).dwellMs)
    }

    @Test
    fun `crossed line distance belongs to each inside region rather than its endpoint`() = runBlocking {
        val (store, useCase) = fixture(listOf(region("A", -100.0, -5000.0, 100.0, 0.0),
            region("B", -100.0, 0.0, 100.0, 5000.0)))
        useCase.begin()
        useCase.move(0, -25.0, 0)
        useCase.move(1, 25.0, 1_000, 50.0)
        val candidate = requireNotNull(store.snapshot?.candidate)
        assertEquals("B", candidate.regionCode)
        assertEquals(25.0, candidate.distanceM, 0.0001)
        assertEquals(0L, candidate.dwellMs)
    }

    @Test
    fun `exit and reentry discard unqualified progress and allow later revisit`() = runBlocking {
        val (store, useCase) = fixture(listOf(region("A", -100.0, -100.0, 100.0, 100.0)))
        useCase.begin()
        useCase.move(0, 0.0, 0)
        (1..20).forEach { useCase.move(it.toLong(), 0.0, it * 5_000L) }
        useCase.move(21, 200.0, 105_000, 200.0)
        assertNull(store.snapshot?.candidate)
        useCase.move(22, 0.0, 110_000, 200.0)
        (23..57).forEach { useCase.move(it.toLong(), 0.0, 110_000 + (it - 22) * 5_000L) }
        assertTrue(store.badges.isEmpty())
        useCase.move(58, 0.0, 290_000)
        useCase.move(59, 200.0, 295_000, 200.0)
        useCase.move(60, 0.0, 300_000, 200.0)
        (61..96).forEach { useCase.move(it.toLong(), 0.0, 300_000 + (it - 60) * 5_000L) }
        assertEquals(2, store.badges.getValue("REGION:A").visitCount)
        assertEquals(1, store.rewards.size)
        assertEquals(2, store.visits.size)
    }

    @Test
    fun `continuity loss resets unqualified distance and dwell`() = runBlocking {
        val (store, useCase) = fixture()
        useCase.begin()
        useCase.move(0, 0.0, 0)
        (1..20).forEach { useCase.move(it.toLong(), it * 25.0, it * 5_000L, 25.0) }
        useCase.process("session", 21, RegionInput.ContinuityLost)
        useCase.move(22, 1000.0, 105_000)
        val candidate = requireNotNull(store.snapshot?.candidate)
        assertEquals(0.0, candidate.distanceM, 0.0)
        assertEquals(0L, candidate.dwellMs)
        assertTrue(store.badges.isEmpty())
    }

    @Test
    fun `exact ten second gap and explicit new segment never accrue distance or dwell`() = runBlocking {
        val (store, useCase) = fixture()
        useCase.begin()
        useCase.move(0, 0.0, 0)
        useCase.move(1, 25.0, 9_999, 25.0)
        assertEquals(9_999L, requireNotNull(store.snapshot?.candidate).dwellMs)
        useCase.move(2, 2000.0, 19_999)
        assertEquals(0.0, requireNotNull(store.snapshot?.candidate).distanceM, 0.0)
        useCase.process("session", 3, RegionInput.Location(location(2500.0, 20_999), 0.0, newSegment = true))
        assertEquals(0L, requireNotNull(store.snapshot?.candidate).dwellMs)
    }

    @Test
    fun `duplicate sequence and out of order observations cannot add progress`() = runBlocking {
        val (store, useCase) = fixture()
        useCase.begin()
        useCase.move(0, 0.0, 0)
        useCase.move(1, 25.0, 1_000, 25.0)
        val before = store.snapshot
        useCase.move(1, 25.0, 1_000, 25.0)
        assertEquals(before, store.snapshot)
        useCase.move(2, 50.0, 999, 25.0)
        assertEquals(25.0, requireNotNull(store.snapshot?.candidate).distanceM, 0.0)
    }

    @Test
    fun `qualified entry is not granted again after trip change gap or restart`() = runBlocking {
        val (store, useCase) = fixture()
        useCase.begin()
        useCase.move(0, 0.0, 0)
        (1..36).forEach { useCase.move(it.toLong(), 0.0, it * 5_000L) }
        useCase.process("session", 37, RegionInput.TripEnded)
        useCase.process("session", 38, RegionInput.TripStarted("trip-2"))
        useCase.resume("session-2")
        useCase.resume("session-2")
        (0..40).forEach { useCase.process("session-2", it.toLong(), RegionInput.Location(location(0.0, it * 5_000L), 0.0)) }
        assertEquals(1, store.badges.getValue("REGION:A").visitCount)
        assertEquals(1, store.rewards.size)
        assertEquals(1, store.visits.size)
    }

    @Test
    fun `trip ended retains dwell candidate and next trip attaches future qualification`() = runBlocking {
        val (store, useCase) = fixture()
        useCase.begin()
        useCase.move(0, 0.0, 0)
        (1..20).forEach { useCase.move(it.toLong(), 0.0, it * 5_000L) }
        useCase.process("session", 21, RegionInput.TripEnded)
        useCase.process("session", 22, RegionInput.TripStarted("trip-2"))
        (23..38).forEach { useCase.move(it.toLong(), 0.0, 100_000 + (it - 22) * 5_000L) }
        assertEquals("trip-2", store.visits.values.single().tripId)
    }

    @Test
    fun `visit without a trip creates badge but no dangling trip visit`() = runBlocking {
        val (store, useCase) = fixture()
        useCase.begin(null)
        (0..36).forEach { useCase.move(it.toLong(), 0.0, it * 5_000L) }
        assertNull(store.badges.getValue("REGION:A").firstTripId)
        assertNull(store.rewards.values.single().tripId)
        assertTrue(store.visits.isEmpty())
    }

    @Test
    fun `save failure leaves snapshot unchanged and lost acknowledgement does not duplicate rewards`() = runBlocking {
        val (store, useCase) = fixture()
        useCase.begin()
        (0..35).forEach { useCase.move(it.toLong(), 0.0, it * 5_000L) }
        val before = store.snapshot
        store.failCommit = true
        assertThrows(IOException::class.java) { runBlocking { useCase.move(36, 0.0, 180_000) } }
        assertEquals(before, store.snapshot)
        assertTrue(store.badges.isEmpty())
        store.loseAcknowledgement = true
        assertThrows(IOException::class.java) { runBlocking { useCase.move(36, 0.0, 180_000) } }
        useCase.move(36, 0.0, 180_000)
        assertEquals(1, store.badges.getValue("REGION:A").visitCount)
        assertEquals(1, store.rewards.size)
    }

    @Test
    fun `read failures and cancellation propagate without advancing snapshot`() = runBlocking {
        val (store, useCase) = fixture()
        useCase.begin()
        (0..35).forEach { useCase.move(it.toLong(), 0.0, it * 5_000L) }
        val before = store.snapshot
        store.failBadges = true
        assertThrows(IOException::class.java) { runBlocking { useCase.move(36, 0.0, 180_000) } }
        assertEquals(before, store.snapshot)
        store.failBadges = false
        store.cancelCommit = true
        assertThrows(CancellationException::class.java) { runBlocking { useCase.move(36, 0.0, 180_000) } }
        assertEquals(before, store.snapshot)
    }

    @Test
    fun `previous badge retains acquisition data and receives no new reward`() = runBlocking {
        val (store, useCase) = fixture()
        val old = RegionBadge("REGION:A", "A", BadgeType.REGION, "past-trip", 10L, 2)
        store.badges[old.badgeId] = old
        useCase.begin()
        (0..36).forEach { useCase.move(it.toLong(), 0.0, it * 5_000L) }
        assertEquals(old.copy(visitCount = 3), store.badges.getValue("REGION:A"))
        assertTrue(store.rewards.isEmpty())
    }

    @Test
    fun `invalid lifecycle and numeric inputs fail without persistence`() = runBlocking {
        val (store, useCase) = fixture()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { useCase.start("", "trip") } }
        useCase.begin()
        val before = store.snapshot
        useCase.begin()
        assertEquals(before, store.snapshot)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { useCase.process("wrong", 0, RegionInput.ContinuityLost) }
        }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { useCase.move(-1, 0.0, 0) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { useCase.move(0, 0.0, 0, Double.NaN) } }
        assertFalse(requireNotNull(store.snapshot).sessionId.isBlank())
    }

    @Test
    fun `tick cannot discard delayed F03 approved locations or disconnect the shared stream`() = runBlocking {
        listOf(1_500L, 10_000L).forEach { tickAt ->
            val (store, useCase) = fixture()
            useCase.begin()
            var id = 0
            val recorder = TrackRecorder(IdGenerator { "track-${++id}" })
            var track = recorder.start("trip", 1_000_000, "session").snapshot
            suspend fun approved(sequence: Long, northM: Double, elapsedMs: Long) {
                val validation = LocationValidator().validate(LocationSample(1_000_000 + elapsedMs, elapsedMs,
                    point(0.0, northM), null, null, 5.0), track.previous) as LocationValidation.Accepted
                val update = recorder.process(track, validation, sequence, emptyMap())
                useCase.process("session", sequence, RegionInput.Location(validation.location,
                    update.connectedDistanceM, update.newSegment))
                track = update.snapshot
            }
            approved(0, 0.0, 0)
            useCase.process("session", 1, RegionInput.Tick(tickAt, 1_000_000 + tickAt))
            approved(2, 25.0, 1_000)
            assertEquals(25.0, requireNotNull(store.snapshot?.candidate).distanceM, 0.001)
            approved(3, 50.0, 2_000)
            assertEquals(track.trip.distanceM, requireNotNull(store.snapshot?.candidate).distanceM, 0.001)
        }
    }

    @Test
    fun `restart resets unqualified dwell while preserving entry identity`() = runBlocking {
        val (store, useCase) = fixture()
        useCase.begin()
        (0..20).forEach { useCase.move(it.toLong(), 0.0, it * 5_000L) }
        val entry = requireNotNull(store.snapshot?.candidate).entrySequence
        useCase.resume("new-session")
        (0..35).forEach { useCase.process("new-session", it.toLong(), RegionInput.Location(location(0.0, it * 5_000L), 0.0)) }
        assertTrue(store.badges.isEmpty())
        useCase.process("new-session", 36, RegionInput.Location(location(0.0, 180_000), 0.0))
        assertEquals(entry, store.visits.values.single().entrySequence)
    }

    @Test
    fun `startup and resume acknowledgement loss are safe to retry`() = runBlocking {
        val (store, useCase) = fixture()
        store.loseAcknowledgement = true
        assertThrows(IOException::class.java) { runBlocking { useCase.begin() } }
        useCase.begin()
        useCase.move(0, 0.0, 0)
        store.loseAcknowledgement = true
        assertThrows(IOException::class.java) { runBlocking { useCase.resume("new-session") } }
        val resumed = store.snapshot
        useCase.resume("new-session")
        assertEquals(resumed, store.snapshot)
        assertTrue(store.badges.isEmpty())
    }

    @Test
    fun `snapshot and catalog read failures propagate without advancing cursor`() = runBlocking {
        val failingCatalog = object : RegionCatalog {
            override fun findCandidates(point: GeoPoint): List<RegionDefinition> = throw IOException("catalog failed")
            override fun findCandidates(bounds: GeoBounds): List<RegionDefinition> = throw IOException("catalog failed")
        }
        val (store, useCase) = fixture(failingCatalog)
        useCase.begin()
        val before = store.snapshot
        assertThrows(IOException::class.java) { runBlocking { useCase.move(0, 0.0, 0) } }
        assertEquals(before, store.snapshot)
        store.failLoad = true
        assertThrows(IOException::class.java) { runBlocking { useCase.resume("new-session") } }
        assertEquals(before, store.snapshot)
    }

    @Test
    fun `detaching deleted trip preserves dwell and clears only matching trip reference`() = runBlocking {
        val (store, useCase) = fixture()
        useCase.begin()
        (0..20).forEach { useCase.move(it.toLong(), 0.0, it * 5_000L) }
        val candidate = store.snapshot?.candidate
        useCase.process("session", 21, RegionInput.TripDetached("other-trip"))
        assertEquals("trip", store.snapshot?.tripId)
        useCase.process("session", 22, RegionInput.TripDetached("trip"))
        assertNull(store.snapshot?.tripId)
        assertEquals(candidate, store.snapshot?.candidate)
        (23..38).forEach { useCase.move(it.toLong(), 0.0, 100_000 + (it - 22) * 5_000L) }
        assertNull(store.badges.getValue("REGION:A").firstTripId)
        assertNull(store.rewards.values.single().tripId)
        assertTrue(store.visits.isEmpty())
    }

    private class MemoryStore : RegionVisitStore {
        var snapshot: RegionVisitSnapshot? = null
        val badges = mutableMapOf<String, RegionBadge>()
        val visits = mutableMapOf<Triple<String, String, Long>, TripRegionVisit>()
        val rewards = mutableMapOf<String, PendingReward>()
        var failCommit = false
        var failBadges = false
        var failLoad = false
        var cancelCommit = false
        var loseAcknowledgement = false

        override suspend fun load(): RegionVisitSnapshot? {
            if (failLoad) throw IOException("snapshot read failed")
            return snapshot
        }

        override suspend fun findBadges(regionCodes: Set<String>): Map<String, RegionBadge> {
            if (failBadges) throw IOException("read failed")
            return badges.filterValues { it.regionCode in regionCodes }
        }

        override suspend fun commit(expectedRevision: Long?, update: RegionVisitUpdate) {
            if (failCommit) { failCommit = false; throw IOException("save failed") }
            if (cancelCommit) { cancelCommit = false; throw CancellationException("cancelled") }
            check(expectedRevision == snapshot?.revision)
            badges.putAll(update.badges)
            update.visits.forEach { visits[Triple(it.tripId, it.regionCode, it.entrySequence)] = it }
            update.rewards.forEach { rewards[it.deduplicationKey] = it }
            snapshot = update.snapshot
            if (loseAcknowledgement) { loseAcknowledgement = false; throw IOException("ack lost") }
        }
    }
}
