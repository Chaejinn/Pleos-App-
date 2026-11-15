package com.pleos.explorer.domain.model

data class TrackPosition(
    val tripId: String,
    val pathSegmentId: String,
    val pointId: String?,
    val distanceFromTripStartM: Double,
    val elapsedMs: Long,
    val timeMs: Long? = null,
    val point: GeoPoint? = null,
    val isPathStart: Boolean? = null,
) {
    init {
        require(tripId.isNotBlank() && pathSegmentId.isNotBlank())
        require(pointId == null || pointId.isNotBlank())
        require(distanceFromTripStartM.isFinite() && distanceFromTripStartM >= 0)
        require(elapsedMs >= 0)
        require(timeMs == null || timeMs >= 0)
        require(point == null || point.latitudeDeg.isFinite() && point.latitudeDeg in -90.0..90.0 &&
            point.longitudeDeg.isFinite() && point.longitudeDeg in -180.0..180.0)
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
            return TrackPosition(state.trip.id, segmentId, pointId, state.trip.distanceM, location.elapsedMs,
                location.timeMs, location.point, update.newSegment)
        }
    }
}
