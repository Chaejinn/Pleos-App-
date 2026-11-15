package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.GhostTestFixtures.history
import com.pleos.explorer.domain.GhostTestFixtures.location
import com.pleos.explorer.domain.model.EfficiencySample
import com.pleos.explorer.domain.model.EfficiencyUnit
import com.pleos.explorer.domain.model.GhostCursor
import com.pleos.explorer.domain.model.GhostMetricsInput
import com.pleos.explorer.domain.model.GhostSection
import com.pleos.explorer.domain.model.GhostSegment
import com.pleos.explorer.domain.model.HarshBrakeState
import com.pleos.explorer.domain.model.HarshBrakeUpdate
import com.pleos.explorer.domain.model.HarshSteerState
import com.pleos.explorer.domain.model.HarshSteerUpdate
import com.pleos.explorer.domain.model.MetricsSample
import com.pleos.explorer.domain.model.Observation
import com.pleos.explorer.domain.model.TrackPosition
import com.pleos.explorer.domain.model.TurnDirection
import com.pleos.explorer.domain.model.TurnSignalState
import com.pleos.explorer.domain.model.TurnSignalTurn
import com.pleos.explorer.domain.model.TurnSignalUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GhostMetricsComparisonTest {
    private val builder = GhostResultBuilder()
    private val track = history(count = 41)

    private fun segment(indices: List<Int> = (0..23).toList()): GhostSegment {
        val sections = indices.map { i -> GhostSection(location(i * 25.0, i * 1_000L),
            location((i + 1) * 25.0, (i + 1) * 1_000L),
            GhostCursor(track.points[i], track.points[i + 1], 0.0),
            GhostCursor(track.points[i], track.points[i + 1], 1.0), 25.0) }
        return GhostSegment("ghost", "current", "past", sections.size * 25.0,
            sections.first().currentFrom.timeMs, sections.last().currentTo.timeMs, sections)
    }

    @Test
    fun `same matched range compares counts ratios and efficiency current minus past`() {
        val result = builder.build(segment(), listOf(track), input())
        val diff = requireNotNull(result.metricsDiff)
        assertEquals(-2, diff.harshBrakesDelta)
        assertEquals(1, diff.harshSteersDelta)
        assertEquals(-0.25, requireNotNull(diff.turnSignalRateDelta), 1e-12)
        assertEquals(5.0, requireNotNull(diff.efficiencyDelta).value, 1e-12)
        assertEquals(EfficiencyUnit.KM_PER_L, diff.efficiencyDelta?.unit)
        val legacy = builder.build(segment(), listOf(track))
        assertEquals(legacy, result.copy(metricsDiff = null))
    }

    @Test
    fun `range is clipped from raw observations rather than comparing whole trip totals`() {
        val result = builder.build(segment((8..31).toList()), listOf(track), input())
        assertEquals(-1, requireNotNull(result.metricsDiff).harshBrakesDelta)
        assertNotNull(result.metricsDiff?.efficiencyDelta)
    }

    @Test
    fun `shared boundary events occur once across adjacent sections`() {
        val source = input()
        val result = builder.build(segment(), listOf(track), source.copy(past = source.current.mapIndexed { i, sample ->
            sample.copy(position = sample.position.copy(tripId = "past", pointId = "past-$i",
                timeMs = track.points[i].timeMs, point = track.points[i].point))
        }))
        val diff = requireNotNull(result.metricsDiff)
        assertEquals(0, diff.harshBrakesDelta)
        assertEquals(0, diff.harshSteersDelta)
        assertEquals(0.0, requireNotNull(diff.turnSignalRateDelta), 0.0)
    }

    @Test
    fun `separate matched ranges sum turns before calculating rates and do not bridge gaps`() {
        val indices = (0..9).toList() + (20..39).toList()
        fun extraTurn(samples: List<MetricsSample>) = samples.mapIndexed { i, sample -> if (i == 30)
            sample.copy(turnSignal = requireNotNull(sample.turnSignal).copy(
                turn = TurnSignalTurn(TurnDirection.LEFT, false, sample.position.elapsedMs))) else sample }
        val source = input()
        val result = builder.build(segment(indices), listOf(track),
            GhostMetricsInput(extraTurn(source.current), extraTurn(source.past)))
        assertNotNull(result.metricsDiff)
        assertEquals(-1, result.metricsDiff?.harshBrakesDelta)
        assertEquals(-1.0 / 6, requireNotNull(result.metricsDiff?.turnSignalRateDelta), 1e-12)
        assertEquals(5.0, requireNotNull(result.metricsDiff?.efficiencyDelta).value, 1e-12)
    }

    @Test
    fun `missing evidence makes only its metric unknown`() {
        val source = input()
        val current = source.current.mapIndexed { i, s -> if (i == 12) s.copy(harshBrake = null) else s }
        val diff = requireNotNull(builder.build(segment(), listOf(track), source.copy(current = current)).metricsDiff)
        assertNull(diff.harshBrakesDelta)
        assertEquals(1, diff.harshSteersDelta)
        assertNotNull(diff.efficiencyDelta)
        val noSignals = source.current.map { it.copy(turnSignal = null) }
        val noSignalResult = builder.build(segment(), listOf(track), source.copy(current = noSignals))
        assertNull(noSignalResult.metricsDiff?.turnSignalRateDelta)
    }

    @Test
    fun `different efficiency units never affect other metrics`() {
        val source = input()
        val past = source.past.map {
            it.copy(efficiency = requireNotNull(it.efficiency).copy(unit = EfficiencyUnit.KM_PER_KWH))
        }
        val diff = requireNotNull(builder.build(segment(), listOf(track), source.copy(past = past)).metricsDiff)
        assertNull(diff.efficiencyDelta)
        assertEquals(-2, diff.harshBrakesDelta)
    }

    @Test
    fun `negative efficiency difference retains either verified unit`() {
        val source = input()
        for (unit in EfficiencyUnit.entries) {
            fun readings(samples: List<MetricsSample>, divisor: Double) = samples.map { sample ->
                sample.copy(efficiency = requireNotNull(sample.efficiency).copy(unit = unit,
                    consumption = Observation(sample.position.distanceFromTripStartM / divisor,
                        sample.position.elapsedMs)))
            }
            val result = builder.build(segment(), listOf(track),
                GhostMetricsInput(readings(source.current, 5_000.0), readings(source.past, 10_000.0)))
            assertEquals(-5.0, requireNotNull(result.metricsDiff?.efficiencyDelta).value, 1e-12)
            assertEquals(unit, result.metricsDiff?.efficiencyDelta?.unit)
        }
    }

    @Test
    fun `unavailable boundary or fractional historical cursor never prorates metrics`() {
        val source = input()
        assertNull(builder.build(segment(), listOf(track), source.copy(current = source.current.drop(1))).metricsDiff)
        assertNull(builder.build(segment(), listOf(track), source.copy(past = source.past.drop(1))).metricsDiff)
        val original = segment()
        val first = original.sections.first()
        val partial = original.copy(sections = listOf(first.copy(pastFrom = first.pastFrom.copy(fraction = 0.01))) +
            original.sections.drop(1))
        assertNull(builder.build(partial, listOf(track), source).metricsDiff)
        assertNull(builder.build(segment(), listOf(track), GhostMetricsInput(emptyList(), source.past)).metricsDiff)
    }

    @Test
    fun `missing interior historical evidence cannot certify comparison`() {
        val source = input()
        val missing = source.past.filter { it.position.pointId != "past-10" }
        assertNull(builder.build(segment(), listOf(track), source.copy(past = missing)).metricsDiff)
    }

    @Test
    fun `stale current location cannot certify comparison even when clock is unique`() {
        val source = input()
        val unrelatedCurrent = source.current.map { it.copy(position = it.position.copy(
            timeMs = requireNotNull(it.position.timeMs) - 100_000)) }
        assertNull(builder.build(segment(), listOf(track), source.copy(current = unrelatedCurrent)).metricsDiff)
        val wrongGeometry = source.current.map {
            it.copy(position = it.position.copy(point = location(9_000.0, 0).point))
        }
        assertNull(builder.build(segment(), listOf(track), source.copy(current = wrongGeometry)).metricsDiff)
    }

    @Test
    fun `historical identifiers also require original geometry and epoch evidence`() {
        val source = input()
        val wrongPast = source.past.map { it.copy(position = it.position.copy(
            timeMs = requireNotNull(it.position.timeMs) + 100_000)) }
        assertNull(builder.build(segment(), listOf(track), source.copy(past = wrongPast)).metricsDiff)
    }

    @Test
    fun `source path first event is retained while partial range start event is not repeated`() {
        val source = input()
        val past = source.past.mapIndexed { i, sample -> if (i in setOf(0, 8)) {
            val brake = requireNotNull(sample.harshBrake)
            sample.copy(harshBrake = brake.copy(state = brake.state.copy(active = true),
                decelerationMps2 = 3.0, started = true))
        } else sample }
        val full = builder.build(segment(), listOf(track), source.copy(past = past))
        assertEquals(-4, full.metricsDiff?.harshBrakesDelta)
        assertEquals(-1, builder.build(segment((8..31).toList()), listOf(track),
            source.copy(past = past)).metricsDiff?.harshBrakesDelta)
    }

    @Test
    fun `ambiguous current session clocks and conflicting observations are not silently selected`() {
        val source = input()
        val duplicatePath = source.current.map { it.copy(position = it.position.copy(pathSegmentId = "other",
            distanceFromTripStartM = it.position.distanceFromTripStartM + 1_000.0)) }
        val ambiguous = builder.build(segment(), listOf(track), source.copy(current = source.current + duplicatePath))
        assertNull(ambiguous.metricsDiff)
        assertThrows(IllegalArgumentException::class.java) {
            builder.build(segment(), listOf(track), source.copy(past = source.past.reversed()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            builder.build(segment(), listOf(track), source.copy(past = source.past.map {
                it.copy(position = it.position.copy(tripId = "unrelated"))
            }))
        }
    }

    @Test
    fun `all missing sensor fields and empty input leave complete metrics unknown`() {
        val source = input()
        assertNull(builder.build(segment(), listOf(track), source.copy(current = source.current.map {
            MetricsSample(it.position)
        })).metricsDiff)
        assertNull(builder.build(segment(), listOf(track), GhostMetricsInput(emptyList(), emptyList())).metricsDiff)
    }

    @Test
    fun `optional identity metadata preserves F15 collection but cannot certify a ghost comparison`() {
        val source = input()
        val old = source.current.map {
            it.copy(position = it.position.copy(timeMs = null, point = null, isPathStart = null))
        }
        assertEquals(1, MetricsCollector().collect(old, finished = true).single().harshBrakes)
        assertNull(builder.build(segment(), listOf(track), source.copy(current = old)).metricsDiff)
        val noOrigin = source.past.map { it.copy(position = it.position.copy(isPathStart = null)) }
        assertNull(builder.build(segment(), listOf(track), source.copy(past = noOrigin)).metricsDiff)
    }

    @Test
    fun `partly credited section and contradictory GPS totals are never scaled into comparison`() {
        val original = segment()
        val partial = original.copy(matchedDistanceM = original.matchedDistanceM - 1.0,
            sections = listOf(original.sections.first().copy(distanceM = 24.0)) + original.sections.drop(1))
        assertNull(builder.build(partial, listOf(track), input()).metricsDiff)
        val source = input()
        val wrongDistance = source.current.map { it.copy(position = it.position.copy(
            distanceFromTripStartM = it.position.distanceFromTripStartM * 2)) }
        assertNull(builder.build(original, listOf(track), source.copy(current = wrongDistance)).metricsDiff)
    }

    private fun input(): GhostMetricsInput = GhostMetricsInput(samples("current"), samples("past"))

    private fun samples(trip: String): List<MetricsSample> = (0..40).map { i ->
        val at = i * if (trip == "current") 1_000L else 2_000L
        val brake = i in if (trip == "current") setOf(10) else setOf(5, 10, 15)
        val steer = trip == "current" && i == 12
        val signal = when {
            trip == "current" && i == 10 -> true
            trip == "past" && i in setOf(5, 10, 15) -> true
            i == 20 -> false
            else -> null
        }
        val observation = Observation<Double?>(0.0, at)
        val distance = i * 25.0
        val original = location(distance, i * 1_000L)
        MetricsSample(TrackPosition(trip, "segment", "$trip-$i", distance, at,
            timeMs = if (trip == "current") original.timeMs else track.points[i].timeMs,
            point = original.point, isPathStart = i == 0),
            HarshBrakeUpdate(HarshBrakeState(observation, brake), 0.0, brake),
            HarshSteerUpdate(HarshSteerState(observation, steer), 0.0, steer),
            TurnSignalUpdate(TurnSignalState(angle = observation, armed = true),
                signal?.let { TurnSignalTurn(TurnDirection.LEFT, it, at) }),
            EfficiencySample(Observation(distance, at),
                Observation(distance / if (trip == "current") 10_000.0 else 5_000.0, at), EfficiencyUnit.KM_PER_L))
    }
}
