package com.pleos.explorer.domain.geo

import com.pleos.explorer.domain.model.GeoBounds
import com.pleos.explorer.domain.model.GeoPoint
import com.pleos.explorer.domain.model.RegionDefinition
import com.pleos.explorer.domain.model.RegionGeometry
import com.pleos.explorer.domain.port.RegionCatalog
import kotlin.math.abs
import kotlin.math.hypot

data class RegionSection(val regionCode: String?, val startFraction: Double, val endFraction: Double)

class RegionGeometryResolver(private val catalog: RegionCatalog) {
    fun locate(point: GeoPoint, previousRegionCode: String? = null): String? =
        select(point, catalog.findCandidates(point), previousRegionCode)

    fun split(from: GeoPoint, to: GeoPoint, previousRegionCode: String? = null): List<RegionSection> {
        require(abs(to.longitudeDeg - from.longitudeDeg) <= 180.0) { "Antimeridian region segment is unsupported" }
        val candidates = catalog.findCandidates(GeoBounds.between(from, to))
        val cuts = mutableListOf(0.0, 1.0)
        candidates.forEach { region -> region.geometry.polygons.forEach { polygon ->
            (listOf(polygon.outer) + polygon.holes).forEach { ring -> ring.zipWithNext().forEach { (a, b) ->
                cuts.addAll(RegionShapeMath.intersections(from, to, a, b))
            } }
        } }
        val ordered = cuts.sorted().fold(mutableListOf<Double>()) { result, value ->
            if (result.isEmpty() || value - result.last() > 1e-12) result.add(value)
            result
        }
        val result = mutableListOf<RegionSection>()
        var previous = previousRegionCode
        ordered.zipWithNext().forEach { (start, end) ->
            val midpoint = GeoPoint(from.latitudeDeg + (to.latitudeDeg - from.latitudeDeg) * (start + end) / 2,
                from.longitudeDeg + (to.longitudeDeg - from.longitudeDeg) * (start + end) / 2)
            val code = select(midpoint, candidates, previous)
            val last = result.lastOrNull()
            if (last != null && last.regionCode == code) {
                result[result.lastIndex] = last.copy(endFraction = end)
            } else result.add(RegionSection(code, start, end))
            previous = code
        }
        return result.toList()
    }

    private fun select(point: GeoPoint, candidates: List<RegionDefinition>, previous: String?): String? {
        val contained = candidates.map { it.code to RegionShapeMath.position(point, it.geometry) }
            .filter { it.second != RegionPosition.OUTSIDE }
        if (contained.any { it.second == RegionPosition.BOUNDARY } && contained.any { it.first == previous }) {
            return previous
        }
        return contained.minOfOrNull { it.first }
    }
}

internal enum class RegionPosition { OUTSIDE, INSIDE, BOUNDARY }

internal object RegionShapeMath {
    private const val EPSILON_M = 1e-6
    private val projection = GridProjector()
    private data class Edge(val index: Int, val from: GeoPoint, val to: GeoPoint) {
        val bbox: GeoBounds = GeoBounds.between(from, to)
    }

    fun position(point: GeoPoint, geometry: RegionGeometry): RegionPosition {
        var boundary = false
        geometry.polygons.forEach { polygon ->
            val outer = ringPosition(point, polygon.outer)
            if (outer == RegionPosition.OUTSIDE) return@forEach
            val holes = polygon.holes.map { ringPosition(point, it) }
            if (RegionPosition.INSIDE in holes) return@forEach
            if (outer == RegionPosition.INSIDE && RegionPosition.BOUNDARY !in holes) return RegionPosition.INSIDE
            boundary = true
        }
        return if (boundary) RegionPosition.BOUNDARY else RegionPosition.OUTSIDE
    }

