package com.pleos.explorer.domain.geo

import com.pleos.explorer.domain.model.DetectionPolicy
import com.pleos.explorer.domain.model.GhostSection
import com.pleos.explorer.domain.model.HistoricalTrack
import kotlin.math.abs
import kotlin.math.roundToLong

internal data class GhostVisitRange(
    val fromFraction: Double,
    val toFraction: Double,
    val tripId: String,
    val segmentId: String,
    val fromAtMs: Long,
    val toAtMs: Long,
) {
    fun timeAt(fraction: Double): Long = fromAtMs + ((toAtMs - fromAtMs) *
        ((fraction - fromFraction) / (toFraction - fromFraction)).coerceIn(0.0, 1.0)).roundToLong()
}

internal class GhostVisitMatcher {
    fun ranges(section: GhostSection, tracks: List<HistoricalTrack>): List<GhostVisitRange> {
        val plane = GhostPlane(section.currentFrom.point, section.currentTo.point)
        val current = plane.project(section.currentTo.point)
        return tracks.flatMap { track -> track.points.zipWithNext().flatMap edge@ { (from, to) ->
            if (from.pathSegmentId != to.pathSegmentId || to.sequence - from.sequence != 1L ||
                abs(from.point.longitudeDeg - to.point.longitudeDeg) > 180) return@edge emptyList()
            val fromBearing = from.bearingDeg ?: return@edge emptyList()
            val toBearing = to.bearingDeg ?: return@edge emptyList()
            val a = plane.project(from.point)
            val b = plane.project(to.point)
            val dx = b.x - a.x
            val dy = b.y - a.y
            val squared = dx * dx + dy * dy
            if (squared == 0.0) return@edge emptyList()
            val rate = (current.x * dx + current.y * dy) / squared
            if (rate <= 0.0) return@edge emptyList()
            val offset = -(a.x * dx + a.y * dy) / squared
            val geometry = GhostCorridor.intervals(section.currentFrom.point, section.currentTo.point,
                listOf(from.point to to.point), DetectionPolicy.GHOST_DISTANCE_M)
            val joined = mutableListOf<GhostCorridor.Interval>()
            geometry.forEach { range ->
                val last = joined.lastOrNull()
                if (last != null && range.start <= last.end + 1e-10) {
                    joined[joined.lastIndex] = GhostCorridor.Interval(last.start, maxOf(last.end, range.end))
                } else joined.add(range)
            }
            val headings = headings(section, fromBearing, toBearing)
            joined.flatMap { geometryRange -> headings.mapNotNull { heading ->
                val start = maxOf(geometryRange.start, heading.start, -offset / rate, 0.0)
                val end = minOf(geometryRange.end, heading.end, (1 - offset) / rate, 1.0)
                if (end - start <= 1e-10) null else {
                    fun timeAt(fraction: Double): Long = from.timeMs + ((to.timeMs - from.timeMs) *
                        (rate * fraction + offset).coerceIn(0.0, 1.0)).roundToLong()
                    GhostVisitRange(start, end, track.tripId, from.pathSegmentId, timeAt(start), timeAt(end))
                }
            } }
        } }
    }

    private fun headings(section: GhostSection, first: Double, second: Double): List<GhostCorridor.Interval> {
        val from = section.currentFrom.bearingDeg ?: return emptyList()
        val to = section.currentTo.bearingDeg ?: return emptyList()
        val delta = (to - from + 540) % 360 - 180
        val cuts = mutableSetOf(0.0, 1.0)
        if (delta != 0.0) listOf(first, second).forEach { bearing ->
            (-2..2).forEach { turn -> listOf(-1, 1).forEach { direction ->
                val cut = (bearing + turn * 360 + direction * DetectionPolicy.GHOST_BEARING_DEG - from) / delta
                if (cut in 0.0..1.0) cuts.add(cut)
            } }
        }
        return cuts.sorted().zipWithNext().mapNotNull { (start, end) ->
            val bearing = (from + delta * ((start + end) / 2) + 360) % 360
            fun angle(other: Double) = abs(bearing - other).let { minOf(it, 360 - it) }
            if (angle(first) <= DetectionPolicy.GHOST_BEARING_DEG && angle(second) <= DetectionPolicy.GHOST_BEARING_DEG) {
                GhostCorridor.Interval(start, end)
            } else null
        }
    }
}
