package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.geo.GeoDistance
import com.pleos.explorer.domain.geo.GhostCorridor
import com.pleos.explorer.domain.geo.GhostMatch
import com.pleos.explorer.domain.geo.GhostMatcher
import com.pleos.explorer.domain.geo.GridProjector
import com.pleos.explorer.domain.geo.PathSimplifier
import com.pleos.explorer.domain.model.DetectionPolicy
import com.pleos.explorer.domain.model.GeoPoint
import com.pleos.explorer.domain.model.GhostCursor
import com.pleos.explorer.domain.model.GhostPassage
import com.pleos.explorer.domain.model.GhostResult
import com.pleos.explorer.domain.model.GhostSection
import com.pleos.explorer.domain.model.GhostSegment
import com.pleos.explorer.domain.model.HistoricalTrack
import com.pleos.explorer.domain.model.PathSegment
import com.pleos.explorer.domain.model.TrackPoint
import com.pleos.explorer.domain.model.ValidatedLocation
import kotlin.math.abs

class GhostResultBuilder(
    private val matcher: GhostMatcher = GhostMatcher(),
    private val grid: GridProjector = GridProjector(),
    private val simplifier: PathSimplifier = PathSimplifier(),
) {
    fun requiredGridKeys(segments: List<GhostSegment>): Set<String> = segments.flatMap { segment ->
        segment.sections.flatMap { section ->
            grid.split(section.currentFrom.point, section.currentTo.point).flatMap { grid.neighbors(it.gridKey) }
        }
    }.toSet()

    fun build(segment: GhostSegment, history: List<HistoricalTrack>): GhostResult {
        require(segment.id.isNotBlank() && segment.tripId.isNotBlank() && segment.pastTripId.isNotBlank())
        require(segment.tripId != segment.pastTripId && segment.sections.isNotEmpty())
        require(segment.matchedDistanceM.isFinite() && segment.matchedDistanceM >= DetectionPolicy.GHOST_START_DISTANCE_M)
        require(abs(segment.sections.sumOf { it.distanceM } - segment.matchedDistanceM) <= 0.001)
        val source = history.filter { it.tripId != segment.tripId }
        matcher.validateHistory(source)
        val embedded = segment.sections.flatMap { listOf(it.pastFrom.from, it.pastFrom.to, it.pastTo.from, it.pastTo.to) }
        val tracks = (source + HistoricalTrack(segment.pastTripId, embedded)).groupBy { it.tripId }.map { (tripId, values) ->
            val points = values.flatMap { it.points }.groupBy { it.sequence }.map { (_, copies) ->
                require(copies.distinct().size == 1) { "Historical point changed during comparison" }
                copies.first()
            }.sortedBy { it.sequence }
            HistoricalTrack(tripId, points)
        }
        matcher.validateHistory(tracks)
        val indexed = tracks.map { it to index(it) }
        val currentPaths = mutableListOf<MutableList<GeoPoint>>()
        val pastPaths = mutableListOf<MutableList<GeoPoint>>()
        val passages = segment.sections.flatMapIndexed { index, section ->
            val length = GeoDistance.meters(section.currentFrom.point, section.currentTo.point)
            require(length > 0)
            require(section.distanceM.isFinite() && section.distanceM > 0 && section.distanceM <= length + 0.001)
            require(section.currentTo.elapsedMs > section.currentFrom.elapsedMs)
            require(section.pastFrom.key == section.pastTo.key && section.pastFrom.key.tripId == segment.pastTripId)
            if (index > 0) require(section.currentFrom.elapsedMs >= segment.sections[index - 1].currentTo.elapsedMs)
            val path = requireNotNull(matcher.path(section.pastFrom, section.pastTo, tracks)) {
                "Matched historical path is unavailable"
            }
            val joined = index > 0 && section.currentFrom.elapsedMs == segment.sections[index - 1].currentTo.elapsedMs
            if (!joined) {
                currentPaths.add(mutableListOf())
                pastPaths.add(mutableListOf())
            }
            currentPaths.last().addAll(listOf(section.currentFrom.point, section.currentTo.point))
            pastPaths.last().addAll(listOf(at(section.pastFrom)) + path.drop(1).dropLast(1).map { it.point } +
                at(section.pastTo))
            val keys = grid.split(section.currentFrom.point, section.currentTo.point)
                .flatMap { grid.neighbors(it.gridKey) }.toSet()
            val nearby = indexed.mapNotNull { (track, cells) ->
                val selected = keys.flatMap { cells[it].orEmpty() }.toSortedSet()
                if (selected.isEmpty()) null else track.copy(points = selected.map { track.points[it] })
            }
            passages(index, section, path, nearby)
        }
        fun display(paths: List<List<GeoPoint>>, prefix: String): List<PathSegment> = paths.mapIndexed { index, points ->
            PathSegment("$prefix:${segment.id}:$index", simplifier.simplify(points.distinctUntilChanged()))
        }
        return GhostResult(segment.id, segment.tripId, segment.pastTripId, segment.matchedDistanceM,
            passages.minOf { it.firstPassedAtMs }, passages.maxOf { it.previousLastPassedAtMs }, 0.0, null,
            display(currentPaths, "current"), display(pastPaths, "past"), passages)
    }

    private fun passages(index: Int, section: GhostSection, path: List<TrackPoint>,
        tracks: List<HistoricalTrack>): List<GhostPassage> {
        val a = grid.project(section.currentFrom.point)
        val b = grid.project(section.currentTo.point)
        val dx = b.x - a.x
        val dy = b.y - a.y
        val squared = dx * dx + dy * dy
        val edges = tracks.flatMap { track -> track.points.zipWithNext().filter { (from, to) ->
            from.pathSegmentId == to.pathSegmentId && to.sequence - from.sequence == 1L &&
                abs(from.point.longitudeDeg - to.point.longitudeDeg) <= 180
        }.map { it.first.point to it.second.point } }
        val boundaries = GhostCorridor.intervals(section.currentFrom.point, section.currentTo.point, edges,
            DetectionPolicy.GHOST_DISTANCE_M).flatMap { listOf(it.start, it.end) }
        val fractions = (listOf(0.0, 1.0) + boundaries + tracks.flatMap { it.points }.mapNotNull { point ->
            val p = grid.project(point.point)
            val fraction = ((p.x - a.x) * dx + (p.y - a.y) * dy) / squared
            fraction.takeIf { it > 1e-8 && it < 1 - 1e-8 &&
                GeoDistance.distanceToSegment(point.point, section.currentFrom.point, section.currentTo.point) <=
                DetectionPolicy.GHOST_DISTANCE_M }
        }).sorted().fold(mutableListOf<Double>()) { cuts, value ->
            if (cuts.isEmpty() || value - cuts.last() > 1e-8) cuts.add(value)
            cuts
        }
        val matches = mutableMapOf<Double, List<List<GhostMatch>>>()
        fun atFraction(fraction: Double): List<List<GhostMatch>> = matches.getOrPut(fraction) {
            runs(matcher.matchesValidated(at(section, fraction), tracks))
        }
        return fractions.zipWithNext().map { (start, end) ->
            val currentFrom = at(section, start)
            val currentTo = at(section, end)
            val length = GeoDistance.meters(currentFrom.point, currentTo.point)
            val times = mutableListOf(at(path, section.pastFrom, section.pastTo, start).timeMs,
                at(path, section.pastFrom, section.pastTo, end).timeMs)
            val from = atFraction(start)
            val to = atFraction(end)
            from.forEach { starts -> to.filter { it.first().cursor.key == starts.first().cursor.key }.forEach { ends ->
                val pair = starts.flatMap { a -> ends.mapNotNull { b ->
                    val advance = matcher.advance(a.cursor, b.cursor, tracks) ?: return@mapNotNull null
                    if (advance <= 0 || advance > length + 2 * DetectionPolicy.GHOST_DISTANCE_M ||
                        !matcher.covers(currentFrom, currentTo, a.cursor, b.cursor, tracks)) null
                    else Triple(a, b, abs(advance - length))
                } }.minWithOrNull(compareBy<Triple<GhostMatch, GhostMatch, Double>> { it.first.distanceM + it.second.distanceM }
                    .thenBy { it.third }.thenBy { it.first.cursor.from.sequence })
                pair?.let { times.add(it.first.cursor.timeMs); times.add(it.second.cursor.timeMs) }
            } }
            GhostPassage(index, start, end, section.distanceM * (end - start), minOf(currentFrom.timeMs, currentTo.timeMs),
                times.min(), times.max())
        }
    }

    private fun index(track: HistoricalTrack): Map<String, Set<Int>> {
        val cells = mutableMapOf<String, MutableSet<Int>>()
        track.points.forEachIndexed { index, point -> cells.getOrPut(point.gridKey) { mutableSetOf() }.add(index) }
        track.points.zipWithNext().forEachIndexed { index, (from, to) ->
            if (from.pathSegmentId == to.pathSegmentId && to.sequence - from.sequence == 1L &&
                abs(from.point.longitudeDeg - to.point.longitudeDeg) <= 180) {
                grid.split(from.point, to.point).forEach { section ->
                    cells.getOrPut(section.gridKey) { mutableSetOf() }.addAll(listOf(index, index + 1))
                }
            }
        }
        return cells
    }

    private fun at(section: GhostSection, fraction: Double): ValidatedLocation {
        val from = section.currentFrom
        val to = section.currentTo
        val bearing = from.bearingDeg?.let { a -> to.bearingDeg?.let { b ->
            val delta = (b - a + 540) % 360 - 180
            (a + delta * fraction + 360) % 360
        } }
        return ValidatedLocation(from.timeMs + ((to.timeMs - from.timeMs) * fraction).toLong(),
            from.elapsedMs + ((to.elapsedMs - from.elapsedMs) * fraction).toLong(),
            GeoPoint(from.point.latitudeDeg + (to.point.latitudeDeg - from.point.latitudeDeg) * fraction,
                from.point.longitudeDeg + (to.point.longitudeDeg - from.point.longitudeDeg) * fraction),
            null, bearing, maxOf(from.accuracyM, to.accuracyM))
    }

    private fun at(path: List<TrackPoint>, from: GhostCursor, to: GhostCursor, fraction: Double): GhostCursor {
        if (fraction == 0.0) return from
        if (fraction == 1.0) return to
        val edges = path.zipWithNext()
        val lengths = edges.mapIndexed { index, (a, b) ->
            GeoDistance.meters(a.point, b.point) *
                ((if (index == edges.lastIndex) to.fraction else 1.0) - (if (index == 0) from.fraction else 0.0))
        }
        var remaining = lengths.sum() * fraction
        edges.forEachIndexed { index, (a, b) ->
            if (lengths[index] > 0 && remaining <= lengths[index]) {
                val edgeLength = GeoDistance.meters(a.point, b.point)
                val position = (if (index == 0) from.fraction else 0.0) + remaining / edgeLength
                return GhostCursor(a, b, position.coerceIn(0.0, 1.0))
            }
            remaining -= lengths[index]
        }
        return to
    }

    private fun runs(matches: List<GhostMatch>): List<List<GhostMatch>> {
        val result = mutableListOf<MutableList<GhostMatch>>()
        matches.groupBy { it.cursor.key }.values.forEach { options ->
            var run: MutableList<GhostMatch>? = null
            options.sortedBy { it.cursor.from.sequence }.forEach { match ->
                if (run?.lastOrNull()?.cursor?.to?.sequence != match.cursor.from.sequence) {
                    run = mutableListOf()
                    result.add(requireNotNull(run))
                }
                requireNotNull(run).add(match)
            }
        }
        return result
    }

    private fun at(cursor: GhostCursor) = GeoPoint(
        cursor.from.point.latitudeDeg + (cursor.to.point.latitudeDeg - cursor.from.point.latitudeDeg) * cursor.fraction,
        cursor.from.point.longitudeDeg + (cursor.to.point.longitudeDeg - cursor.from.point.longitudeDeg) * cursor.fraction,
    )

    private fun List<GeoPoint>.distinctUntilChanged(): List<GeoPoint> = filterIndexed { index, point ->
        index == 0 || point != this[index - 1]
    }
}
