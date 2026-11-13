package com.pleos.explorer.domain.geo

import com.pleos.explorer.domain.model.GeoPoint
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoDistance {
    const val EARTH_RADIUS_M = 6_371_008.8

    fun meters(from: GeoPoint, to: GeoPoint): Double {
        val latitude = Math.toRadians(to.latitudeDeg - from.latitudeDeg)
        val longitude = Math.toRadians(to.longitudeDeg - from.longitudeDeg)
        val a = sin(latitude / 2) * sin(latitude / 2) +
            cos(Math.toRadians(from.latitudeDeg)) * cos(Math.toRadians(to.latitudeDeg)) *
            sin(longitude / 2) * sin(longitude / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    internal fun distanceToSegment(point: GeoPoint, from: GeoPoint, to: GeoPoint): Double {
        val length = meters(from, to)
        if (length == 0.0) return meters(from, point)
        val angle = meters(from, point) / EARTH_RADIUS_M
        val bearingDifference = bearing(from, point) - bearing(from, to)
        val along = atan2(sin(angle) * cos(bearingDifference), cos(angle)) * EARTH_RADIUS_M
        return when {
            along <= 0.0 -> meters(from, point)
            along >= length -> meters(to, point)
            else -> kotlin.math.abs(asin((sin(angle) * sin(bearingDifference)).coerceIn(-1.0, 1.0))) *
                EARTH_RADIUS_M
        }
    }

    private fun bearing(from: GeoPoint, to: GeoPoint): Double {
        val a = Math.toRadians(from.latitudeDeg)
        val b = Math.toRadians(to.latitudeDeg)
        val longitude = Math.toRadians(to.longitudeDeg - from.longitudeDeg)
        return atan2(sin(longitude) * cos(b), cos(a) * sin(b) - sin(a) * cos(b) * cos(longitude))
    }
}
