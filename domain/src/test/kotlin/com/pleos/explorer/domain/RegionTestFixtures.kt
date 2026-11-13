package com.pleos.explorer.domain

import com.pleos.explorer.domain.engine.LocationValidator
import com.pleos.explorer.domain.model.GeoPoint
import com.pleos.explorer.domain.model.LocationSample
import com.pleos.explorer.domain.model.LocationValidation
import com.pleos.explorer.domain.model.RegionDefinition
import com.pleos.explorer.domain.model.RegionGeometry
import com.pleos.explorer.domain.model.RegionPolygon
import kotlin.math.cos

internal object RegionTestFixtures {
    fun point(eastM: Double, northM: Double): GeoPoint = GeoPoint(
        36.0 + Math.toDegrees(northM / 6_371_008.8),
        127.0 + Math.toDegrees(eastM / (6_371_008.8 * cos(Math.toRadians(36.0)))),
    )

    fun rectangle(west: Double, south: Double, east: Double, north: Double): List<GeoPoint> = listOf(
        point(west, south), point(east, south), point(east, north), point(west, north), point(west, south),
    )

    fun region(code: String, west: Double = -5000.0, south: Double = -5000.0,
        east: Double = 5000.0, north: Double = 5000.0): RegionDefinition =
        region(code, RegionGeometry(listOf(RegionPolygon(rectangle(west, south, east, north)))))

    fun region(code: String, geometry: RegionGeometry): RegionDefinition = RegionDefinition(
        code, code, "province", point(0.0, 0.0), geometry,
    )

    fun location(northM: Double, elapsedMs: Long, eastM: Double = 0.0) =
        (LocationValidator().validate(LocationSample(1_000_000 + elapsedMs, elapsedMs,
            point(eastM, northM), null, null, 5.0), null) as LocationValidation.Accepted).location
}
