package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.geo.GeoDistance
import com.pleos.explorer.domain.geo.GhostMatcher
import com.pleos.explorer.domain.geo.GhostVisitMatcher
import com.pleos.explorer.domain.geo.GhostVisitRange
import com.pleos.explorer.domain.geo.GridProjector
import com.pleos.explorer.domain.geo.PathSimplifier
import com.pleos.explorer.domain.model.DetectionPolicy
import com.pleos.explorer.domain.model.GeoPoint
import com.pleos.explorer.domain.model.GhostCursor
import com.pleos.explorer.domain.model.GhostPassage
import com.pleos.explorer.domain.model.GhostPassageVisit
import com.pleos.explorer.domain.model.GhostResult
import com.pleos.explorer.domain.model.GhostSection
import com.pleos.explorer.domain.model.GhostSegment
import com.pleos.explorer.domain.model.HistoricalTrack
import com.pleos.explorer.domain.model.PathSegment
import com.pleos.explorer.domain.model.TrackPoint
import kotlin.math.abs
import kotlin.math.roundToLong

class GhostResultBuilder(
    private val matcher: GhostMatcher = GhostMatcher(),
    private val grid: GridProjector = GridProjector(),
    private val simplifier: PathSimplifier = PathSimplifier(),
) {
    private val visitMatcher = GhostVisitMatcher()

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
        val ranges = baseline(section, path) + visitMatcher.ranges(section, tracks)
        val boundaries = ranges.flatMap { listOf(it.fromFraction, it.toFraction) }
            .filter { it > 1e-10 && it < 1 - 1e-10 }
        val fractions = (listOf(0.0, 1.0) + boundaries).sorted().fold(mutableListOf<Double>()) { cuts, value ->
            if (cuts.isEmpty() || value - cuts.last() > 1e-10) cuts.add(value)
            cuts
        }
        return fractions.zipWithNext().map { (start, end) ->
            val currentFromAtMs = timeAt(section, start)
            val currentToAtMs = timeAt(section, end)
            val middle = (start + end) / 2
            val visits = ranges.filter { middle >= it.fromFraction && middle <= it.toFraction }.map { range ->
                GhostPassageVisit(range.tripId, range.segmentId, currentFromAtMs, currentToAtMs,
                    range.timeAt(start), range.timeAt(end))
            }.distinct()
            check(visits.isNotEmpty())
            GhostPassage(index, start, end, section.distanceM * (end - start), minOf(currentFromAtMs, currentToAtMs),
                visits.minOf { minOf(it.pastFromAtMs, it.pastToAtMs) },
                visits.maxOf { maxOf(it.pastFromAtMs, it.pastToAtMs) }, visits)
        }
    }

    private fun baseline(section: GhostSection, path: List<TrackPoint>): List<GhostVisitRange> {
        val edges = path.zipWithNext()
        val bounds = edges.indices.map { index ->
            (if (index == 0) section.pastFrom.fraction else 0.0) to
                (if (index == edges.lastIndex) section.pastTo.fraction else 1.0)
        }
        val lengths = edges.mapIndexed { index, (a, b) ->
            GeoDistance.meters(a.point, b.point) * (bounds[index].second - bounds[index].first)
        }
        val total = lengths.sum()
        require(total > 0)
        var distance = 0.0
        return edges.mapIndexedNotNull { index, (a, b) ->
            val start = distance / total
            distance += lengths[index]
            val end = distance / total
            if (end <= start) null else {
                fun timeAt(fraction: Double) = a.timeMs + ((b.timeMs - a.timeMs) * fraction).roundToLong()
                GhostVisitRange(start, end, a.tripId, a.pathSegmentId,
                    timeAt(bounds[index].first), timeAt(bounds[index].second))
            }
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

    private fun timeAt(section: GhostSection, fraction: Double): Long = section.currentFrom.timeMs +
        ((section.currentTo.timeMs - section.currentFrom.timeMs) * fraction).roundToLong()

    private fun at(cursor: GhostCursor) = GeoPoint(
        cursor.from.point.latitudeDeg + (cursor.to.point.latitudeDeg - cursor.from.point.latitudeDeg) * cursor.fraction,
        cursor.from.point.longitudeDeg + (cursor.to.point.longitudeDeg - cursor.from.point.longitudeDeg) * cursor.fraction,
    )

    private fun List<GeoPoint>.distinctUntilChanged(): List<GeoPoint> = filterIndexed { index, point ->
        index == 0 || point != this[index - 1]
    }
}
