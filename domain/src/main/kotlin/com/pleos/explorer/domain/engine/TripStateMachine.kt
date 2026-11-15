package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.model.DetectionPolicy
import com.pleos.explorer.domain.model.Gear
import com.pleos.explorer.domain.model.LocationValidation
import com.pleos.explorer.domain.model.Observation
import com.pleos.explorer.domain.model.RecordingState
import com.pleos.explorer.domain.model.SignalSupport
import com.pleos.explorer.domain.model.Trip
import com.pleos.explorer.domain.model.TripEndReason
import com.pleos.explorer.domain.model.TripInput
import com.pleos.explorer.domain.model.TripLifecycleSnapshot
import com.pleos.explorer.domain.model.TripLifecycleUpdate
import com.pleos.explorer.domain.model.TripStartCandidate
import com.pleos.explorer.domain.model.TripTransition
import com.pleos.explorer.domain.model.UiAccess
import com.pleos.explorer.domain.model.VehicleCapabilities
import com.pleos.explorer.domain.port.IdGenerator

class TripStateMachine(private val ids: IdGenerator) {
    fun process(state: TripLifecycleSnapshot, input: TripInput, sequence: Long): TripLifecycleUpdate {
        validate(state)
        require(sequence >= 0)
        if (sequence <= state.lastInputSequence) return TripLifecycleUpdate(state)
        val (observedElapsed, atMs) = when (input) {
            is TripInput.Vehicle -> input.sample.elapsedMs to input.sample.timeMs
            is TripInput.Location -> input.elapsedMs to input.timeMs
            is TripInput.Tick -> input.elapsedMs to input.timeMs
            is TripInput.AccessChanged -> input.elapsedMs to input.timeMs
        }
        require(observedElapsed >= 0 && atMs >= 0)
        if (input is TripInput.Tick || input is TripInput.AccessChanged) {
            require(state.lastElapsedMs == null || observedElapsed >= state.lastElapsedMs)
        }
        val elapsed = maxOf(observedElapsed, state.lastElapsedMs ?: observedElapsed)
        val usable = elapsed - observedElapsed <= freshMs &&
            (state.accessGrantedAtElapsedMs == null || observedElapsed >= state.accessGrantedAtElapsedMs)
        var next = state.copy(revision = Math.addExact(state.revision, 1), lastInputSequence = sequence,
            lastElapsedMs = elapsed)
        if (next.startCandidate?.locations?.lastOrNull()?.let { elapsed - it.elapsedMs > freshMs } == true) {
            next = next.copy(startCandidate = null)
        }
        next = when (input) {
            is TripInput.Vehicle -> vehicle(next, input, usable)
            is TripInput.Location -> {
                val accepted = (input.validation as? LocationValidation.Accepted)?.location
                if (accepted != null) {
                    require(accepted.elapsedMs == observedElapsed && accepted.timeMs == atMs)
                    require(accepted.speedMps?.let { it.isFinite() && it >= 0 } != false)
                }
                if (!usable || state.lastLocationElapsedMs?.let { observedElapsed <= it } == true) next else {
                    next.copy(lastLocationElapsedMs = observedElapsed,
                        locationSpeed = accepted?.speedMps?.let { Observation(it, observedElapsed) },
                        startCandidate = if (accepted == null || accepted.speedMps == null ||
                            accepted.speedMps < DetectionPolicy.TRIP_START_SPEED_MPS) null else next.startCandidate)
                }
            }
            is TripInput.AccessChanged -> {
                val changed = input.recordingConsentGranted != next.recordingConsentGranted ||
                    input.locationPermissionGranted != next.locationPermissionGranted
                val updated = next.copy(recordingConsentGranted = input.recordingConsentGranted,
                    locationPermissionGranted = input.locationPermissionGranted,
                    accessGrantedAtElapsedMs = if (changed) elapsed else next.accessGrantedAtElapsedMs)
                if (changed) clearObservations(updated) else updated
            }
            is TripInput.Tick -> next
        }
        if (!next.recordingConsentGranted || !next.locationPermissionGranted) {
            val ended = if (input is TripInput.AccessChanged && next.tripId != null) {
                listOf(TripTransition.Ended(requireNotNull(next.tripId), atMs, TripEndReason.ACCESS_REVOKED))
            } else emptyList()
            next = clearObservations(next).copy(uiAccess = UiAccess.UNKNOWN)
            if (ended.isNotEmpty()) next = next.copy(tripId = null, recordingState = RecordingState.IDLE,
                recordingSinceElapsedMs = null)
            return TripLifecycleUpdate(next, ended, ended.isNotEmpty())
        }
        val speed = speed(next, elapsed)
        val priorZero = speed(state, elapsed) == 0.0
        next = next.copy(stoppedSinceElapsedMs = if (speed == 0.0) {
            if (priorZero) state.stoppedSinceElapsedMs ?: elapsed else {
                if (usable && (input is TripInput.Vehicle || input is TripInput.Location)) observedElapsed else elapsed
            }
        } else null)
        val gear = fresh(next.gear, elapsed)
        val brake = fresh(next.parkingBrake, elapsed)
        if (speed != null && speed > 0 || gear != null && gear !in drivingGears) {
            next = next.copy(startBlocked = false)
        }
        val events = mutableListOf<TripTransition>()
        if (next.tripId != null) {
            val reason = when {
                gear == Gear.P -> TripEndReason.PARK
                next.stoppedSinceElapsedMs?.let { elapsed - it >= DetectionPolicy.TRIP_STOP_DURATION_MS } == true ->
                    TripEndReason.STATIONARY
                else -> null
            }
            if (reason != null) {
                events.add(TripTransition.Ended(requireNotNull(next.tripId), atMs, reason))
                next = next.copy(tripId = null, recordingState = RecordingState.IDLE, startCandidate = null,
                    stoppedSinceElapsedMs = null, startBlocked = reason == TripEndReason.STATIONARY,
                    recordingSinceElapsedMs = null)
            }
        }
        if (next.tripId == null && events.isEmpty()) {
            val fallback = next.capabilities.gear == SignalSupport.UNSUPPORTED && brake != true
            if (!fallback) next = next.copy(startCandidate = null)
            if (fallback && usable && input is TripInput.Location &&
                state.lastLocationElapsedMs?.let { observedElapsed > it } != false) {
                val location = (input.validation as? LocationValidation.Accepted)?.location
                if (location?.speedMps?.let { it >= DetectionPolicy.TRIP_START_SPEED_MPS } == true) {
                    next = next.copy(startCandidate = TripStartCandidate(
                        next.startCandidate?.locations.orEmpty() + location))
                }
            }
            val buffered = next.startCandidate?.locations.orEmpty()
            val speedStart = buffered.isNotEmpty() && elapsed - buffered.first().elapsedMs >=
                DetectionPolicy.TRIP_START_DURATION_MS
            if (gear in drivingGears && !next.startBlocked || speedStart) {
                val id = ids.newId()
                check(id.isNotBlank())
                val route = if (speedStart) buffered.toList() else emptyList()
                events.add(TripTransition.Started(Trip(id, route.firstOrNull()?.timeMs ?: atMs), route))
                next = next.copy(tripId = id, recordingState = RecordingState.RECORDING, startCandidate = null,
                    startBlocked = false, recordingSinceElapsedMs = route.firstOrNull()?.elapsedMs ?: observedElapsed,
                    stoppedSinceElapsedMs = if (speed == 0.0) observedElapsed else null)
            }
        }
        if (next.tripId != null) {
            val id = requireNotNull(next.tripId)
            if (brake == true && next.recordingState == RecordingState.RECORDING) {
                events.add(TripTransition.Paused(id, atMs))
                next = next.copy(recordingState = RecordingState.PAUSED, recordingSinceElapsedMs = null)
            } else if (next.recordingState == RecordingState.PAUSED &&
                (brake == false || next.capabilities.parkingBrake == SignalSupport.UNSUPPORTED) &&
                (gear in drivingGears || speed?.let { it > 0 } == true &&
                    (gear != null && gear != Gear.P || next.capabilities.gear == SignalSupport.UNSUPPORTED))) {
                events.add(TripTransition.Resumed(id, atMs))
                next = next.copy(recordingState = RecordingState.RECORDING, recordingSinceElapsedMs = observedElapsed)
            }
        }
        next = next.copy(uiAccess = access(next, elapsed))
        val record = usable && input is TripInput.Location && input.validation is LocationValidation.Accepted &&
            state.lastLocationElapsedMs?.let { observedElapsed > it } != false &&
            next.recordingState == RecordingState.RECORDING &&
            next.recordingSinceElapsedMs?.let { observedElapsed >= it } == true &&
            events.none { it is TripTransition.Started }
        return TripLifecycleUpdate(next, events.toList(), events.any { it !is TripTransition.Started }, record)
    }

