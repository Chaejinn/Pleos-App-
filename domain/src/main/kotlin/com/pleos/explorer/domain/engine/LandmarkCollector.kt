package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.geo.GeoDistance
import com.pleos.explorer.domain.model.GeoPoint
import com.pleos.explorer.domain.model.ValidatedLocation
import com.pleos.explorer.domain.model.legacy.Landmark

/** Only F-03 accepted observations reach this detector; all overlapping POIs are returned. */
class LandmarkCollector(private val catalog: List<Landmark>) {
    fun findNew(location: ValidatedLocation, collectedIds: Set<String>): List<Landmark> = catalog.filter {
        it.id !in collectedIds &&
            kotlin.math.abs(location.point.latitudeDeg - it.lat) <= it.radiusM / 110_000.0 &&
            GeoDistance.meters(location.point, GeoPoint(it.lat, it.lon)) <= it.radiusM
    }
}
