package com.pleos.explorer.domain.model

import java.time.Instant
import java.time.ZoneId

enum class Coast { EAST, WEST }

data class CoastPoint(val id: String, val coast: Coast, val point: GeoPoint) {
    init {
        require(id.isNotBlank())
        requireRegionPoint(point)
    }
}

data class CoastVisit(
    val coastId: String,
    val coast: Coast,
    val atMs: Long,
    val tripId: String? = null,
) {
    init {
        require(coastId.isNotBlank() && atMs >= 0)
        require(tripId == null || tripId.isNotBlank())
    }

    val date: String get() = Instant.ofEpochMilli(atMs).atZone(SEOUL).toLocalDate().toString()

    companion object {
        private val SEOUL = ZoneId.of("Asia/Seoul")
    }
}