    fun resume(state: TripLifecycleSnapshot, sessionId: String): TripLifecycleUpdate {
        validate(state)
        require(sessionId.isNotBlank())
        if (state.sessionId == sessionId) return TripLifecycleUpdate(state)
        return TripLifecycleUpdate(clearObservations(state).copy(sessionId = sessionId,
            revision = Math.addExact(state.revision, 1), lastInputSequence = -1, lastElapsedMs = null,
            lastLocationElapsedMs = null, accessGrantedAtElapsedMs = null,
            capabilitiesAtElapsedMs = null,
            recordingConsentGranted = false, locationPermissionGranted = false, uiAccess = UiAccess.UNKNOWN,
            recordingSinceElapsedMs = null,
            recordingState = if (state.tripId == null) RecordingState.IDLE else RecordingState.PAUSED),
            breakContinuity = true)
    }

    private fun vehicle(state: TripLifecycleSnapshot, input: TripInput.Vehicle, usable: Boolean): TripLifecycleSnapshot {
        val sample = input.sample
        require(sample.speedMps?.let { it.isFinite() && it >= 0 } != false)
        val capAt = state.capabilitiesAtElapsedMs ?: 0
        val metadata = input.capabilities.takeIf { usable && sample.elapsedMs >= capAt }
        val caps = metadata ?: state.capabilities
        fun useField(support: SignalSupport) = usable &&
            (support != SignalSupport.UNSUPPORTED || sample.elapsedMs >= capAt)
        val observedGear = sample.gear.takeIf { useField(caps.gear) }
        val observedBrake = sample.parkingBrakeEngaged.takeIf { useField(caps.parkingBrake) }
        val observedSpeed = sample.speedMps.takeIf { useField(caps.speed) }
        require(metadata?.gear != SignalSupport.UNSUPPORTED || observedGear == null)
        require(metadata?.parkingBrake != SignalSupport.UNSUPPORTED || observedBrake == null)
        require(metadata?.speed != SignalSupport.UNSUPPORTED || observedSpeed == null)
        val actual = VehicleCapabilities(
            if (observedGear == null) caps.gear else SignalSupport.SUPPORTED,
            if (observedBrake == null) caps.parkingBrake else SignalSupport.SUPPORTED,
            if (observedSpeed == null) caps.speed else SignalSupport.SUPPORTED)
        val newEvidence = metadata != null || observedGear != null || observedBrake != null || observedSpeed != null
        return state.copy(capabilities = actual,
            capabilitiesAtElapsedMs = if (newEvidence) maxOf(capAt, sample.elapsedMs) else state.capabilitiesAtElapsedMs,
            gear = observedGear?.takeIf { sample.elapsedMs >= (state.gear?.observedAtElapsedMs ?: 0) }
                ?.let { Observation(it, sample.elapsedMs) }
                ?: state.gear.takeIf { actual.gear == state.capabilities.gear },
            parkingBrake = observedBrake?.takeIf {
                sample.elapsedMs >= (state.parkingBrake?.observedAtElapsedMs ?: 0)
            }?.let { Observation(it, sample.elapsedMs) }
                ?: state.parkingBrake.takeIf { actual.parkingBrake == state.capabilities.parkingBrake },
            vehicleSpeed = observedSpeed?.takeIf {
                sample.elapsedMs >= (state.vehicleSpeed?.observedAtElapsedMs ?: 0)
            }?.let { Observation(it, sample.elapsedMs) }
                ?: state.vehicleSpeed.takeIf { actual.speed == state.capabilities.speed })
    }

