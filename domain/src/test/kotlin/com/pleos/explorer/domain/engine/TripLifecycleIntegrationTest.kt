package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.RegionTestFixtures
import com.pleos.explorer.domain.model.Gear
import com.pleos.explorer.domain.model.LocationSample
import com.pleos.explorer.domain.model.LocationValidation
import com.pleos.explorer.domain.model.RecordingState
import com.pleos.explorer.domain.model.SignalSupport
import com.pleos.explorer.domain.model.TripInput
import com.pleos.explorer.domain.model.TripLifecycleSnapshot
import com.pleos.explorer.domain.model.TripStatus
import com.pleos.explorer.domain.model.TripTransition
import com.pleos.explorer.domain.model.TripVehicleSample
import com.pleos.explorer.domain.model.VehicleCapabilities
import com.pleos.explorer.domain.port.IdGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TripLifecycleIntegrationTest {
    private val epoch = 1_700_000_000_000L

    @Test
    fun `fallback replays the complete thirty second approved route once into F03`() {
        var id = 0
        val ids = IdGenerator { "id-${++id}" }
        val engine = TripStateMachine(ids)
        var state = TripLifecycleSnapshot("session", recordingConsentGranted = true, locationPermissionGranted = true)
        state = engine.process(state, TripInput.Vehicle(TripVehicleSample(epoch, 0),
            VehicleCapabilities(gear = SignalSupport.UNSUPPORTED)), 0).snapshot
        var previous: com.pleos.explorer.domain.model.ValidatedLocation? = null
        var event: TripTransition.Started? = null
        (0..30).forEach { i ->
            val sample = LocationSample(epoch + i * 1_000L, i * 1_000L,
                RegionTestFixtures.point(0.0, i * 10.0), 10.0, 0.0, 5.0)
            val validation = LocationValidator().validate(sample, previous) as LocationValidation.Accepted
            val update = engine.process(state, TripInput.Location(validation, sample.elapsedMs, sample.timeMs), i + 1L)
            event = update.transitions.filterIsInstance<TripTransition.Started>().singleOrNull() ?: event
            previous = validation.location
            state = update.snapshot
        }
        val start = requireNotNull(event)
        assertEquals(31, start.bufferedLocations.size)
        val recorder = TrackRecorder(ids)
        var track = recorder.start(start.trip.id, start.trip.startedAtMs, "session").snapshot
        val points = mutableListOf<com.pleos.explorer.domain.model.TrackPoint>()
        start.bufferedLocations.forEachIndexed { i, location ->
            val update = recorder.process(track, LocationValidation.Accepted(location), i.toLong(), emptyMap())
            points.addAll(update.points)
            track = update.snapshot
        }
        assertEquals(300.0, track.trip.distanceM, 0.001)
        assertEquals(epoch, track.trip.startedAtMs)
        val closed = engine.process(state, TripInput.AccessChanged(false, true, 31_000, epoch + 31_000), 32)
            .transitions.single() as TripTransition.Ended
        val result = recorder.finish(track, closed.atMs, points)
        assertEquals(TripStatus.FINISHED, result.snapshot.status)
        assertEquals(300.0, result.snapshot.trip.distanceM, 0.001)
        assertTrue(result.snapshot.trip.simplifiedPath.isNotEmpty())
    }

    @Test
    fun `pause interruption flushes F03 tail and never bridges a short gap after resuming`() {
        var id = 0
        val ids = IdGenerator { "id-${++id}" }
        val engine = TripStateMachine(ids)
        var state = TripLifecycleSnapshot("session", recordingConsentGranted = true, locationPermissionGranted = true)
        val start = engine.process(state, TripInput.Vehicle(TripVehicleSample(epoch, 0, Gear.D, false)), 0)
        state = start.snapshot
        val recorder = TrackRecorder(ids)
        var track = recorder.start((start.transitions.single() as TripTransition.Started).trip.id, epoch, "session").snapshot
        fun record(northM: Double, ms: Long, sequence: Long) = recorder.process(track,
            LocationValidator().validate(LocationSample(epoch + ms, ms, RegionTestFixtures.point(0.0, northM),
                2.0, 0.0, 5.0), track.previous), sequence, emptyMap()).also { track = it.snapshot }
        val first = record(0.0, 0, 1)
        record(5.0, 1_000, 2)
        val paused = engine.process(state, TripInput.Vehicle(TripVehicleSample(epoch + 1_000, 1_000,
            parkingBrakeEngaged = true)), 3)
        state = paused.snapshot
        assertEquals(RecordingState.PAUSED, state.recordingState)
        val interrupted = recorder.interrupt(track, 3)
        assertEquals(1, interrupted.points.size)
        track = interrupted.snapshot
        val resumed = engine.process(state, TripInput.Vehicle(TripVehicleSample(epoch + 2_000, 2_000,
            Gear.D, false)), 4)
        assertTrue(resumed.breakContinuity)
        val after = record(25.0, 3_000, 5)
        assertTrue(after.newSegment)
        assertEquals(0.0, after.connectedDistanceM, 0.0)
        assertEquals(5.0, track.trip.distanceM, 0.001)
        assertNotEquals(first.points.single().pathSegmentId, after.points.single().pathSegmentId)
        assertThrows(IllegalArgumentException::class.java) { recorder.interrupt(track, 5) }
        assertEquals(TripStatus.DISCARDED, recorder.finish(track, epoch + 4_000,
            first.points + interrupted.points + after.points).snapshot.status)
    }

    @Test
    fun `fresh location can resume a restored trip after F03 session interruption without double sequence`() {
        var id = 0
        val ids = IdGenerator { "id-${++id}" }
        val engine = TripStateMachine(ids)
        val recorder = TrackRecorder(ids)
        val initial = TripLifecycleSnapshot("old", recordingConsentGranted = true, locationPermissionGranted = true)
        val started = engine.process(initial, TripInput.Vehicle(TripVehicleSample(epoch, 0, Gear.D),
            VehicleCapabilities(parkingBrake = SignalSupport.UNSUPPORTED)), 0)
        val trip = (started.transitions.single() as TripTransition.Started).trip
        var track = recorder.start(trip.id, trip.startedAtMs, "old").snapshot
        val oldLocation = LocationValidator().validate(LocationSample(epoch, 0,
            RegionTestFixtures.point(0.0, 0.0), 2.0, 0.0, 5.0), null)
        track = recorder.process(track, oldLocation, 1, emptyMap()).snapshot
        var state = engine.resume(started.snapshot, "new").snapshot
        track = recorder.resume(track, "new").snapshot
        state = engine.process(state, TripInput.AccessChanged(true, true, 0, epoch + 1_000), 0).snapshot
        state = engine.process(state, TripInput.Vehicle(TripVehicleSample(epoch + 1_001, 1, Gear.N)), 1).snapshot
        val approved = LocationValidator().validate(LocationSample(epoch + 1_002, 2,
            RegionTestFixtures.point(0.0, 100.0), 2.0, 0.0, 5.0), null) as LocationValidation.Accepted
        val resumed = engine.process(state, TripInput.Location(approved, 2, epoch + 1_002), 2)
        assertTrue(resumed.transitions.single() is TripTransition.Resumed)
        assertTrue(resumed.recordCurrentLocation && resumed.breakContinuity)
        assertTrue(track.continuityBroken)
        val recorded = recorder.process(track, approved, 2, emptyMap())
        assertEquals(0.0, recorded.connectedDistanceM, 0.0)
        assertTrue(recorded.newSegment)
    }
}
