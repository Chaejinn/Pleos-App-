package com.pleos.explorer.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TripFinishedFactTest {
    private fun finished(roads: Map<String, ExploredRoad> = emptyMap()) = TrackUpdate(RecordingSnapshot(
        Trip("trip", 1_000, endedAtMs = 2_000, distanceM = 1_000.0), "session", status = TripStatus.FINISHED), roads = roads)
    private fun road(key: String, tripId: String?, distance: Double) =
        ExploredRoad(key, tripId, 1_000, 2_000, 1, distance)

    @Test
    fun `only current trip first exploration rows form the source delta`() {
        val update = finished(mapOf("new" to road("new", "trip", 400.0),
            "known" to road("known", "older", 70_000.0), "detached" to road("detached", null, 20_000.0)))
        val fact = requireNotNull(AchievementFact.TripFinished.from(update))
        assertEquals("trip", fact.tripId)
        assertEquals(2_000L, fact.atMs)
        assertEquals(400.0, fact.newRoadM, 0.0)
    }

    @Test
    fun `discarded and active recording cannot contribute finished facts`() {
        val update = finished()
        assertNull(AchievementFact.TripFinished.from(update.copy(snapshot = update.snapshot.copy(status = TripStatus.DISCARDED))))
        assertThrows(IllegalArgumentException::class.java) {
            AchievementFact.TripFinished.from(update.copy(snapshot = update.snapshot.copy(status = TripStatus.ACTIVE)))
        }
        assertEquals(0.0, requireNotNull(AchievementFact.TripFinished.from(update)).newRoadM, 0.0)
    }

    @Test
    fun `missing completion timestamp and malformed exploration values fail explicitly`() {
        val update = finished()
        val invalid = listOf(update.copy(snapshot = update.snapshot.copy(trip = update.snapshot.trip.copy(endedAtMs = null))),
            update.copy(snapshot = update.snapshot.copy(trip = update.snapshot.trip.copy(distanceM = 199.0))),
            update.copy(roads = mapOf("new" to road("different", "trip", 10.0))),
            update.copy(roads = mapOf("new" to road("new", "trip", Double.NaN))),
            update.copy(roads = mapOf("new" to road("new", "trip", -1.0))),
            update.copy(roads = mapOf("new" to road("new", "trip", 1_001.0))))
        invalid.forEach { input -> assertThrows(IllegalArgumentException::class.java) {
            AchievementFact.TripFinished.from(input)
        } }
    }

    @Test
    fun `large finite per road values cannot overflow into a qualifying delta`() {
        val update = finished(mapOf("a" to road("a", "trip", Double.MAX_VALUE),
            "b" to road("b", "trip", Double.MAX_VALUE)))
        assertThrows(IllegalArgumentException::class.java) { AchievementFact.TripFinished.from(update) }
    }
}
