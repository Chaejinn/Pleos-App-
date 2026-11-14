package com.pleos.explorer.domain

import com.pleos.explorer.domain.engine.LocationValidator
import com.pleos.explorer.domain.geo.GeoDistance
import com.pleos.explorer.domain.geo.GridProjector
import com.pleos.explorer.domain.model.GeoPoint
import com.pleos.explorer.domain.model.HistoricalTrack
import com.pleos.explorer.domain.model.LocationSample
import com.pleos.explorer.domain.model.LocationValidation
import com.pleos.explorer.domain.model.TrackPoint

object GhostTestFixtures {
    const val NOW_MS = 10_000_000L

    fun point(northM: Double, eastM: Double = 0.0) =
        GeoPoint(Math.toDegrees(northM / GeoDistance.EARTH_RADIUS_M),
            Math.toDegrees(eastM / GeoDistance.EARTH_RADIUS_M))

    fun location(northM: Double, elapsedMs: Long, eastM: Double = 0.0, bearing: Double? = 0.0) =
        (LocationValidator().validate(LocationSample(NOW_MS + elapsedMs, elapsedMs,
            point(northM, eastM), null, bearing, 5.0), null) as LocationValidation.Accepted).location

    fun history(id: String = "past", atMs: Long = 1_000_000, bearing: Double? = 0.0,
        eastM: Double = 0.0, count: Int = 121): HistoricalTrack = HistoricalTrack(id,
        (0 until count).map { index ->
            val p = point(index * 25.0, eastM)
            TrackPoint("$id-$index", id, atMs + index * 1_000, p, null, bearing, 5.0,
                GridProjector().key(p), "segment", index.toLong())
        })
}
