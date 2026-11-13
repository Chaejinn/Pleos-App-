package com.pleos.explorer.domain.geo

import com.pleos.explorer.domain.model.DetectionPolicy
import com.pleos.explorer.domain.model.GeoPoint
import java.util.ArrayDeque

class PathSimplifier {
    fun simplify(points: List<GeoPoint>, toleranceM: Double = DetectionPolicy.PATH_TOLERANCE_M): List<GeoPoint> {
        require(toleranceM.isFinite() && toleranceM >= 0)
        if (points.size <= 2) return points.toList()
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val pending = ArrayDeque<Pair<Int, Int>>()
        pending.add(0 to points.lastIndex)
        while (pending.isNotEmpty()) {
            val (first, last) = pending.removeLast()
            var furthest = -1
            var maxDistance = toleranceM
            for (index in first + 1 until last) {
                val distance = GeoDistance.distanceToSegment(points[index], points[first], points[last])
                if (distance > maxDistance) {
                    furthest = index
                    maxDistance = distance
                }
            }
            if (furthest >= 0) {
                keep[furthest] = true
                pending.add(first to furthest)
                pending.add(furthest to last)
            }
        }
        return points.filterIndexed { index, _ -> keep[index] }
    }
}
