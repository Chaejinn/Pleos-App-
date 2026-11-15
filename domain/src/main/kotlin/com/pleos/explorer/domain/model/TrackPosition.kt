package com.pleos.explorer.domain.model

data class TrackPosition(
    val tripId: String,
    val pathSegmentId: String,
    val pointId: String?,
    val distanceFromTripStartM: Double,
    val elapsedMs: Long,
) {
    init {
        require(tripId.isNotBlank() && pathSegmentId.isNotBlank())
        require(pointId == null || pointId.isNotBlank())
        require(distanceFromTripStartM.isFinite() && distanceFromTripStartM >= 0)
        require(elapsedMs >= 0)
    }

    companion object {
        fun from(update: TrackUpdate): TrackPosition? {
            val location = (update.validation as? LocationValidation.Accepted)?.location ?: return null
            val state = update.snapshot
            if (state.status != TripStatus.ACTIVE || state.continuityBroken || state.previous != location) return null
            val segmentId = state.segmentId ?: return null
            val pointId = state.lastStored?.takeIf {
                state.lastStoredElapsedMs == location.elapsedMs && it.pathSegmentId == segmentId
            }?.id
            return TrackPosition(state.trip.id, segmentId, pointId, state.trip.distanceM, location.elapsedMs)
        }
    }
}
