package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.RegionTestFixtures
import com.pleos.explorer.domain.geo.GeoDistance
import com.pleos.explorer.domain.geo.GridProjector
import com.pleos.explorer.domain.model.GhostEvent
import com.pleos.explorer.domain.model.GhostInput
import com.pleos.explorer.domain.model.GhostResult
import com.pleos.explorer.domain.model.GhostSnapshot
import com.pleos.explorer.domain.model.HistoricalTrack
import com.pleos.explorer.domain.model.LocationSample
import com.pleos.explorer.domain.model.LocationValidation
import com.pleos.explorer.domain.model.TrackPoint
import com.pleos.explorer.domain.port.IdGenerator
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class GhostPassageRegressionTest {
    private val epochMs = 1_700_000_000_000L
    private val thirtyDaysMs = 30L * 24 * 60 * 60 * 1_000

    private fun history(id: String, north: List<Double>, timeStepMs: Long = 1_000,
        shift: Double = 0.0, diagonal: Boolean = false): HistoricalTrack = HistoricalTrack(id,
        north.mapIndexed { index, y ->
            val point = RegionTestFixtures.point(if (diagonal) y else 0.0, y + shift)
            TrackPoint("$id-$index", id, epochMs + index * timeStepMs, point, null,
                if (diagonal) 45.0 else 0.0, 5.0, GridProjector().key(point), "segment", index.toLong())
        })

    private fun compare(old: HistoricalTrack, history: List<HistoricalTrack>, count: Int, stepM: Double,
        stepMs: Long, ageMs: Long): GhostResult {
        val detector = GhostDetector(IdGenerator { "ghost" })
        var state = GhostSnapshot("current", "session")
        var previous: com.pleos.explorer.domain.model.ValidatedLocation? = null
        (0..count).forEach { index ->
            val validation = LocationValidator().validate(LocationSample(epochMs + ageMs + index * stepMs,
                index * stepMs, RegionTestFixtures.point(0.0, index * stepM), null, 0.0, 5.0), previous)
                as LocationValidation.Accepted
            val location = validation.location
            val distance = previous?.let { GeoDistance.meters(it.point, location.point) } ?: 0.0
            state = detector.process(state, GhostInput.Location(location, distance, index == 0), index.toLong(),
                listOf(old)).snapshot
            previous = location
        }
        val segment = detector.process(state, GhostInput.TripEnded, count + 1L).events
            .filterIsInstance<GhostEvent.Finished>().single().segment
        return GhostResultBuilder().build(segment, history)
    }

    @Test
    fun `same speed revisit at exactly thirty days retains all six kilometers as eligible`() {
        val old = history("old", (0..250).map { it * 25.0 })
        val result = compare(old, listOf(old), 240, 25.0, 1_000, thirtyDaysMs)
        val eligibleM = result.passages.filter { it.currentPassedAtMs - it.previousLastPassedAtMs >= thirtyDaysMs }
            .sumOf { it.distanceM }
        assertEquals(6_000.0, eligibleM, 0.001)
    }

    @Test
    fun `recent crossing visit retains its complete overlapping distance at every shifted boundary`() {
        val old = history("old", (0..8).map { it * 100.0 }, 5_000)
        listOf(-20.0, 0.0, 20.0, 40.0, 60.0, 80.0).forEach { shift ->
            val recent = history("recent", listOf(-90.0, 90.0), 5_000, shift, true).let { track ->
                track.copy(points = track.points.map { it.copy(timeMs = it.timeMs + 1_000_000) })
            }
            val result = compare(old, listOf(old, recent), 6, 100.0, 4_000, thirtyDaysMs)
            val actual = result.passages.filter { it.sectionIndex == 0 && it.previousLastPassedAtMs >= epochMs + 1_000_000 }
                .sumOf { it.distanceM }
            val expected = minOf(100.0, shift + 30 * sqrt(2.0)) - maxOf(0.0, shift - 30 * sqrt(2.0))
            assertEquals("shift=$shift", expected, actual, 0.001)
        }
    }
}