    fun validate(geometry: RegionGeometry) {
        geometry.polygons.forEach { polygon ->
            (listOf(polygon.outer) + polygon.holes).forEach(::validateRing)
            polygon.holes.forEachIndexed { index, hole ->
                require(hole.dropLast(1).all { ringPosition(it, polygon.outer) == RegionPosition.INSIDE })
                require(!ringsIntersect(hole, polygon.outer)) { "Hole crosses outer ring" }
                polygon.holes.take(index).forEach { other ->
                    require(!ringsIntersect(hole, other) && ringPosition(hole.first(), other) == RegionPosition.OUTSIDE &&
                        ringPosition(other.first(), hole) == RegionPosition.OUTSIDE) { "Overlapping holes" }
                }
            }
        }
    }

    private fun validateRing(ring: List<GeoPoint>) {
        val edges = ring.zipWithNext().filter { (a, b) -> a != b }.mapIndexed { i, (a, b) -> Edge(i, a, b) }
        val index = BoundsIndex(edges) { it.bbox }
        edges.forEach { edge -> index.query(edge.bbox).forEach candidate@{ other ->
            if (other.index <= edge.index + 1 || edge.index == 0 && other.index == edges.lastIndex) return@candidate
            require(intersections(edge.from, edge.to, other.from, other.to).isEmpty()) { "Self-intersecting ring" }
        } }
    }

    private fun ringsIntersect(first: List<GeoPoint>, second: List<GeoPoint>): Boolean {
        val index = BoundsIndex(second.zipWithNext().mapIndexed { i, (a, b) -> Edge(i, a, b) }) { it.bbox }
        return first.zipWithNext().any { (a, b) -> index.query(GeoBounds.between(a, b)).any {
            intersections(a, b, it.from, it.to).isNotEmpty()
        } }
    }

    private fun ringPosition(point: GeoPoint, ring: List<GeoPoint>): RegionPosition {
        val p = projection.project(point)
        var inside = false
        ring.zipWithNext().forEach { (first, second) ->
            val a = projection.project(first)
            val b = projection.project(second)
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = hypot(dx, dy)
            if (length == 0.0) {
                if (hypot(p.x - a.x, p.y - a.y) <= EPSILON_M) return RegionPosition.BOUNDARY
            } else {
                val along = (p.x - a.x) * dx + (p.y - a.y) * dy
                if (abs(dx * (p.y - a.y) - dy * (p.x - a.x)) <= EPSILON_M * length &&
                    along >= -EPSILON_M * length && along <= length * length + EPSILON_M * length) {
                    return RegionPosition.BOUNDARY
                }
            }
            if ((a.y > p.y) != (b.y > p.y) && p.x < a.x + (p.y - a.y) * dx / dy) inside = !inside
        }
        return if (inside) RegionPosition.INSIDE else RegionPosition.OUTSIDE
    }

    fun intersections(from: GeoPoint, to: GeoPoint, edgeFrom: GeoPoint, edgeTo: GeoPoint): List<Double> {
        val a = projection.project(from)
        val b = projection.project(to)
        val c = projection.project(edgeFrom)
        val d = projection.project(edgeTo)
        val rx = b.x - a.x
        val ry = b.y - a.y
        val sx = d.x - c.x
        val sy = d.y - c.y
        val lengthSquared = rx * rx + ry * ry
        if (lengthSquared == 0.0) return emptyList()
        val cross = rx * sy - ry * sx
        val qx = c.x - a.x
        val qy = c.y - a.y
        if (abs(cross) > 1e-12 * hypot(rx, ry) * hypot(sx, sy)) {
            val t = (qx * sy - qy * sx) / cross
            val u = (qx * ry - qy * rx) / cross
            return if (t in -1e-12..1.000000000001 && u in -1e-12..1.000000000001) {
                listOf(t.coerceIn(0.0, 1.0))
            } else emptyList()
        }
        if (abs(qx * ry - qy * rx) > EPSILON_M * hypot(rx, ry)) return emptyList()
        return listOf((qx * rx + qy * ry) / lengthSquared,
            ((d.x - a.x) * rx + (d.y - a.y) * ry) / lengthSquared)
            .filter { it in 0.0..1.0 }
    }
}
