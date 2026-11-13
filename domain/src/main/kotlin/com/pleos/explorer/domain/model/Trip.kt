package com.pleos.explorer.domain.model

data class Trip(
    val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val distanceM: Double = 0.0,
    val odometerStartKm: Double? = null,
    val odometerEndKm: Double? = null,
    val simplifiedPath: List<PathSegment> = emptyList(),
)

data class PathSegment(val segmentId: String, val points: List<GeoPoint>)

data class TrackPoint(
    val id: String,
    val tripId: String,
    val timeMs: Long,
    val point: GeoPoint,
    val speedMps: Double?,
    val bearingDeg: Double?,
    val accuracyM: Double,
    val gridKey: String,
    val pathSegmentId: String,
    val sequence: Long,
)

data class ExploredRoad(
    val gridKey: String,
    val firstTripId: String?,
    val firstVisitedAtMs: Long,
    val lastVisitedAtMs: Long,
    val visitCount: Int,
    val firstExploredDistanceM: Double,
)
