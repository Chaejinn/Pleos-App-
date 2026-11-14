package com.pleos.explorer.domain.geo

import com.pleos.explorer.domain.model.DetectionPolicy
import com.pleos.explorer.domain.model.GeoPoint
import com.pleos.explorer.domain.model.GhostCursor
import com.pleos.explorer.domain.model.HistoricalTrack
import com.pleos.explorer.domain.model.TrackPoint
import com.pleos.explorer.domain.model.ValidatedLocation
import kotlin.math.abs

data class GhostMatch(val cursor: GhostCursor, val distanceM: Double)

class GhostMatcher {
    private val grid = GridProjector()

    fun matches(location: ValidatedLocation, history: List<HistoricalTrack>): List<GhostMatch> {
        require(history.map { it.tripId }.distinct().size == history.size) { "Duplicate historical trip" }
        history.forEach(::validate)
        val bearing = location.bearingDeg ?: return emptyList()
        return history.flatMap { track -> track.points.zipWithNext().mapNotNull { (a, b) ->
            if (!connected(a, b) ||
                abs(b.point.longitudeDeg - a.point.longitudeDeg) > 180) return@mapNotNull null
            val fromBearing = a.bearingDeg ?: return@mapNotNull null
            val toBearing = b.bearingDeg ?: return@mapNotNull null
            if (angle(bearing, fromBearing) > DetectionPolicy.GHOST_BEARING_DEG ||
                angle(bearing, toBearing) > DetectionPolicy.GHOST_BEARING_DEG) return@mapNotNull null
            val fraction = fraction(location.point, a.point, b.point) ?: return@mapNotNull null
            val point = GeoPoint(a.point.latitudeDeg + (b.point.latitudeDeg - a.point.latitudeDeg) * fraction,
                a.point.longitudeDeg + (b.point.longitudeDeg - a.point.longitudeDeg) * fraction)
            val distance = GeoDistance.meters(location.point, point)
            if (distance <= DetectionPolicy.GHOST_DISTANCE_M) GhostMatch(GhostCursor(a, b, fraction), distance)
            else null
        } }
    }

    fun advance(from: GhostCursor, to: GhostCursor, history: List<HistoricalTrack>): Double? {
        val points = path(from, to, history) ?: return null
        val edges = points.zipWithNext()
        val lastEdge = edges.indexOfFirst { it.first.sequence == to.from.sequence }
        if (lastEdge < 0) return null
        val lengths = edges.map { GeoDistance.meters(it.first.point, it.second.point) }
        val distance = lengths.take(lastEdge).sum() + lengths[lastEdge] * to.fraction -
            lengths.first() * from.fraction
        return distance.takeIf { it >= 0.0 && it.isFinite() }
    }

    internal fun covers(currentFrom: ValidatedLocation, currentTo: ValidatedLocation,
        pastFrom: GhostCursor, pastTo: GhostCursor, history: List<HistoricalTrack>): Boolean {
        val points = path(pastFrom, pastTo, history) ?: return false
        val fromBearing = currentFrom.bearingDeg ?: return false
        val toBearing = currentTo.bearingDeg ?: return false
        val edges = points.zipWithNext().mapIndexedNotNull { index, (a, b) ->
            val bearings = listOf(a.bearingDeg, b.bearingDeg)
            if (bearings.any { it == null || minOf(angle(fromBearing, it), angle(toBearing, it)) >
                    DetectionPolicy.GHOST_BEARING_DEG }) return@mapIndexedNotNull null
            fun at(fraction: Double) = GeoPoint(
                a.point.latitudeDeg + (b.point.latitudeDeg - a.point.latitudeDeg) * fraction,
                a.point.longitudeDeg + (b.point.longitudeDeg - a.point.longitudeDeg) * fraction)
            at(if (index == 0) pastFrom.fraction else 0.0) to
                at(if (b.sequence == pastTo.to.sequence) pastTo.fraction else 1.0)
        }
        return GhostCorridor.covers(currentFrom.point, currentTo.point, edges, DetectionPolicy.GHOST_DISTANCE_M)
    }

    private fun path(from: GhostCursor, to: GhostCursor, history: List<HistoricalTrack>): List<TrackPoint>? {
        require(from.fraction.isFinite() && from.fraction in 0.0..1.0 &&
            to.fraction.isFinite() && to.fraction in 0.0..1.0)
        if (from.key != to.key || to.from.sequence < from.from.sequence ||
            to.from.sequence == from.from.sequence && to.fraction < from.fraction) return null
        val points = (history.filter { it.tripId == from.key.tripId }.flatMap { it.points } +
            listOf(from.from, from.to, to.from, to.to))
            .filter { it.sequence >= from.from.sequence && it.sequence <= to.to.sequence }
            .groupBy { it.sequence }.map { (_, values) ->
                require(values.distinct().size == 1) { "Historical point changed during matching" }
                values.first()
            }.sortedBy { it.sequence }
        val edges = points.zipWithNext()
        if (edges.isEmpty() || edges.any { (a, b) -> !connected(a, b) }) return null
        return points
    }

    private fun connected(a: TrackPoint, b: TrackPoint): Boolean = a.tripId == b.tripId &&
        a.pathSegmentId == b.pathSegmentId && b.sequence - a.sequence == 1L

    private fun validate(track: HistoricalTrack) {
        require(track.tripId.isNotBlank())
        require(track.points.zipWithNext().all { it.first.sequence < it.second.sequence })
        require(track.points.map { it.id }.distinct().size == track.points.size)
        track.points.forEach { p ->
            require(p.id.isNotBlank() && p.tripId == track.tripId && p.pathSegmentId.isNotBlank() &&
                p.sequence >= 0 && p.timeMs >= 0 && p.gridKey == grid.key(p.point) &&
                p.accuracyM.isFinite() && p.accuracyM in 0.0..DetectionPolicy.MAX_LOCATION_ACCURACY_M &&
                p.speedMps?.let { it.isFinite() && it >= 0 } != false &&
                p.bearingDeg?.let { it.isFinite() && it >= 0 && it < 360 } != false) { "Invalid historical point" }
        }
    }

    private fun fraction(point: GeoPoint, from: GeoPoint, to: GeoPoint): Double? {
        val p = grid.project(point)
        val a = grid.project(from)
        val b = grid.project(to)
        val dx = b.x - a.x
        val dy = b.y - a.y
        val squared = dx * dx + dy * dy
        if (squared == 0.0) return null
        return (((p.x - a.x) * dx + (p.y - a.y) * dy) / squared).coerceIn(0.0, 1.0)
    }

    private fun angle(a: Double, b: Double): Double = abs(a - b).let { minOf(it, 360 - it) }
}
