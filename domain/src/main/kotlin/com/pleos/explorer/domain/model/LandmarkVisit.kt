package com.pleos.explorer.domain.model

/** A point-of-interest visit, distinct from a polygon-based RegionBadge. */
data class LandmarkVisit(
    val landmarkId: String,
    val acquiredAtMs: Long,
    val tripId: String?,
    val inputSource: String,
    val acknowledged: Boolean = false,
) {
    val isReplay: Boolean get() = inputSource == "REPLAY"
    val key: String get() = "$inputSource:$landmarkId"
}
