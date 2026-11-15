package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.GhostTestFixtures.history
import com.pleos.explorer.domain.GhostTestFixtures.location
import com.pleos.explorer.domain.RegionTestFixtures
import com.pleos.explorer.domain.geo.GridProjector
import com.pleos.explorer.domain.model.LocationSample
import com.pleos.explorer.domain.model.LocationValidation
import com.pleos.explorer.domain.model.TrackPoint
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
        assertEquals(segment.sections.indices.toSet(), result.passages.map { it.sectionIndex }.toSet())
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
        assertEquals(3_001_000L, result.passages.filter { it.sectionIndex == 0 }.maxOf { it.previousLastPassedAtMs })
    }

    @Test
    fun `partial recent visit updates only corresponding sections`() {
        val recent = history("recent", 3_000_000).let { it.copy(points = it.points.take(9)) }
        val result = builder.build(segment(), listOf(history(), recent))
        assertTrue(result.passages.first().previousLastPassedAtMs >= 3_000_000)
        assertTrue(result.passages.last().previousLastPassedAtMs < 3_000_000)
    }

    @Test
    fun `recent visit inside one long approved section splits its date evidence`() {
        val detector = GhostDetector(IdGenerator { "ghost" })
        var state = GhostSnapshot("current", "session")
        (0..6).forEach { i ->
            state = detector.process(state, GhostInput.Location(location(i * 100.0, i * 4_000L),
                if (i == 0) 0.0 else 100.0, i == 0), i.toLong(), listOf(history())).snapshot
        }
        val segment = detector.process(state, GhostInput.TripEnded, 7).events
            .filterIsInstance<GhostEvent.Finished>().single().segment
        val partial = history("recent", 3_000_000).let { it.copy(points = it.points.filter { p -> p.sequence in 2L..3L }) }
        val result = builder.build(segment, listOf(history(), partial))
        val firstSection = result.passages.filter { it.sectionIndex == 0 }
        assertTrue(firstSection.any { it.previousLastPassedAtMs >= 3_000_000 })
        assertTrue(firstSection.any { it.previousLastPassedAtMs < 3_000_000 })
        assertEquals(100.0, firstSection.sumOf { it.distanceM }, 0.001)
    }

    @Test
    fun `crossing past edge contributes dates even when both originals are outside current corridor`() {
        fun approved(eastM: Double, northM: Double, elapsedMs: Long, bearing: Double) =
            (LocationValidator().validate(LocationSample(10_000_000 + elapsedMs, elapsedMs,
                RegionTestFixtures.point(eastM, northM), null, bearing, 5.0), null) as LocationValidation.Accepted).location
        fun track(id: String, values: List<Triple<Double, Double, Double>>, baseMs: Long): HistoricalTrack =
            HistoricalTrack(id, values.mapIndexed { index, (east, north, bearing) ->
                val point = RegionTestFixtures.point(east, north)
                TrackPoint("$id-$index", id, baseMs + index * 5_000L, point, null, bearing, 5.0,
                    GridProjector().key(point), "segment", index.toLong())
            })
        val old = track("old", (0..8).map { Triple(0.0, it * 100.0, 0.0) }, 1_000_000)
        val recent = track("recent", listOf(Triple(-90.0, -90.0, 45.0), Triple(90.0, 90.0, 45.0)), 3_000_000)
        val first = approved(-90.0, -90.0, 0, 45.0)
        assertTrue(LocationValidator().validate(LocationSample(10_005_000, 5_000,
            recent.points.last().point, null, 45.0, 5.0), first) is LocationValidation.Accepted)
        val detector = GhostDetector(IdGenerator { "ghost" })
        var state = GhostSnapshot("current", "session")
        (0..6).forEach { i ->
            state = detector.process(state, GhostInput.Location(approved(0.0, i * 100.0, i * 4_000L, 0.0),
                if (i == 0) 0.0 else 100.0, i == 0), i.toLong(), listOf(old)).snapshot
        }
        val segment = detector.process(state, GhostInput.TripEnded, 7).events
            .filterIsInstance<GhostEvent.Finished>().single().segment
        val result = builder.build(segment, listOf(old, recent))
        val firstSection = result.passages.filter { it.sectionIndex == 0 }
        assertTrue(firstSection.any { it.previousLastPassedAtMs >= 3_000_000 })
        assertTrue(firstSection.any { it.previousLastPassedAtMs < 3_000_000 })
    }

    @Test
    fun `stored cursor originals preserve a completed result after source removal`() {
        val result = builder.build(segment(), emptyList())
        assertEquals(600.0, result.matchedDistanceM, 0.001)
        assertFalse(result.pastPath.isEmpty())
        assertEquals(1_000_000L, result.firstPassedAtMs)
    }

    @Test
    fun `unmatched bridge remains separate in both displayed paths`() {
        val detector = GhostDetector(IdGenerator { "ghost" })
        var state = GhostSnapshot("current", "session")
        (0..40).forEach { i ->
            state = detector.process(state, GhostInput.Location(location(i * 25.0, i * 1_000L,
                bearing = if (i in 22..24) null else 0.0), if (i == 0) 0.0 else 25.0, i == 0),
                i.toLong(), listOf(history())).snapshot
        }
        val segment = detector.process(state, GhostInput.TripEnded, 41).events
            .filterIsInstance<GhostEvent.Finished>().single().segment
        val result = builder.build(segment, listOf(history()))
        assertEquals(2, result.currentPath.size)
        assertEquals(2, result.pastPath.size)
        assertTrue(result.currentPath.first().points.last() != result.currentPath.last().points.first())
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

    @Test
    fun `six kilometer comparison retains dates across fifteen past visits`() {
        val past = (0..14).map { i -> history("past-$i", 100_000L + i * 100_000, count = 251) }
        val detector = GhostDetector(IdGenerator { "ghost" })
        var state = GhostSnapshot("current", "session")
        (0..240).forEach { i ->
            state = detector.process(state, GhostInput.Location(location(i * 25.0, i * 1_000L),
                if (i == 0) 0.0 else 25.0, i == 0), i.toLong(), past.takeLast(1)).snapshot
        }
        val segment = detector.process(state, GhostInput.TripEnded, 241).events
            .filterIsInstance<GhostEvent.Finished>().single().segment
        val started = System.nanoTime()
        val result = builder.build(segment, past)
        println("F13 6km/15visits build ms=${(System.nanoTime() - started) / 1_000_000.0}")
        assertEquals(6_000.0, result.matchedDistanceM, 0.001)
        assertEquals(100_000L, result.firstPassedAtMs)
        assertEquals(1_740_000L, result.previousLastPassedAtMs)
    }
}
