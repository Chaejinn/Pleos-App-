package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.RegionTestFixtures
import com.pleos.explorer.domain.model.Gear
import com.pleos.explorer.domain.model.LocationRejection
import com.pleos.explorer.domain.model.LocationSample
import com.pleos.explorer.domain.model.LocationValidation
import com.pleos.explorer.domain.model.RecordingState
import com.pleos.explorer.domain.model.SignalSupport
import com.pleos.explorer.domain.model.TripEndReason
import com.pleos.explorer.domain.model.TripInput
import com.pleos.explorer.domain.model.TripLifecycleSnapshot
import com.pleos.explorer.domain.model.TripLifecycleUpdate
import com.pleos.explorer.domain.model.TripTransition
import com.pleos.explorer.domain.model.TripVehicleSample
import com.pleos.explorer.domain.model.UiAccess
import com.pleos.explorer.domain.model.VehicleCapabilities
import com.pleos.explorer.domain.port.IdGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TripStateMachineTest {
    private val epoch = 1_700_000_000_000L
    private val unsupported = VehicleCapabilities(SignalSupport.UNSUPPORTED, SignalSupport.UNSUPPORTED,
        SignalSupport.UNSUPPORTED)

    private inner class Fixture {
        var id = 0
        val engine = TripStateMachine(IdGenerator { "trip-${++id}" })
        var state = TripLifecycleSnapshot("session", recordingConsentGranted = true, locationPermissionGranted = true)
        var sequence = 0L
        fun process(input: TripInput): TripLifecycleUpdate = engine.process(state, input, sequence++).also {
            state = it.snapshot
        }
        fun vehicle(ms: Long, gear: Gear? = null, brake: Boolean? = null, speed: Double? = null,
            capabilities: VehicleCapabilities? = null, atMs: Long = epoch + ms) =
            process(TripInput.Vehicle(TripVehicleSample(atMs, ms, gear, brake, speed), capabilities))
        fun location(ms: Long, speed: Double?, atMs: Long = epoch + ms): TripLifecycleUpdate {
            val validation = LocationValidator().validate(LocationSample(atMs, ms,
                RegionTestFixtures.point(0.0, ms / 1_000.0), speed, 0.0, 5.0), null)
            return process(TripInput.Location(validation, ms, atMs))
        }
        fun tick(ms: Long) = process(TripInput.Tick(ms, epoch + ms))
        fun access(ms: Long, consent: Boolean, permission: Boolean) =
            process(TripInput.AccessChanged(consent, permission, ms, epoch + ms))
        fun noGear() = vehicle(0, capabilities = unsupported)
    }

    @Test
    fun `initial D or R starts once and driving gear changes keep the same trip`() {
        listOf(Gear.D, Gear.R).forEach { initial ->
            val f = Fixture()
            val started = f.vehicle(0, initial).transitions.single() as TripTransition.Started
            assertEquals(epoch, started.trip.startedAtMs)
            listOf(Gear.R, Gear.N, Gear.D).forEachIndexed { i, gear ->
                assertTrue(f.vehicle((i + 1) * 1_000L, gear).transitions.isEmpty())
                assertEquals(started.trip.id, f.state.tripId)
            }
            assertEquals(1, f.id)
        }
    }

    @Test
    fun `P ends immediately and another D creates a different trip`() {
        val f = Fixture()
        f.vehicle(0, Gear.D)
        val oldId = f.state.tripId
        val ended = f.vehicle(1_000, Gear.P).transitions.single() as TripTransition.Ended
        assertEquals(TripEndReason.PARK, ended.reason)
        assertEquals(epoch + 1_000, ended.atMs)
        assertEquals(RecordingState.IDLE, f.state.recordingState)
        assertTrue(f.vehicle(2_000, Gear.P).transitions.isEmpty())
        f.vehicle(3_000, Gear.D)
        assertNotEquals(oldId, f.state.tripId)
    }

    @Test
    fun `brake pauses and a fresh release with driving evidence resumes the same trip`() {
        val f = Fixture()
        f.vehicle(0, Gear.D, false, 5.0)
        val id = f.state.tripId
        val paused = f.vehicle(1_000, brake = true, speed = 0.0)
        assertTrue(paused.transitions.single() is TripTransition.Paused)
        assertTrue(paused.breakContinuity)
        assertEquals(UiAccess.PARKED, f.state.uiAccess)
        assertTrue(f.vehicle(2_000, brake = true).transitions.isEmpty())
        val resumed = f.vehicle(3_000, Gear.D, false, 5.0)
        assertTrue(resumed.transitions.single() is TripTransition.Resumed)
        assertTrue(resumed.breakContinuity)
        assertEquals(id, f.state.tripId)
    }

    @Test
    fun `D with engaged brake starts paused and missing brake does not manufacture release`() {
        val f = Fixture()
        assertEquals(2, f.vehicle(0, Gear.D, true, 0.0).transitions.size)
        assertEquals(RecordingState.PAUSED, f.state.recordingState)
        assertTrue(f.vehicle(6_000, Gear.D, speed = 4.0).transitions.isEmpty())
        assertEquals(RecordingState.PAUSED, f.state.recordingState)
        assertTrue(f.vehicle(7_000, Gear.D, false, 4.0).transitions.single() is TripTransition.Resumed)
    }

    @Test
    fun `fallback requires explicitly unsupported gear and thirty seconds of approved speed`() {
        val f = Fixture()
        f.noGear()
        (0..29).forEach { assertTrue(f.location(it * 1_000L, 5.0 / 3.6).transitions.isEmpty()) }
        assertTrue(f.tick(29_999).transitions.isEmpty())
        val started = f.tick(30_000).transitions.single() as TripTransition.Started
        assertEquals(epoch, started.trip.startedAtMs)
        assertEquals(30, started.bufferedLocations.size)
        assertEquals(0L, started.bufferedLocations.first().elapsedMs)
        assertEquals(29_000L, started.bufferedLocations.last().elapsedMs)
        assertNull(f.state.startCandidate)
    }

    @Test
    fun `unknown or temporarily missing supported gear never enables speed fallback`() {
        listOf(SignalSupport.UNKNOWN, SignalSupport.SUPPORTED).forEach { support ->
            val f = Fixture()
            f.vehicle(0, capabilities = VehicleCapabilities(gear = support))
            (0..40).forEach { assertTrue(f.location(it * 1_000L, 10.0).transitions.isEmpty()) }
            assertNull(f.state.startCandidate)
        }
    }

    @Test
    fun `below threshold null rejected and stale locations break fallback continuity`() {
        val f = Fixture()
        f.noGear()
        (0..10).forEach { f.location(it * 1_000L, 2.0) }
        f.location(11_000, 5.0 / 3.6 - 0.0001)
        assertNull(f.state.startCandidate)
        f.location(12_000, 2.0)
        f.location(13_000, null)
        assertNull(f.state.startCandidate)
        f.location(14_000, 2.0)
        f.process(TripInput.Location(LocationValidation.Rejected(LocationRejection.LOW_ACCURACY), 15_000,
            epoch + 15_000))
        assertNull(f.state.startCandidate)
        f.location(16_000, 2.0)
        f.location(21_001, 2.0)
        assertEquals(21_001L, f.state.startCandidate?.locations?.first()?.elapsedMs)
        assertTrue(f.tick(30_000).transitions.isEmpty())
        assertNull(f.state.startCandidate)
    }

    @Test
    fun `vehicle speed without approved locations cannot supply the fallback route`() {
        val f = Fixture()
        f.vehicle(0, speed = 5.0, capabilities = unsupported.copy(speed = SignalSupport.SUPPORTED))
        (1..40).forEach { assertTrue(f.vehicle(it * 1_000L, speed = 5.0).transitions.isEmpty()) }
        assertNull(f.state.tripId)
    }

    @Test
    fun `zero speed includes five minutes and D heartbeats do not immediately restart`() {
        val f = Fixture()
        f.vehicle(0, Gear.D, speed = 0.0)
        (1..299).forEach { assertTrue(f.vehicle(it * 1_000L, Gear.D, speed = 0.0).transitions.isEmpty()) }
        assertTrue(f.tick(299_999).transitions.isEmpty())
        val ended = f.tick(300_000).transitions.single() as TripTransition.Ended
        assertEquals(TripEndReason.STATIONARY, ended.reason)
        assertTrue(f.vehicle(301_000, Gear.D, speed = 0.0).transitions.isEmpty())
        assertTrue(f.vehicle(307_000, Gear.D, speed = 0.0).transitions.isEmpty())
        assertTrue(f.vehicle(308_000, Gear.D, speed = 1.0).transitions.single() is TripTransition.Started)
    }

    @Test
    fun `stale observations cannot complete a long stop or refresh gear through another field`() {
        val f = Fixture()
        f.vehicle(0, Gear.D, speed = 0.0)
        (1..299).forEach { f.vehicle(it * 1_000L, speed = 0.0) }
        assertEquals(0L, f.state.gear?.observedAtElapsedMs)
        assertTrue(f.tick(304_001).transitions.isEmpty())
        assertNull(f.state.stoppedSinceElapsedMs)
        assertEquals(RecordingState.RECORDING, f.state.recordingState)
        f.vehicle(305_000, speed = 0.0)
        assertEquals(305_000L, f.state.stoppedSinceElapsedMs)
    }

    @Test
    fun `moving evidence contradicting zero from another source resets the stop`() {
        val f = Fixture()
        f.vehicle(0, Gear.D, speed = 0.0)
        f.location(1_000, 3.0)
        assertNull(f.state.stoppedSinceElapsedMs)
        assertEquals(UiAccess.RESTRICTED, f.state.uiAccess)
        f.vehicle(2_000, Gear.P, speed = 0.0)
        assertEquals(UiAccess.RESTRICTED, f.state.uiAccess)
    }

    @Test
    fun `UI access needs fresh P brake or explicit unsupported stationary fallback`() {
        val f = Fixture()
        f.vehicle(0, Gear.P)
        assertEquals(UiAccess.PARKED, f.state.uiAccess)
        f.tick(5_000)
        assertEquals(UiAccess.PARKED, f.state.uiAccess)
        f.tick(5_001)
        assertEquals(UiAccess.UNKNOWN, f.state.uiAccess)
        val fallback = Fixture()
        fallback.noGear()
        (0..9).forEach { fallback.location(it * 1_000L, 0.0) }
        fallback.tick(9_999)
        assertEquals(UiAccess.UNKNOWN, fallback.state.uiAccess)
        fallback.tick(10_000)
        assertEquals(UiAccess.PARKED, fallback.state.uiAccess)
        fallback.tick(14_001)
        assertEquals(UiAccess.UNKNOWN, fallback.state.uiAccess)
    }

    @Test
    fun `consent and permission are both required and no preconsent location is retained`() {
        val f = Fixture()
        f.state = TripLifecycleSnapshot("session")
        f.vehicle(0, Gear.D)
        f.location(1_000, 5.0)
        assertNull(f.state.tripId)
        assertNull(f.state.gear)
        assertNull(f.state.startCandidate)
        f.access(2_000, true, false)
        assertTrue(f.vehicle(3_000, Gear.D).transitions.isEmpty())
        f.access(4_000, true, true)
        assertTrue(f.tick(4_000).transitions.isEmpty())
        assertTrue(f.vehicle(5_000, Gear.D).transitions.single() is TripTransition.Started)
    }

    @Test
    fun `revocation ends a trip once and clears pending locations without deleting history`() {
        listOf(true to false, false to true).forEach { (consent, permission) ->
            val f = Fixture()
            f.vehicle(0, Gear.D)
            val ended = f.access(1_000, consent, permission)
            assertEquals(TripEndReason.ACCESS_REVOKED, (ended.transitions.single() as TripTransition.Ended).reason)
            assertTrue(ended.breakContinuity)
            assertNull(f.state.tripId)
            assertTrue(f.access(2_000, consent, permission).transitions.isEmpty())
        }
        val pending = Fixture()
        pending.noGear()
        pending.location(0, 4.0)
        pending.access(1_000, false, true)
        assertNull(pending.state.startCandidate)
    }

    @Test
    fun `duplicate sequence is inert and out of order elapsed time fails`() {
        val f = Fixture()
        val input = TripInput.Vehicle(TripVehicleSample(epoch, 0, Gear.D))
        f.process(input)
        val same = f.engine.process(f.state, input, 0)
        assertTrue(same.transitions.isEmpty())
        assertEquals(f.state, same.snapshot)
        assertEquals(1, f.id)
        f.tick(1_000)
        assertThrows(IllegalArgumentException::class.java) { f.tick(999) }
    }

    @Test
    fun `wall clock reversal does not change monotonic stop duration`() {
        val f = Fixture()
        f.vehicle(0, Gear.D, speed = 0.0)
        (1..300).forEach { i ->
            f.vehicle(i * 1_000L, speed = 0.0, atMs = epoch - i * 1_000L)
        }
        assertEquals(RecordingState.IDLE, f.state.recordingState)
    }

    @Test
    fun `resume preserves trip but drops old clocks authorization and pending samples`() {
        val f = Fixture()
        f.vehicle(0, Gear.D, false, 2.0)
        val id = f.state.tripId
        val resumed = f.engine.resume(f.state, "new")
        f.state = resumed.snapshot
        f.sequence = 0
        assertEquals(id, f.state.tripId)
        assertEquals(RecordingState.PAUSED, f.state.recordingState)
        assertTrue(resumed.breakContinuity)
        assertNull(f.state.gear)
        assertFalse(f.state.recordingConsentGranted)
        assertEquals(-1L, f.state.lastInputSequence)
        f.access(0, true, true)
        assertTrue(f.tick(0).transitions.isEmpty())
        assertTrue(f.vehicle(1, Gear.D, false, 2.0).transitions.single() is TripTransition.Resumed)
        assertEquals(id, f.state.tripId)
        assertFalse(f.engine.resume(f.state, "new").breakContinuity)
        val pending = Fixture()
        pending.noGear()
        pending.location(0, 2.0)
        assertNull(pending.engine.resume(pending.state, "new").snapshot.startCandidate)
    }

    @Test
    fun `uncommitted proposal leaves input state untouched and reload suppresses committed retry`() {
        val f = Fixture()
        val before = f.state
        val input = TripInput.Vehicle(TripVehicleSample(epoch, 0, Gear.D))
        val proposed = f.engine.process(before, input, 0)
        assertEquals(RecordingState.IDLE, before.recordingState)
        assertNull(before.tripId)
        assertTrue(f.engine.process(before, input, 0).transitions.single() is TripTransition.Started)
        assertTrue(f.engine.process(proposed.snapshot, input, 0).transitions.isEmpty())
    }

    @Test
    fun `invalid signal support timestamps values and lifecycle snapshot fail explicitly`() {
        val f = Fixture()
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { f.vehicle(0, speed = bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { f.vehicle(-1, Gear.D) }
        assertThrows(IllegalArgumentException::class.java) { f.vehicle(0, Gear.D, atMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { f.vehicle(0, Gear.D, capabilities = unsupported) }
        assertThrows(IllegalArgumentException::class.java) {
            f.engine.process(f.state.copy(tripId = "broken"), TripInput.Tick(0, epoch), 0)
        }
        assertThrows(IllegalArgumentException::class.java) { f.engine.resume(f.state, " ") }
        val active = Fixture()
        active.vehicle(0, Gear.D, speed = 0.0)
        val invalid = listOf(active.state.copy(vehicleSpeed = com.pleos.explorer.domain.model.Observation(Double.NaN, 0)),
            active.state.copy(gear = com.pleos.explorer.domain.model.Observation(Gear.P, 1)),
            active.state.copy(capabilities = unsupported))
        invalid.forEach { snapshot -> assertThrows(IllegalArgumentException::class.java) {
            active.engine.process(snapshot, TripInput.Tick(1, epoch + 1), 1)
        } }
    }

    @Test
    fun `late accepted SDK positions and vehicle fields retain their own observation clocks`() {
        val f = Fixture()
        f.noGear()
        f.location(0, 2.0)
        f.tick(1_000)
        f.location(900, 2.0)
        assertEquals(1_000L, f.state.lastElapsedMs)
        assertEquals(900L, f.state.locationSpeed?.observedAtElapsedMs)
        assertEquals(listOf(0L, 900L), f.state.startCandidate?.locations?.map { it.elapsedMs })
        f.location(800, 0.0)
        assertEquals(900L, f.state.locationSpeed?.observedAtElapsedMs)
        f.tick(10_000)
        f.location(2_000, 2.0)
        assertNull(f.state.startCandidate)
        val vehicle = Fixture()
        vehicle.tick(1_000)
        assertTrue(vehicle.vehicle(900, Gear.D).transitions.single() is TripTransition.Started)
        vehicle.vehicle(800, Gear.P)
        assertEquals(Gear.D, vehicle.state.gear?.value)
    }

    @Test
    fun `stale supported signals never authorize parked UI and preconsent samples stay discarded`() {
        val f = Fixture()
        f.vehicle(0, Gear.D, speed = 10.0)
        f.vehicle(6_000, Gear.P, true)
        assertEquals(UiAccess.UNKNOWN, f.state.uiAccess)
        f.vehicle(7_000, Gear.P, true, 0.0)
        assertEquals(UiAccess.PARKED, f.state.uiAccess)
        val denied = Fixture()
        denied.state = TripLifecycleSnapshot("session")
        denied.access(1_000, true, true)
        assertTrue(denied.vehicle(900, Gear.D).transitions.isEmpty())
        assertNull(denied.state.gear)
    }

    @Test
    fun `late pre-pause locations do not become resumed route points`() {
        val f = Fixture()
        f.vehicle(0, Gear.D, false, 2.0)
        assertTrue(f.location(500, 2.0).recordCurrentLocation)
        f.vehicle(1_000, brake = true, speed = 0.0)
        assertFalse(f.location(1_500, 0.0).recordCurrentLocation)
        f.vehicle(2_000, Gear.D, false, 2.0)
        assertFalse(f.location(1_900, 2.0).recordCurrentLocation)
        assertTrue(f.location(2_100, 2.0).recordCurrentLocation)
        assertFalse(f.location(2_100, 2.0).recordCurrentLocation)
    }

    @Test
    fun `parking time before a new trip never completes its five minute stop timer`() {
        val f = Fixture()
        (0..310).forEach { f.vehicle(it * 1_000L, Gear.P, speed = 0.0) }
        assertTrue(f.vehicle(311_000, Gear.D, speed = 0.0).transitions.single() is TripTransition.Started)
        assertEquals(311_000L, f.state.stoppedSinceElapsedMs)
        assertTrue(f.tick(311_001).transitions.isEmpty())
    }

    @Test
    fun `late support metadata cannot erase a newer moving speed or bypass restart blocking`() {
        val f = Fixture()
        f.vehicle(1_000, speed = 10.0)
        f.vehicle(2_000, Gear.P)
        f.vehicle(500, capabilities = VehicleCapabilities(gear = SignalSupport.SUPPORTED,
            speed = SignalSupport.UNSUPPORTED))
        assertEquals(10.0, f.state.vehicleSpeed?.value)
        assertEquals(UiAccess.RESTRICTED, f.state.uiAccess)
        f.tick(10_000)
        f.vehicle(3_000, capabilities = unsupported)
        assertEquals(SignalSupport.SUPPORTED, f.state.capabilities.speed)
        val stopped = Fixture()
        (0..300).forEach { stopped.vehicle(it * 1_000L, Gear.D, speed = 0.0) }
        assertTrue(stopped.state.startBlocked)
        assertTrue(stopped.vehicle(299_500, Gear.N).transitions.isEmpty())
        assertTrue(stopped.state.startBlocked)
        val recovered = Fixture()
        recovered.noGear()
        assertTrue(recovered.vehicle(1_000, Gear.D, false, 3.0).transitions.single() is TripTransition.Started)
        assertEquals(SignalSupport.SUPPORTED, recovered.state.capabilities.speed)
        assertEquals(UiAccess.RESTRICTED, recovered.state.uiAccess)
    }
}
