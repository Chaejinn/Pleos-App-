package com.pleos.explorer.domain.model

data class HistoricalTrack(val tripId: String, val points: List<TrackPoint>)

data class GhostKey(val tripId: String, val pathSegmentId: String)

data class GhostCursor(val from: TrackPoint, val to: TrackPoint, val fraction: Double) {
    val key: GhostKey get() = GhostKey(from.tripId, from.pathSegmentId)
    val timeMs: Long get() = from.timeMs + ((to.timeMs - from.timeMs) * fraction).toLong()
}

data class GhostSection(
    val currentFrom: ValidatedLocation,
    val currentTo: ValidatedLocation,
    val pastFrom: GhostCursor,
    val pastTo: GhostCursor,
    val distanceM: Double,
)

data class GhostProgress(
    val cursor: GhostCursor,
    val firstLocation: ValidatedLocation,
    val lastMatchedLocation: ValidatedLocation,
    val matchedDistanceM: Double = 0.0,
    val mismatchedDistanceM: Double = 0.0,
    val sections: List<GhostSection> = emptyList(),
    val id: String? = null,
)

data class GhostSegment(
    val id: String,
    val tripId: String,
    val pastTripId: String,
    val matchedDistanceM: Double,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val sections: List<GhostSection>,
)

sealed interface GhostEvent {
    data class Started(val id: String, val pastTripId: String, val atMs: Long) : GhostEvent
    data class Updated(val id: String, val matchedDistanceM: Double) : GhostEvent
    data class Finished(val segment: GhostSegment) : GhostEvent
}

sealed interface GhostInput {
    data class Location(
        val location: ValidatedLocation,
        val connectedDistanceM: Double,
        val newSegment: Boolean = false,
    ) : GhostInput
    data object ContinuityLost : GhostInput
    data object TripEnded : GhostInput
}

data class GhostSnapshot(
    val tripId: String,
    val sessionId: String,
    val revision: Long = 0,
    val lastInputSequence: Long = -1,
    val previous: ValidatedLocation? = null,
    val candidates: List<GhostProgress> = emptyList(),
    val active: GhostProgress? = null,
    val finished: Boolean = false,
)

data class GhostUpdate(val snapshot: GhostSnapshot, val events: List<GhostEvent> = emptyList())
