package com.pleos.explorer.domain.model

import kotlin.math.abs

data class GeoBounds(
    val westDeg: Double,
    val southDeg: Double,
    val eastDeg: Double,
    val northDeg: Double,
) {
    init {
        require(westDeg.isFinite() && eastDeg.isFinite() && westDeg in -180.0..180.0 && eastDeg in -180.0..180.0)
        require(southDeg.isFinite() && northDeg.isFinite() && southDeg in -90.0..90.0 && northDeg in -90.0..90.0)
        require(westDeg <= eastDeg && southDeg <= northDeg)
    }

    fun contains(point: GeoPoint): Boolean = point.longitudeDeg in westDeg..eastDeg &&
        point.latitudeDeg in southDeg..northDeg

    fun intersects(other: GeoBounds): Boolean = westDeg <= other.eastDeg && eastDeg >= other.westDeg &&
        southDeg <= other.northDeg && northDeg >= other.southDeg

    companion object {
        fun between(from: GeoPoint, to: GeoPoint): GeoBounds = enclosing(listOf(from, to))

        fun enclosing(points: List<GeoPoint>): GeoBounds {
            require(points.isNotEmpty())
            points.forEach(::requireRegionPoint)
            return GeoBounds(points.minOf { it.longitudeDeg }, points.minOf { it.latitudeDeg },
                points.maxOf { it.longitudeDeg }, points.maxOf { it.latitudeDeg })
        }
    }
}

data class RegionPolygon(val outer: List<GeoPoint>, val holes: List<List<GeoPoint>> = emptyList()) {
    init {
        (listOf(outer) + holes).forEach { ring ->
            require(ring.size >= 4 && ring.first() == ring.last() && ring.distinct().size >= 3)
            ring.forEach(::requireRegionPoint)
            require(ring.zipWithNext().all { abs(it.second.longitudeDeg - it.first.longitudeDeg) <= 180 })
            val origin = ring.first()
            val area = ring.zipWithNext().sumOf { (a, b) ->
                (a.longitudeDeg - origin.longitudeDeg) * (b.latitudeDeg - origin.latitudeDeg) -
                    (b.longitudeDeg - origin.longitudeDeg) * (a.latitudeDeg - origin.latitudeDeg)
            }
            require(area != 0.0)
        }
    }
}

data class RegionGeometry(val polygons: List<RegionPolygon>) {
    init { require(polygons.isNotEmpty()) }
}

data class RegionDefinition(
    val code: String,
    val name: String,
    val provinceCode: String,
    val representativePoint: GeoPoint,
    val geometry: RegionGeometry,
) {
    val bbox: GeoBounds = GeoBounds.enclosing(geometry.polygons.flatMap { it.outer })

    init {
        require(code.isNotBlank() && name.isNotBlank() && provinceCode.isNotBlank())
        requireRegionPoint(representativePoint)
    }
}

internal fun requireRegionPoint(point: GeoPoint) {
    require(point.latitudeDeg.isFinite() && point.latitudeDeg in -90.0..90.0 &&
        point.longitudeDeg.isFinite() && point.longitudeDeg in -180.0..180.0)
}
