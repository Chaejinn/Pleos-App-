package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.geo.GeoDistance
import com.pleos.explorer.domain.model.DetectionPolicy
import com.pleos.explorer.domain.model.LocationRejection
import com.pleos.explorer.domain.model.LocationSample
import com.pleos.explorer.domain.model.LocationValidation
import com.pleos.explorer.domain.model.ValidatedLocation

class LocationValidator {
    fun validate(sample: LocationSample, previous: ValidatedLocation?): LocationValidation {
        val accuracy = sample.accuracyM
        val reason = when {
            !sample.point.latitudeDeg.isFinite() || sample.point.latitudeDeg !in -90.0..90.0 ||
                !sample.point.longitudeDeg.isFinite() || sample.point.longitudeDeg !in -180.0..180.0 ||
                sample.elapsedMs < 0 || sample.timeMs < 0 ||
                sample.speedMps?.let { !it.isFinite() || it < 0 } == true ||
                sample.bearingDeg?.let { !it.isFinite() || it < 0 || it >= 360 } == true ||
                accuracy?.let { !it.isFinite() || it < 0 } == true -> LocationRejection.INVALID_VALUE
            accuracy == null -> LocationRejection.MISSING_ACCURACY
            accuracy > DetectionPolicy.MAX_LOCATION_ACCURACY_M -> LocationRejection.LOW_ACCURACY
            previous != null && sample.elapsedMs <= previous.elapsedMs -> LocationRejection.OUT_OF_ORDER
            previous != null && sample.elapsedMs - previous.elapsedMs < DetectionPolicy.LOCATION_GAP_MS &&
                GeoDistance.meters(previous.point, sample.point) /
                ((sample.elapsedMs - previous.elapsedMs) / 1000.0) > DetectionPolicy.MAX_JUMP_SPEED_MPS ->
                LocationRejection.JUMP
            else -> null
        }
        if (reason != null) return LocationValidation.Rejected(reason)
        return LocationValidation.Accepted(
            ValidatedLocation(sample.timeMs, sample.elapsedMs, sample.point, sample.speedMps,
                sample.bearingDeg, requireNotNull(accuracy)),
        )
    }
}
