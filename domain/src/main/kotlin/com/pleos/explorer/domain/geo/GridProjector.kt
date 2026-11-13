package com.pleos.explorer.domain.geo

import com.pleos.explorer.domain.model.DetectionPolicy
import com.pleos.explorer.domain.model.GeoPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor

data class GridSection(val gridKey: String, val from: GeoPoint, val to: GeoPoint, val distanceM: Double)

internal data class PlanePoint(val x: Double, val y: Double)

class GridProjector {
    private val longitudeScale = GeoDistance.EARTH_RADIUS_M * cos(Math.toRadians(36.0))

    fun key(point: GeoPoint): String {
        val plane = project(point)
        return key(floor(plane.x / DetectionPolicy.GRID_SIZE_M).toLong(),
            floor(plane.y / DetectionPolicy.GRID_SIZE_M).toLong())
    }

    fun neighbors(gridKey: String): Set<String> {
        val parts = gridKey.split(':')
        require(parts.size == 3 && parts[0] == "v1")
        val column = parts[1].toLong()
        val row = parts[2].toLong()
        return (-1L..1L).flatMap { x -> (-1L..1L).map { y -> key(column + x, row + y) } }.toSet()
    }

    fun split(from: GeoPoint, to: GeoPoint): List<GridSection> {
        val a = project(from)
        val b = project(to)
        if (abs(to.longitudeDeg - from.longitudeDeg) > 180) {
            val endLongitude = to.longitudeDeg + if (to.longitudeDeg < from.longitudeDeg) 360 else -360
            val boundary = if (endLongitude > from.longitudeDeg) 180.0 else -180.0
            val fraction = (boundary - from.longitudeDeg) / (endLongitude - from.longitudeDeg)
            val latitude = from.latitudeDeg + (to.latitudeDeg - from.latitudeDeg) * fraction
            return split(from, GeoPoint(latitude, boundary)) + split(GeoPoint(latitude, -boundary), to)
        }
        val distance = GeoDistance.meters(from, to)
        if (distance == 0.0) return emptyList()
        val fractions = mutableSetOf(0.0, 1.0)
        addCrossings(a.x, b.x, fractions)
        addCrossings(a.y, b.y, fractions)
        return fractions.sorted().zipWithNext().mapNotNull { (start, end) ->
            if (end - start < 1e-12) return@mapNotNull null
            val midpoint = interpolate(from, to, (start + end) / 2)
            GridSection(key(midpoint), interpolate(from, to, start), interpolate(from, to, end),
                distance * (end - start))
        }
    }

    internal fun project(point: GeoPoint): PlanePoint {
        require(point.latitudeDeg.isFinite() && point.latitudeDeg in -90.0..90.0)
        require(point.longitudeDeg.isFinite() && point.longitudeDeg in -180.0..180.0)
        return PlanePoint(longitudeScale * Math.toRadians(point.longitudeDeg - 127.0),
            GeoDistance.EARTH_RADIUS_M * Math.toRadians(point.latitudeDeg - 36.0))
    }

    private fun addCrossings(from: Double, to: Double, fractions: MutableSet<Double>) {
        if (from == to) return
        val first = floor(minOf(from, to) / DetectionPolicy.GRID_SIZE_M).toLong() + 1
        val last = floor(maxOf(from, to) / DetectionPolicy.GRID_SIZE_M).toLong()
        for (boundary in first..last) {
            val fraction = (boundary * DetectionPolicy.GRID_SIZE_M - from) / (to - from)
            if (fraction > 0 && fraction < 1) fractions.add(fraction)
        }
    }

    private fun interpolate(from: GeoPoint, to: GeoPoint, fraction: Double) = GeoPoint(
        from.latitudeDeg + (to.latitudeDeg - from.latitudeDeg) * fraction,
        from.longitudeDeg + (to.longitudeDeg - from.longitudeDeg) * fraction,
    )

    private fun key(column: Long, row: Long) = "v1:$column:$row"
}
