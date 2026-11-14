package com.pleos.explorer.domain.geo

import com.pleos.explorer.domain.model.GeoPoint
import kotlin.math.sqrt

internal object GhostCorridor {
    internal data class Interval(val start: Double, val end: Double)

    fun covers(from: GeoPoint, to: GeoPoint, edges: List<Pair<GeoPoint, GeoPoint>>, radiusM: Double): Boolean {
        if (from == to) return true
        var covered = 0.0
        intervals(from, to, edges, radiusM).forEach { range ->
            if (range.start > covered + 1e-10) return false
            covered = maxOf(covered, range.end)
            if (covered >= 1.0 - 1e-10) return true
        }
        return false
    }

    fun intervals(from: GeoPoint, to: GeoPoint, edges: List<Pair<GeoPoint, GeoPoint>>, radiusM: Double): List<Interval> {
        val plane = GhostPlane(from, to)
        val end = plane.project(to)
        val lengthSquared = end.x * end.x + end.y * end.y
        if (lengthSquared == 0.0) return listOf(Interval(0.0, 1.0))
        return edges.flatMap { (first, second) ->
            val a = plane.project(first)
            val b = plane.project(second)
            val result = mutableListOf<Interval>()
            listOf(a, b).forEach { center ->
                val linear = -2 * (end.x * center.x + end.y * center.y)
                val constant = center.x * center.x + center.y * center.y - radiusM * radiusM
                val discriminant = linear * linear - 4 * lengthSquared * constant
                if (discriminant >= 0) {
                    val root = sqrt(discriminant)
                    interval((-linear - root) / (2 * lengthSquared),
                        (-linear + root) / (2 * lengthSquared))?.let(result::add)
                }
            }
            val dx = b.x - a.x
            val dy = b.y - a.y
            val edgeLengthSquared = dx * dx + dy * dy
            if (edgeLengthSquared > 0.0) {
                val along = restrict(Interval(0.0, 1.0), (end.x * dx + end.y * dy) / edgeLengthSquared,
                    -(a.x * dx + a.y * dy) / edgeLengthSquared, 0.0, 1.0)
                val edgeLength = sqrt(edgeLengthSquared)
                along?.let { restrict(it, (dx * end.y - dy * end.x) / edgeLength,
                    (dy * a.x - dx * a.y) / edgeLength, -radiusM, radiusM) }?.let(result::add)
            }
            result
        }.sortedBy { it.start }
    }

    private fun interval(start: Double, end: Double): Interval? {
        val lower = maxOf(0.0, start)
        val upper = minOf(1.0, end)
        return if (lower <= upper) Interval(lower, upper) else null
    }

    private fun restrict(range: Interval, rate: Double, offset: Double, min: Double, max: Double): Interval? {
        if (rate == 0.0) return range.takeIf { offset in min..max }
        val a = (min - offset) / rate
        val b = (max - offset) / rate
        return interval(maxOf(range.start, minOf(a, b)), minOf(range.end, maxOf(a, b)))
    }
}