    private fun access(state: TripLifecycleSnapshot, elapsed: Long): UiAccess {
        if (speed(state, elapsed)?.let { it > 0 } == true) return UiAccess.RESTRICTED
        if (state.capabilities.speed == SignalSupport.SUPPORTED && fresh(state.vehicleSpeed, elapsed) == null ||
            state.capabilities.gear == SignalSupport.SUPPORTED && fresh(state.gear, elapsed) == null ||
            state.capabilities.parkingBrake == SignalSupport.SUPPORTED && fresh(state.parkingBrake, elapsed) == null) {
            return UiAccess.UNKNOWN
        }
        val gear = fresh(state.gear, elapsed)
        val brake = fresh(state.parkingBrake, elapsed)
        if (gear == Gear.P || brake == true) return UiAccess.PARKED
        if (state.capabilities.gear == SignalSupport.UNSUPPORTED &&
            state.capabilities.parkingBrake == SignalSupport.UNSUPPORTED &&
            state.stoppedSinceElapsedMs?.let { elapsed - it >= DetectionPolicy.PARKED_FALLBACK_DURATION_MS } == true) {
            return UiAccess.PARKED
        }
        return if (gear != null || brake == false) UiAccess.RESTRICTED else UiAccess.UNKNOWN
    }

    private fun speed(state: TripLifecycleSnapshot, elapsed: Long): Double? =
        listOfNotNull(fresh(state.vehicleSpeed, elapsed), fresh(state.locationSpeed, elapsed)).maxOrNull()

