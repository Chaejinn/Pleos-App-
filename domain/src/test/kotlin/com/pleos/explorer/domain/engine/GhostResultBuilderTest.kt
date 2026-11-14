package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.GhostTestFixtures.history
import com.pleos.explorer.domain.GhostTestFixtures.location
import com.pleos.explorer.domain.model.GhostEvent
import com.pleos.explorer.domain.model.GhostInput
import com.pleos.explorer.domain.model.GhostSegment
import com.pleos.explorer.domain.model.GhostSnapshot
import com.pleos.explorer.domain.model.HistoricalTrack
import com.pleos.explorer.domain.port.IdGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostResultBuilderTest {
    private val builder = GhostResultBuilder()

    private fun segment(): GhostSegment {
        val detector = GhostDetector(IdGenerator { "ghost" })
        var state = GhostSnapshot("current", "session")
        (0..24).forEach { i ->
            state = detector.process(state, GhostInput.Location(location(i * 25.0, i * 1_000L),
                if (i == 0) 0.0 else 25.0, i == 0), i.toLong(), listOf(history())).snapshot
        }
        return detector.process(state, GhostInput.TripEnded, 25).events
            .filterIsInstance<GhostEvent.Finished>().single().segment
    }

    @Test
    fun `result keeps stable identity matched geometry and unknown metrics`() {
        val segment = segment()
        val result = builder.build(segment, listOf(history()))
        assertEquals(segment.id, result.id)
        assertEquals("current", result.tripId)
        assertEquals("past", result.pastTripId)
        assertEquals(segment.matchedDistanceM, result.matchedDistanceM, 0.0)
        assertEquals(0.0, result.newRoadM, 0.0)
        assertNull(result.metricsDiff)
        assertFalse(result.finalized)
        assertEquals(segment.sections.first().currentFrom.point, result.currentPath.first().points.first())
        assertEquals(segment.sections.last().currentTo.point, result.currentPath.last().points.last())
        assertEquals(history().points.first().point, result.pastPath.first().points.first())
        assertEquals(segment.sections.size, result.passages.size)
        assertEquals(segment.matchedDistanceM, result.passages.sumOf { it.distanceM }, 0.001)
    }

    @Test
    fun `passage times come from corresponding points rather than trip endpoints`() {
        val segment = segment()
        val old = history("old", 100_000)
        val recent = history("recent", 3_000_000)
        val result = builder.build(segment, listOf(history(), old, recent))
        assertEquals(100_000L, result.firstPassedAtMs)
        assertEquals(3_024_000L, result.previousLastPassedAtMs)
        assertEquals(100_000L, result.passages.first().firstPassedAtMs)
        assertEquals(3_001_000L, result.passages.first().previousLastPassedAtMs)
    }

    @Test
    fun `partial recent visit updates only corresponding sections`() {
        val recent = history("recent", 3_000_000).let { it.copy(points = it.points.take(9)) }
        val result = builder.build(segment(), listOf(history(), recent))
        assertTrue(result.passages.first().previousLastPassedAtMs >= 3_000_000)
        assertTrue(result.passages.last().previousLastPassedAtMs < 3_000_000)
    }

    @Test
    fun `current trip reverse direction and disconnected fragments do not change dates`() {
        val original = builder.build(segment(), listOf(history()))
        val gap = history("gap", 1).let { it.copy(points = it.points.filterIndexed { i, _ -> i % 2 == 0 }) }
        val isolated = history("isolated", 1).let { it.copy(points = it.points.map { p ->
            p.copy(pathSegmentId = "segment-${p.sequence}")
        }) }
        val result = builder.build(segment(), listOf(history(), history("current", 1),
            history("reverse", 1, bearing = 180.0), gap, isolated))
        assertEquals(original.firstPassedAtMs, result.firstPassedAtMs)
        assertEquals(original.previousLastPassedAtMs, result.previousLastPassedAtMs)
    }

    @Test
    fun `wall clock reversal preserves epoch evidence without changing geometry`() {
        val corrected = history("corrected", 100_000).let { it.copy(points = it.points.map { p ->
            if (p.sequence >= 10) p.copy(timeMs = p.timeMs - 60_000) else p
        }) }
        val result = builder.build(segment(), listOf(history(), corrected))
        assertTrue(result.firstPassedAtMs < 100_000)
        assertEquals(600.0, result.matchedDistanceM, 0.001)
    }

    @Test
    fun `malformed source and inconsistent segment fail instead of producing a result`() {
        val segment = segment()
        val malformed: List<List<HistoricalTrack>> = listOf(
            listOf(history(), history()),
            listOf(history().let { it.copy(points = it.points.reversed()) }),
            listOf(history().let { it.copy(points = it.points.map { p -> p.copy(tripId = "wrong") }) }),
        )
        malformed.forEach { tracks ->
            assertThrows(IllegalArgumentException::class.java) { builder.build(segment, tracks) }
        }
        assertThrows(IllegalArgumentException::class.java) { builder.build(segment.copy(sections = emptyList()), listOf(history())) }
        assertThrows(IllegalArgumentException::class.java) { builder.build(segment.copy(matchedDistanceM = 1.0), listOf(history())) }
    }
}
