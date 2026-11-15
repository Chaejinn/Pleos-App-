package com.pleos.explorer.domain.geo

import com.pleos.explorer.domain.model.GeoPoint
import kotlin.math.cos

internal class GhostPlane(private val from: GeoPoint, to: GeoPoint) {
    private val scale = GeoDistance.EARTH_RADIUS_M * cos(Math.toRadians((from.latitudeDeg + to.latitudeDeg) / 2))

    fun project(point: GeoPoint) = PlanePoint(scale * Math.toRadians(point.longitudeDeg - from.longitudeDeg),
        GeoDistance.EARTH_RADIUS_M * Math.toRadians(point.latitudeDeg - from.latitudeDeg))
}
