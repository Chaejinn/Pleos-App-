package com.pleos.explorer.domain.usecase

import com.pleos.explorer.domain.engine.TitleEvaluator
import com.pleos.explorer.domain.engine.CoastVisitDetector
import com.pleos.explorer.domain.engine.LocationValidator
import com.pleos.explorer.domain.engine.TrackRecorder
import com.pleos.explorer.domain.GhostTestFixtures.point
import com.pleos.explorer.domain.model.AchievementFact
import com.pleos.explorer.domain.model.AchievementProgress
import com.pleos.explorer.domain.model.Coast
import com.pleos.explorer.domain.model.CoastVisit
import com.pleos.explorer.domain.model.CoastPoint
import com.pleos.explorer.domain.model.LocationSample
import com.pleos.explorer.domain.model.LocationValidation
import com.pleos.explorer.domain.model.PendingReward
import com.pleos.explorer.domain.model.TitleSnapshot
import com.pleos.explorer.domain.model.TitleUpdate
import com.pleos.explorer.domain.port.IdGenerator
import com.pleos.explorer.domain.port.TitleStore
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoastTitleTest {
    private val morning = Instant.parse("2026-11-15T00:00:00Z").toEpochMilli()
    private fun visit(coast: Coast, at: Long = morning, tripId: String? = "trip") =
        AchievementFact.CoastEntered(CoastVisit(coast.name, coast, at, tripId))

    private class Store : TitleStore {
        var state = TitleSnapshot()
        var updates = mutableListOf<TitleUpdate>()
        var fail = false
        var cancel = false
        var loseAck = false
        var conflict = false
        override suspend fun load() = state
        override suspend fun commit(expectedRevision: Long, update: TitleUpdate) {
            if (fail) { fail = false; throw IOException("write") }
            if (cancel) { cancel = false; throw CancellationException("cancel") }
            if (conflict) { conflict = false; state = state.copy(revision = state.revision + 1) }
            check(state.revision == expectedRevision)
            state = update.snapshot
            updates.add(update)
            if (loseAck) { loseAck = false; throw IOException("ack") }
        }
    }

    private fun useCase(store: Store): ProcessTitleFactUseCase =
        ProcessTitleFactUseCase(store, IdGenerator { "reward" })

    @Test
    fun `first coast persists without reward and opposite coast grants once across restart and trips`() = runBlocking {
        val store = Store()
        val first = useCase(store).process(visit(Coast.EAST, tripId = "east-trip"))
        assertTrue(first.titles.isEmpty() && first.rewards.isEmpty())
        assertEquals(1, store.updates.size)
        assertEquals(1L, store.state.revision)
        assertTrue(useCase(store).process(visit(Coast.EAST)).titles.isEmpty())
        assertEquals(1, store.updates.size)
        val completed = useCase(store).process(visit(Coast.WEST, morning + 10_000, "west-trip"))
        assertEquals("COAST_TO_COAST", completed.titles.single().titleId)
        assertEquals("west-trip", completed.titles.single().tripId)
        assertEquals(morning + 10_000, completed.titles.single().acquiredAtMs)
        assertEquals("TITLE:COAST_TO_COAST", completed.rewards.single().deduplicationKey)
        assertEquals(completed.titles.single().acquiredAtMs, completed.rewards.single().occurredAtMs)
        assertTrue(useCase(store).process(visit(Coast.WEST, morning + 20_000)).rewards.isEmpty())
        assertEquals(2, store.updates.size)
    }

    @Test
    fun `both visiting orders qualify and same coast points never substitute opposite coast`() = runBlocking {
        for (coast in Coast.entries) {
            val store = Store()
            useCase(store).process(visit(coast))
            val repeat = visit(coast, morning + 1_000).let { it.copy(visit = it.visit.copy(coastId = "other")) }
            assertTrue(useCase(store).process(repeat).titles.isEmpty())
            val opposite = Coast.entries.single { it != coast }
            assertEquals(1, useCase(store).process(visit(opposite, morning + 2_000)).titles.size)
        }
    }

    @Test
    fun `Seoul midnight is exclusive and UTC date is not the grouping date`() = runBlocking {
        val midnight = Instant.parse("2026-11-15T15:00:00Z").toEpochMilli()
        val store = Store()
        useCase(store).process(visit(Coast.EAST, midnight - 1))
        assertTrue(useCase(store).process(visit(Coast.WEST, midnight)).titles.isEmpty())
        assertEquals(1, useCase(store).process(visit(Coast.EAST, midnight + 1)).titles.size)
        val sameSeoulDay = Store()
        useCase(sameSeoulDay).process(visit(Coast.EAST, Instant.parse("2026-11-14T16:00:00Z").toEpochMilli()))
        assertEquals(1, useCase(sameSeoulDay).process(visit(Coast.WEST, morning)).titles.size)
    }

    @Test
    fun `out of order facts retain earliest coast evidence and actual completion timestamp and trip`() = runBlocking {
        val store = Store()
        useCase(store).process(visit(Coast.EAST, morning + 20_000, "later"))
        useCase(store).process(visit(Coast.EAST, morning + 10_000, "earlier"))
        val result = useCase(store).process(visit(Coast.WEST, morning, "west"))
        assertEquals(morning + 10_000, result.titles.single().acquiredAtMs)
        assertEquals("earlier", result.titles.single().tripId)
    }

    @Test
    fun `interleaved dates preserve correct same day evidence`() = runBlocking {
        val store = Store()
        useCase(store).process(visit(Coast.EAST, morning))
        useCase(store).process(visit(Coast.EAST, morning + 86_400_000))
        val result = useCase(store).process(visit(Coast.WEST, morning + 1_000))
        assertEquals(morning + 1_000, result.titles.single().acquiredAtMs)
    }

    @Test
    fun `write failures and lost responses retry without partial or duplicate reward`() = runBlocking {
        for (stage in listOf(Coast.EAST, Coast.WEST)) for (failure in listOf("write", "cancel", "ack", "conflict")) {
            val store = Store()
            if (stage == Coast.WEST) useCase(store).process(visit(Coast.EAST))
            val before = store.state
            when (failure) {
                "write" -> store.fail = true
                "cancel" -> store.cancel = true
                "ack" -> store.loseAck = true
                "conflict" -> store.conflict = true
            }
            val fact = visit(stage, morning + 1_000)
            val error = when (failure) {
                "cancel" -> CancellationException::class.java
                "conflict" -> IllegalStateException::class.java
                else -> IOException::class.java
            }
            assertThrows(error) { runBlocking { useCase(store).process(fact) } }
            if (failure in listOf("write", "cancel")) assertEquals(before, store.state)
            useCase(store).process(fact)
            if (stage == Coast.EAST) useCase(store).process(visit(Coast.WEST, morning + 2_000))
            val rewards: List<PendingReward> = store.updates.flatMap { it.rewards }
            assertEquals(1, rewards.size)
            assertEquals("TITLE:COAST_TO_COAST", rewards.single().deduplicationKey)
        }
    }

    @Test
    fun `already acquired title does not keep writing new coastal dates`() = runBlocking {
        val store = Store()
        store.state = TitleSnapshot(progress = AchievementProgress(acquiredTitleIds = setOf("COAST_TO_COAST")))
        assertTrue(useCase(store).process(visit(Coast.WEST)).titles.isEmpty())
        assertTrue(store.updates.isEmpty())
    }

    @Test
    fun `invalid evidence and inconsistent persisted dates fail before commit`() {
        assertThrows(IllegalArgumentException::class.java) { CoastVisit("", Coast.EAST, morning, null) }
        assertThrows(IllegalArgumentException::class.java) { CoastVisit("east", Coast.EAST, -1, null) }
        assertThrows(IllegalArgumentException::class.java) { CoastVisit("east", Coast.EAST, morning, "") }
        val evidence = visit(Coast.EAST).visit
        val wrongDate = LocalDate.parse(evidence.date).plusDays(1).toString()
        val wrong = AchievementProgress(coastVisitsByDate = mapOf(wrongDate to mapOf(Coast.WEST to evidence)))
        assertThrows(IllegalArgumentException::class.java) { TitleEvaluator().evaluate(visit(Coast.WEST), wrong) }
    }

    @Test
    fun `new earlier evidence cannot overwrite and hide corrupt stored coast identity`() = runBlocking {
        val store = Store()
        val incorrect = CoastVisit("east", Coast.EAST, morning + 2_000, "trip")
        store.state = TitleSnapshot(progress = AchievementProgress(coastVisitsByDate = mapOf(
            incorrect.date to mapOf(Coast.WEST to incorrect))))
        val before = store.state
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { useCase(store).process(visit(Coast.WEST, morning)) }
        }
        assertEquals(before, store.state)
        assertTrue(store.updates.isEmpty())
    }

    @Test
    fun `actual F03 approved locations feed detector and existing title transaction`() = runBlocking {
        val store = Store()
        val detector = CoastVisitDetector(listOf(CoastPoint("east", Coast.EAST, point(0.0)),
            CoastPoint("west", Coast.WEST, point(20_000.0))))
        for ((index, coast) in Coast.entries.withIndex()) {
            val recorder = TrackRecorder(IdGenerator { "point-$index" })
            val initial = recorder.start("trip-$index", morning + index * 10_000L, "session-$index")
            val source = LocationSample(morning + index * 10_000L, 0, point(index * 20_000.0), null, 0.0, 5.0)
            val validation = LocationValidator().validate(source, null)
            assertTrue(validation is LocationValidation.Accepted)
            val update = recorder.process(initial.snapshot, validation, 0, emptyMap())
            val approved = (requireNotNull(update.validation) as LocationValidation.Accepted).location
            val visit = detector.detect(approved, update.snapshot.trip.id).single()
            assertEquals(coast, visit.coast)
            useCase(store).process(AchievementFact.CoastEntered(visit))
        }
        assertEquals(2, store.updates.size)
        assertEquals("COAST_TO_COAST", store.updates.last().titles.single().titleId)
        assertEquals("trip-1", store.updates.last().titles.single().tripId)
    }

    @Test
    fun `released Trip reference and existing achievements remain independent`() = runBlocking {
        val store = Store()
        val recorded = visit(Coast.EAST, morning + 10_000).visit.copy(tripId = null)
        store.state = TitleSnapshot(progress = AchievementProgress(regionBadgeCount = 30,
            acquiredTitleIds = setOf("NEW_ROAD_DISCOVERER"), newRoadDistanceM = 100_000.0,
            coastVisitsByDate = mapOf(recorded.date to mapOf(Coast.EAST to recorded))))
        val update = useCase(store).process(visit(Coast.WEST, morning))
        assertEquals(null, update.titles.single().tripId)
        assertEquals(morning + 10_000, update.titles.single().acquiredAtMs)
        assertEquals(30, store.state.progress.regionBadgeCount)
        assertEquals(100_000.0, store.state.progress.newRoadDistanceM, 0.0)
        assertTrue("NEW_ROAD_DISCOVERER" in store.state.progress.acquiredTitleIds)
        assertEquals(1, useCase(store).process(AchievementFact.BadgeGranted("REGION:A", null, morning)).titles.size)
    }
}
