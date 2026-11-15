package com.pleos.explorer.domain.model

enum class Gear { P, R, N, D, OTHER }
enum class SignalSupport { UNKNOWN, SUPPORTED, UNSUPPORTED }
enum class RecordingState { IDLE, RECORDING, PAUSED, ERROR }
enum class UiAccess { RESTRICTED, PARKED, UNKNOWN }
enum class TripEndReason { PARK, STATIONARY, ACCESS_REVOKED }

data class Observation<T>(val value: T, val observedAtElapsedMs: Long)

data class VehicleCapabilities(
    val gear: SignalSupport = SignalSupport.UNKNOWN,
    val parkingBrake: SignalSupport = SignalSupport.UNKNOWN,
    val speed: SignalSupport = SignalSupport.UNKNOWN,
)

data class TripVehicleSample(
    val timeMs: Long,
    val elapsedMs: Long,
    val gear: Gear? = null,
    val parkingBrakeEngaged: Boolean? = null,
    val speedMps: Double? = null,
)

sealed interface TripInput {
    data class Vehicle(val sample: TripVehicleSample, val capabilities: VehicleCapabilities? = null) : TripInput
    data class Location(val validation: LocationValidation, val elapsedMs: Long, val timeMs: Long) : TripInput
    data class Tick(val elapsedMs: Long, val timeMs: Long) : TripInput
    data class AccessChanged(
        val recordingConsentGranted: Boolean,
        val locationPermissionGranted: Boolean,
        val elapsedMs: Long,
        val timeMs: Long,
    ) : TripInput
}

data class TripStartCandidate(val locations: List<ValidatedLocation>)

data class TripLifecycleSnapshot(
    val sessionId: String,
    val revision: Long = 0,
    val lastInputSequence: Long = -1,
    val lastElapsedMs: Long? = null,
    val lastLocationElapsedMs: Long? = null,
    val accessGrantedAtElapsedMs: Long? = null,
    val recordingState: RecordingState = RecordingState.IDLE,
    val recordingSinceElapsedMs: Long? = null,
    val tripId: String? = null,
    val recordingConsentGranted: Boolean = false,
    val locationPermissionGranted: Boolean = false,
    val capabilities: VehicleCapabilities = VehicleCapabilities(),
    val capabilitiesAtElapsedMs: Long? = null,
    val gear: Observation<Gear>? = null,
    val parkingBrake: Observation<Boolean>? = null,
    val vehicleSpeed: Observation<Double>? = null,
    val locationSpeed: Observation<Double>? = null,
    val startCandidate: TripStartCandidate? = null,
    val stoppedSinceElapsedMs: Long? = null,
    val startBlocked: Boolean = false,
    val uiAccess: UiAccess = UiAccess.UNKNOWN,
)

sealed interface TripTransition {
    data class Started(val trip: Trip, val bufferedLocations: List<ValidatedLocation> = emptyList()) : TripTransition
    data class Paused(val tripId: String, val atMs: Long) : TripTransition
    data class Resumed(val tripId: String, val atMs: Long) : TripTransition
    data class Ended(val tripId: String, val atMs: Long, val reason: TripEndReason) : TripTransition
}

data class TripLifecycleUpdate(
    val snapshot: TripLifecycleSnapshot,
    val transitions: List<TripTransition> = emptyList(),
    val breakContinuity: Boolean = false,
    val recordCurrentLocation: Boolean = false,
)
