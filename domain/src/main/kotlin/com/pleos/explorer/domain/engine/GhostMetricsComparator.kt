package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.geo.GeoDistance
import com.pleos.explorer.domain.model.DrivingMetrics
import com.pleos.explorer.domain.model.Efficiency
import com.pleos.explorer.domain.model.GhostCursor
import com.pleos.explorer.domain.model.GhostMetricsInput
import com.pleos.explorer.domain.model.GhostSection
import com.pleos.explorer.domain.model.GhostSegment
import com.pleos.explorer.domain.model.HistoricalTrack
import com.pleos.explorer.domain.model.MetricsDiff
import com.pleos.explorer.domain.model.MetricsSample
import com.pleos.explorer.domain.model.TrackPoint
import com.pleos.explorer.domain.model.ValidatedLocation
import kotlin.math.abs

internal class GhostMetricsComparator {
    private val collector = MetricsCollector()
    private val calculator = VehicleMetricsCalculator()

    private class SourcePath(val samples: List<MetricsSample>, val turnCoverage: Set<Long>) {
        val times = samples.mapIndexed { index, sample -> sample.position.elapsedMs to index }.toMap()
        val points = samples.mapIndexedNotNull { index, sample -> sample.position.pointId?.let { it to index } }.toMap()
        init {
            require(points.size == samples.count { it.position.pointId != null })
        }

        fun index(location: ValidatedLocation): Int? = times[location.elapsedMs]?.takeIf {
            samples[it].position.timeMs == location.timeMs && samples[it].position.point == location.point
        }
    }

    private data class Range(val path: SourcePath, val from: Int, val to: Int) {
        val samples: List<MetricsSample> get() = path.samples.subList(from, to + 1)
    }

    fun compare(segment: GhostSegment, input: GhostMetricsInput, history: List<HistoricalTrack>): MetricsDiff? {
        val current = sources(input.current, segment.tripId)
        val past = sources(input.past, segment.pastTripId)
        val originals = history.filter { it.tripId == segment.pastTripId }.flatMap { it.points }
            .groupBy { it.pathSegmentId }.mapValues { (_, points) -> points.sortedBy { it.sequence } }
        val ranges = mutableListOf<MutableList<GhostSection>>()
        for (section in segment.sections) {
            val length = GeoDistance.meters(section.currentFrom.point, section.currentTo.point)
            if (abs(length - section.distanceM) > 0.001) {
                return null
            }
            val previous = ranges.lastOrNull()?.last()
            val joined = previous != null && previous.currentTo.elapsedMs == section.currentFrom.elapsedMs &&
                previous.currentTo.timeMs == section.currentFrom.timeMs &&
                previous.currentTo.point == section.currentFrom.point &&
                (previous.pastTo == section.pastFrom ||
                    endpoint(previous.pastTo)?.let { it == endpoint(section.pastFrom) } == true)
            if (joined) ranges.last().add(section)
            else ranges.add(mutableListOf(section))
        }
        val currentRanges = mutableListOf<Range>()
        val pastRanges = mutableListOf<Range>()
        for (sections in ranges) {
            val first = sections.first()
            val last = sections.last()
            val from = endpoint(first.pastFrom) ?: return null
            val to = endpoint(last.pastTo) ?: return null
            if (from.pathSegmentId != to.pathSegmentId) return null
            val c = current.mapNotNull { path ->
                range(path, path.index(first.currentFrom), path.index(last.currentTo))
            }.singleOrNull() ?: return null
            val p = past.filter { it.samples.first().position.pathSegmentId == from.pathSegmentId }.mapNotNull { path ->
                range(path, path.points[from.id], path.points[to.id])
            }.singleOrNull() ?: return null
            if (c.samples.first().position.isPathStart == null || p.samples.first().position.isPathStart == null) {
                return null
            }
            for (section in sections) {
                val startIndex = c.path.index(section.currentFrom) ?: return null
                val endIndex = c.path.index(section.currentTo) ?: return null
                val length = c.path.samples[endIndex].position.distanceFromTripStartM -
                    c.path.samples[startIndex].position.distanceFromTripStartM
                if (abs(length - section.distanceM) > 0.001) return null
            }
            val points = originals[from.pathSegmentId] ?: return null
            val firstIndex = points.binarySearchBy(from.sequence) { it.sequence }
            val lastIndex = points.binarySearchBy(to.sequence) { it.sequence }
            if (firstIndex < 0 || lastIndex < firstIndex) return null
            if ((firstIndex..lastIndex).any { index ->
                    val sampleIndex = p.path.points[points[index].id]
                    sampleIndex == null || sampleIndex !in p.from..p.to ||
                        p.path.samples[sampleIndex].position.timeMs != points[index].timeMs ||
                        p.path.samples[sampleIndex].position.point != points[index].point
                }) return null
            currentRanges.add(c)
            pastRanges.add(p)
        }
        fun stats(ranges: List<Range>): List<DrivingMetrics>? = ranges.map {
            collector.summarize(it.path.samples, it.from, it.to, it.path.turnCoverage) ?: return null
        }
        val currentStats = stats(currentRanges) ?: return null
        val pastStats = stats(pastRanges) ?: return null
        fun delta(field: (DrivingMetrics) -> Int?): Int? {
            val a = total(currentStats, field) ?: return null
            val b = total(pastStats, field) ?: return null
            return (a - b).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        }
        val currentRate = rate(currentStats)
        val pastRate = rate(pastStats)
        val a = efficiency(currentRanges)
        val b = efficiency(pastRanges)
        val diff = MetricsDiff(delta { it.harshBrakes }, delta { it.harshSteers },
            if (currentRate != null && pastRate != null) currentRate - pastRate else null,
            if (a != null && b != null && a.unit == b.unit) Efficiency(a.value - b.value, a.unit) else null)
        return diff.takeIf { it.harshBrakesDelta != null || it.harshSteersDelta != null ||
            it.turnSignalRateDelta != null || it.efficiencyDelta != null }
    }

    private fun sources(samples: List<MetricsSample>, tripId: String): List<SourcePath> {
        require(samples.all { it.position.tripId == tripId })
        return collector.validate(samples, requireTripStart = false).groupBy { it.position.pathSegmentId }
            .values.map { SourcePath(it, collector.turnCoverage(it)) }
    }

    private fun range(path: SourcePath, from: Int?, to: Int?): Range? =
        if (from != null && to != null && from < to) Range(path, from, to) else null

    private fun endpoint(cursor: GhostCursor): TrackPoint? = when (cursor.fraction) {
        0.0 -> cursor.from
        1.0 -> cursor.to
        else -> null
    }

    private fun total(rows: List<DrivingMetrics>, field: (DrivingMetrics) -> Int?): Long? =
        rows.sumOf { field(it)?.toLong() ?: return null }

    private fun rate(rows: List<DrivingMetrics>): Double? {
        val turns = total(rows) { it.turnCount } ?: return null
        val signaled = total(rows) { it.signaledTurnCount } ?: return null
        return if (turns > 0) signaled.toDouble() / turns else null
    }

    private fun efficiency(ranges: List<Range>): Efficiency? = calculator.combinedEfficiency(
        ranges.map { collector.efficiencyReadings(it.samples) ?: return null })
}
