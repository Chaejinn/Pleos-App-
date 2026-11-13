package com.pleos.explorer.domain.model

data class GeoPoint(val latitudeDeg: Double, val longitudeDeg: Double)

data class LocationSample(
    val timeMs: Long,
    val elapsedMs: Long,
    val point: GeoPoint,
    val speedMps: Double?,
    val bearingDeg: Double?,
    val accuracyM: Double?,
)

class ValidatedLocation internal constructor(
    val timeMs: Long,
    val elapsedMs: Long,
    val point: GeoPoint,
    val speedMps: Double?,
    val bearingDeg: Double?,
    val accuracyM: Double,
)

enum class LocationRejection { MISSING_ACCURACY, INVALID_VALUE, LOW_ACCURACY, OUT_OF_ORDER, JUMP }

sealed interface LocationValidation {
    data class Accepted(val location: ValidatedLocation) : LocationValidation
    data class Rejected(val reason: LocationRejection) : LocationValidation
}