    private fun <T> fresh(value: Observation<T>?, elapsed: Long): T? = value?.takeIf {
        elapsed - it.observedAtElapsedMs in 0..freshMs
    }?.value

    private fun clearObservations(state: TripLifecycleSnapshot) = state.copy(gear = null, parkingBrake = null,
        vehicleSpeed = null, locationSpeed = null, startCandidate = null, stoppedSinceElapsedMs = null,
        lastLocationElapsedMs = null)

    private fun validate(state: TripLifecycleSnapshot) {
        require(state.sessionId.isNotBlank() && state.revision >= 0 && state.lastInputSequence >= -1)
        require(state.lastElapsedMs == null || state.lastElapsedMs >= 0)
        require((state.recordingState == RecordingState.IDLE) == (state.tripId == null))
        require(state.tripId == null || state.tripId.isNotBlank())
        val observations = listOfNotNull(state.gear?.observedAtElapsedMs, state.parkingBrake?.observedAtElapsedMs,
            state.vehicleSpeed?.observedAtElapsedMs, state.locationSpeed?.observedAtElapsedMs,
            state.lastLocationElapsedMs, state.accessGrantedAtElapsedMs, state.stoppedSinceElapsedMs,
            state.recordingSinceElapsedMs, state.capabilitiesAtElapsedMs)
        require(observations.all { it >= 0 && state.lastElapsedMs != null && it <= state.lastElapsedMs })
        require(listOfNotNull(state.vehicleSpeed?.value, state.locationSpeed?.value).all { it.isFinite() && it >= 0 })
        require(state.gear == null || state.capabilities.gear == SignalSupport.SUPPORTED)
        require(state.parkingBrake == null || state.capabilities.parkingBrake == SignalSupport.SUPPORTED)
        require(state.vehicleSpeed == null || state.capabilities.speed == SignalSupport.SUPPORTED)
        require(state.recordingState == RecordingState.RECORDING || state.recordingSinceElapsedMs == null)
        state.startCandidate?.let { candidate ->
            require(state.recordingState == RecordingState.IDLE && state.recordingConsentGranted &&
                state.locationPermissionGranted && state.capabilities.gear == SignalSupport.UNSUPPORTED)
            require(candidate.locations.isNotEmpty())
            require(candidate.locations.all { it.elapsedMs >= 0 && state.lastElapsedMs != null &&
                it.elapsedMs <= state.lastElapsedMs && it.speedMps?.let { speed ->
                    speed.isFinite() && speed >= DetectionPolicy.TRIP_START_SPEED_MPS } == true })
            require(candidate.locations.zipWithNext().all { (a, b) -> b.elapsedMs > a.elapsedMs })
        }
        check(state.recordingState != RecordingState.ERROR) { "Recording error must be resolved before processing" }
    }

    private val freshMs = DetectionPolicy.VEHICLE_OBSERVATION_MAX_AGE_MS
    private val drivingGears = setOf(Gear.D, Gear.R)
}
