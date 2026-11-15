package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.model.Efficiency
import com.pleos.explorer.domain.model.EfficiencySample
import com.pleos.explorer.domain.model.EfficiencyUnit
import com.pleos.explorer.domain.model.GeoPoint
import com.pleos.explorer.domain.model.HarshBrakeState
import com.pleos.explorer.domain.model.HarshBrakeUpdate
import com.pleos.explorer.domain.model.HarshSteerState
import com.pleos.explorer.domain.model.HarshSteerUpdate
import com.pleos.explorer.domain.model.LocationRejection
import com.pleos.explorer.domain.model.LocationSample
import com.pleos.explorer.domain.model.LocationValidation
import com.pleos.explorer.domain.model.MetricsSample
import com.pleos.explorer.domain.model.Observation
import com.pleos.explorer.domain.model.TrackPosition
import com.pleos.explorer.domain.model.TurnDirection
import com.pleos.explorer.domain.model.TurnSignal
import com.pleos.explorer.domain.model.TurnSignalInput
import com.pleos.explorer.domain.model.TurnSignalState
import com.pleos.explorer.domain.model.TurnSignalTurn
import com.pleos.explorer.domain.model.TurnSignalUpdate
import com.pleos.explorer.domain.model.TurnSignalUsage
import com.pleos.explorer.domain.port.IdGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsCollectorTest {
    private val collector = MetricsCollector()

    @Test
    fun `full kilometres retain overshoot and finish flushes short tail`() {
        val samples = listOf(sample(0, 0.0), sample(4_000, 800.0), sample(8_000, 1_600.0), sample(10_000, 2_100.0))
        val live = collector.collect(samples)
        assertEquals(listOf(0.0, 1_000.0), live.map { it.startDistanceM })
        assertEquals(listOf(1_000.0, 2_000.0), live.map { it.endDistanceM })
        val finished = collector.collect(samples, finished = true)
        assertEquals(listOf(1_000.0, 2_000.0, 2_100.0), finished.map { it.endDistanceM })
        assertEquals(listOf(0, 1, 2), finished.map { it.segmentIndex })
        assertEquals(live, finished.take(2))
        assertEquals(5_000L, live.first().endElapsedMs)
        assertNull(live[1].startPointId)
        assertEquals(finished, collector.collect(samples, finished = true))
    }

    @Test
    fun `path break flushes old tail and never adds the gap distance`() {
        val samples = listOf(sample(0, 0.0), sample(4_000, 400.0),
            sample(20_000, 400.0, path = "b"), sample(24_000, 1_500.0, path = "b"))
        val live = collector.collect(samples)
        assertEquals(listOf("a", "b"), live.map { it.pathSegmentId })
        assertEquals(listOf(400.0, 1_400.0), live.map { it.endDistanceM })
        assertEquals(1_400.0, collector.collect(samples, true).last().startDistanceM, 0.0)
    }

    @Test
    fun `empty stationary and unfinished partial paths have no rows`() {
        assertTrue(collector.collect(emptyList(), true).isEmpty())
        assertTrue(collector.collect(listOf(sample(0, 0.0), sample(1_000, 0.0)), true).isEmpty())
        assertTrue(collector.collect(listOf(sample(0, 0.0), sample(1_000, 199.0))).isEmpty())
        val residual = collector.collect(listOf(sample(0, 0.0), sample(1_000, 199.0)), true).single()
        assertEquals(199.0, residual.endDistanceM, 0.0)
    }

    @Test
    fun `exact boundary has no empty tail and keeps saved start reference`() {
        val samples = listOf(sample(0, 0.0), sample(4_000, 1_000.0), sample(8_000, 2_000.0))
        val rows = collector.collect(samples, true)
        assertEquals(2, rows.size)
        assertEquals("p4000", rows[1].startPointId)
        assertEquals(rows, collector.collect(samples + samples.last(), true))
    }

    @Test
    fun `stationary events after exact boundary belong to next range or terminal closing range`() {
        val samples = listOf(sample(0, 0.0), sample(2_000, 1_000.0), sample(3_000, 1_000.0, brake = true))
        val finished = collector.collect(samples, true).single()
        assertEquals(1, finished.harshBrakes)
        assertEquals(3_000L, finished.endElapsedMs)
        val moved = collector.collect(samples + sample(4_000, 1_100.0), true)
        assertEquals(listOf(0, 1), moved.map { it.harshBrakes })
    }

    @Test
    fun `rounded boundary clocks never move endpoint events into earlier ranges`() {
        val rows = collector.collect(listOf(sample(0, 0.0), sample(1, 3_000.0, brake = true)), true)
        assertEquals(listOf(0, 0, 1), rows.map { it.harshBrakes })
    }

    @Test
    fun `unobserved initial steering does not become zero known turns`() {
        val start = sample(0, 0.0)
        val unknown = start.copy(turnSignal = TurnSignalUpdate(TurnSignalState(
            angle = Observation(100.0, 0), usage = TurnSignalUsage(hasUnobservedSteering = true))))
        for (priorTurns in listOf(0, 1)) {
            val first = unknown.copy(turnSignal = requireNotNull(unknown.turnSignal).copy(state =
                requireNotNull(unknown.turnSignal).state.copy(usage = TurnSignalUsage(
                    turnCount = priorTurns, hasUnobservedSteering = true))))
            val rows = collector.collect(listOf(first, sample(4_000, 1_000.0), sample(8_000, 2_000.0)))
            assertNull(rows[0].turnCount)
            assertEquals(0, rows[1].turnCount)
        }
    }

    @Test
    fun `detector steering loss flag taints current range and recovers after neutral`() {
        val detector = TurnSignalDetector(TurnDirection.LEFT)
        val initial = detector.process(TurnSignalState(), TurnSignalInput.Angle(Observation(0.0, 0)))
        val missing = detector.process(initial.state, TurnSignalInput.Angle(Observation(null, 2_000)))
        val neutral = detector.process(missing.state, TurnSignalInput.Angle(Observation(0.0, 4_000)))
        val again = detector.process(neutral.state, TurnSignalInput.Angle(Observation(0.0, 8_000)))
        val rows = collector.collect(listOf(sample(0, 0.0).copy(turnSignal = initial),
            sample(4_000, 1_000.0).copy(turnSignal = neutral), sample(8_000, 2_000.0).copy(turnSignal = again)))
        assertNull(rows[0].turnCount)
        assertEquals(0, rows[1].turnCount)
    }

    @Test
    fun `events at boundary belong to closing range only`() {
        val samples = listOf(sample(0, 0.0), sample(4_000, 1_000.0, brake = true, steer = true, turn = true),
            sample(8_000, 2_000.0, turn = false))
        val rows = collector.collect(samples, true)
        assertEquals(listOf(1, 0), rows.map { it.harshBrakes })
        assertEquals(listOf(1, 0), rows.map { it.harshSteers })
        assertEquals(listOf(1, 1), rows.map { it.turnCount })
        assertEquals(listOf(1, 0), rows.map { it.signaledTurnCount })
        assertEquals(listOf(1.0, 0.0), rows.map { it.turnSignalRate })
    }

    @Test
    fun `event beyond interpolated boundary is not divided or moved backwards`() {
        val rows = collector.collect(listOf(sample(0, 0.0), sample(4_000, 1_200.0, brake = true)), true)
        assertEquals(listOf(0, 1), rows.map { it.harshBrakes })
        assertNull(rows[0].avgEfficiency)
        assertNull(rows[1].avgEfficiency)
    }

    @Test
    fun `missing mismatched or stale detector evidence stays unknown`() {
        val start = sample(0, 0.0)
        val end = sample(4_000, 1_000.0)
        val cases = listOf(end.copy(harshBrake = null, harshSteer = null, turnSignal = null),
            end.copy(harshBrake = requireNotNull(end.harshBrake).copy(decelerationMps2 = null),
                harshSteer = requireNotNull(end.harshSteer).copy(angularVelocityDegPerSec = null),
                turnSignal = TurnSignalUpdate(TurnSignalState())),
            end.copy(harshBrake = start.harshBrake, harshSteer = start.harshSteer, turnSignal = start.turnSignal),
            sample(6_000, 1_000.0))
        for (input in cases) {
            val row = collector.collect(listOf(start, input), true).single()
            assertNull(row.harshBrakes)
            assertNull(row.harshSteers)
            assertNull(row.turnCount)
            assertNull(row.signaledTurnCount)
            assertNull(row.turnSignalRate)
        }
    }

    @Test
    fun `unknown signal preserves turn count but not signaled count or rate`() {
        val end = sample(4_000, 1_000.0, turn = true)
        val update = requireNotNull(end.turnSignal)
        val unknown = end.copy(turnSignal = update.copy(turn = requireNotNull(update.turn).copy(signaled = null)))
        val row = collector.collect(listOf(sample(0, 0.0), unknown), true).single()
        assertEquals(1, row.turnCount)
        assertNull(row.signaledTurnCount)
        assertNull(row.turnSignalRate)
        assertNull(collector.collect(listOf(sample(0, 0.0), sample(4_000, 1_000.0))).single().turnSignalRate)
    }

    @Test
    fun `missing evidence taints only intersecting ranges`() {
        val missing = sample(4_000, 1_000.0).copy(harshBrake = null, harshSteer = null, turnSignal = null)
        val rows = collector.collect(listOf(sample(0, 0.0), missing, sample(8_000, 2_000.0), sample(12_000, 3_000.0)))
        assertNull(rows[0].harshBrakes)
        assertEquals(0, rows[1].harshBrakes)
        assertNull(rows[1].turnCount)
        assertEquals(0, rows[2].turnCount)
    }

    @Test
    fun `efficiency uses exact range observations and original cumulative counters`() {
        val samples = (0..8).map { sample(it * 1_000L, it * 250.0) }
        for (unit in EfficiencyUnit.entries) {
            val rows = collector.collect(samples.map {
                it.copy(efficiency = requireNotNull(it.efficiency).copy(unit = unit))
            })
            assertEquals(2, rows.size)
            rows.forEach { assertEquals(Efficiency(10.0, unit), it.avgEfficiency) }
        }
        val bad = samples.toMutableList()
        bad[2] = bad[2].copy(efficiency = null)
        val rows = collector.collect(bad)
        assertNull(rows[0].avgEfficiency)
        assertEquals(10.0, requireNotNull(rows[1].avgEfficiency).value, 1e-12)
    }

    @Test
    fun `asynchronous or unrelated efficiency distance is not aligned by rewriting timestamp`() {
        val end = sample(4_000, 1_000.0)
        val efficiency = requireNotNull(end.efficiency)
        for (bad in listOf(efficiency.copy(consumption = Observation(0.1, 3_999)),
            efficiency.copy(distanceM = Observation(999.0, 4_000)),
            efficiency.copy(distanceM = Observation(1_000.0, 4_001)))) {
            assertNull(collector.collect(listOf(sample(0, 0.0), end.copy(efficiency = bad))).single().avgEfficiency)
        }
    }

    @Test
    fun `invalid batch ordering identities and path reuse fail explicitly`() {
        val start = sample(0, 0.0)
        val end = sample(4_000, 1_000.0)
        for (bad in listOf(listOf(end, start),
            listOf(start, start.copy(position = start.position.copy(pointId = "other"))),
            listOf(start, end.copy(position = end.position.copy(tripId = "other"))),
            listOf(start, end, sample(5_000, 999.0)),
            listOf(start, sample(1_000, 0.0, path = "b"), sample(2_000, 0.0)),
            listOf(start, sample(1_000, 50.0, path = "b")))) {
            assertThrows(IllegalArgumentException::class.java) { collector.collect(bad, true) }
        }
        for (distance in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                start.position.copy(distanceFromTripStartM = distance)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { start.position.copy(elapsedMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { start.position.copy(tripId = " ") }
    }

    @Test
    fun `F03 approved unsaved positions retain distance and rejected updates produce no position`() {
        var id = 0
        val recorder = TrackRecorder(object : IdGenerator { override fun newId() = "id${id++}" })
        val validator = LocationValidator()
        var update = recorder.start("trip", 0, "session")
        assertNull(TrackPosition.from(update))
        val positions = (0..3).map { index ->
            val location = LocationSample(index * 1_000L, index * 1_000L,
                GeoPoint(36.0 + index * 0.00005, 127.0), 5.0, 0.0, 1.0)
            update = recorder.process(update.snapshot, validator.validate(location, update.snapshot.previous),
                index.toLong(), emptyMap())
            requireNotNull(TrackPosition.from(update))
        }
        assertNotNull(positions.first().pointId)
        assertNull(positions[1].pointId)
        assertTrue(positions.last().distanceFromTripStartM > 15)
        val rows = collector.collect(positions.map { MetricsSample(it) }, true)
        assertEquals(update.snapshot.trip.distanceM, rows.single().endDistanceM, 0.0)
        assertNull(rows.single().harshBrakes)
        val rejected = recorder.process(update.snapshot, LocationValidation.Rejected(LocationRejection.LOW_ACCURACY),
            4, emptyMap())
        assertNull(TrackPosition.from(rejected))
        assertNull(TrackPosition.from(update.copy(validation = null)))
        assertNull(TrackPosition.from(recorder.finish(update.snapshot, 5_000, emptyList())))
    }

    @Test
    fun `F03 approved positions and real detectors feed kilometre counts`() {
        val brake = HarshBrakeDetector()
        val steer = HarshSteerDetector()
        val signal = TurnSignalDetector(TurnDirection.LEFT)
        var bs = HarshBrakeState()
        var ss = HarshSteerState()
        var ts = TurnSignalState()
        var id = 0
        val recorder = TrackRecorder(object : IdGenerator { override fun newId() = "metric${id++}" })
        val validator = LocationValidator()
        var track = recorder.start("trip", 0, "session")
        val samples = (0..100).map { i ->
            val time = i * 1_000L
            val speed = Observation<Double?>(if (i < 5) 20.0 else 10.0, time)
            val angle = Observation<Double?>(if (i == 5) 200.0 else 0.0, time)
            val b = brake.process(bs, speed).also { bs = it.state }
            val s = steer.process(ss, angle, speed).also { ss = it.state }
            ts = signal.process(ts, TurnSignalInput.Signal(Observation(TurnSignal.LEFT, time))).state
            val t = signal.process(ts, TurnSignalInput.Angle(angle)).also { ts = it.state }
            val location = LocationSample(time, time, GeoPoint(36.0 + i * 0.0001, 127.0),
                speed.value, 0.0, 1.0)
            track = recorder.process(track.snapshot, validator.validate(location, track.snapshot.previous),
                i.toLong(), emptyMap())
            MetricsSample(requireNotNull(TrackPosition.from(track)), b, s, t)
        }
        val row = collector.collect(samples).single()
        assertEquals(1, row.harshBrakes)
        assertEquals(1, row.harshSteers)
        assertEquals(1, row.turnCount)
        assertEquals(1.0, row.turnSignalRate)
    }

    @Test
    fun `new path after resume can restart monotonic clock`() {
        val rows = collector.collect(listOf(sample(100_000, 0.0), sample(104_000, 400.0),
            sample(0, 400.0, path = "b"), sample(4_000, 1_400.0, path = "b")), true)
        assertEquals(listOf("a", "b"), rows.map { it.pathSegmentId })
        assertEquals(listOf(104_000L, 4_000L), rows.map { it.endElapsedMs })
        assertEquals(listOf(400.0, 1_400.0), rows.map { it.endDistanceM })
    }

    @Test
    fun `events at a new path first position are retained once`() {
        val rows = collector.collect(listOf(sample(0, 0.0), sample(4_000, 400.0),
            sample(20_000, 400.0, path = "b", brake = true, steer = true, turn = true),
            sample(24_000, 1_500.0, path = "b")), true)
        assertEquals(listOf(0, 1, 0), rows.map { it.harshBrakes })
        assertEquals(listOf(0, 1, 0), rows.map { it.harshSteers })
        assertEquals(listOf(0, 1, 0), rows.map { it.turnCount })
    }

    private fun sample(at: Long, distance: Double, path: String = "a", brake: Boolean = false,
        steer: Boolean = false, turn: Boolean? = null): MetricsSample {
        val angle = Observation<Double?>(0.0, at)
        return MetricsSample(
            TrackPosition("trip", path, "p$at", distance, at),
            HarshBrakeUpdate(HarshBrakeState(Observation(10.0, at), brake), 0.0, brake),
            HarshSteerUpdate(HarshSteerState(angle, steer), 0.0, steer),
            TurnSignalUpdate(TurnSignalState(angle = angle, armed = true),
                turn?.let { TurnSignalTurn(TurnDirection.LEFT, it, at) }),
            EfficiencySample(Observation(distance, at), Observation(distance / 10_000.0, at), EfficiencyUnit.KM_PER_L),
        )
    }
}
