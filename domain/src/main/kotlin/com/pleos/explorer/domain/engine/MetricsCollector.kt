package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.model.DetectionPolicy
import com.pleos.explorer.domain.model.DrivingMetrics
import com.pleos.explorer.domain.model.Efficiency
import com.pleos.explorer.domain.model.EfficiencySample
import com.pleos.explorer.domain.model.MetricsSample
import com.pleos.explorer.domain.model.TrackPosition
import com.pleos.explorer.domain.model.TurnSignalUsage
import java.util.UUID
import kotlin.math.roundToLong

class MetricsCollector(private val calculator: VehicleMetricsCalculator = VehicleMetricsCalculator()) {
    fun collect(samples: List<MetricsSample>, finished: Boolean = false): List<DrivingMetrics> {
        val ordered = validate(samples, requireTripStart = true)
        if (ordered.size < 2) return emptyList()
        val rows = mutableListOf<DrivingMetrics>()
        val paths = ordered.groupBy { it.position.pathSegmentId }.values.toList()
        for ((pathIndex, path) in paths.withIndex()) {
            var start = path.first().position
            val last = path.last().position
            val closed = finished || pathIndex < paths.lastIndex
            val turnCoverage = turnCoverage(path)
            var fromIndex = 0
            var toIndex = 0
            require((last.distanceFromTripStartM - start.distanceFromTripStartM) /
                DetectionPolicy.METRICS_DISTANCE_M <= Int.MAX_VALUE - rows.size)
            while (last.distanceFromTripStartM - start.distanceFromTripStartM >= DetectionPolicy.METRICS_DISTANCE_M) {
                val distance = start.distanceFromTripStartM + DetectionPolicy.METRICS_DISTANCE_M
                check(distance > start.distanceFromTripStartM)
                while (path[toIndex].position.distanceFromTripStartM < distance) toIndex++
                if (closed && distance == last.distanceFromTripStartM) toIndex = path.lastIndex
                val right = path[toIndex].position
                val end = if (right.distanceFromTripStartM == distance) right
                    else boundary(path[toIndex - 1].position, right, distance)
                rows.add(aggregate(path.subList(fromIndex, toIndex + 1), start, end, rows.size,
                    start == path.first().position, turnCoverage))
                start = end
                fromIndex = if (right == end) toIndex else toIndex - 1
            }
            if (closed && last.distanceFromTripStartM > start.distanceFromTripStartM) {
                rows.add(aggregate(path.subList(fromIndex, path.size), start, last, rows.size,
                    start == path.first().position, turnCoverage))
            }
        }
        return rows.toList()
    }

    internal fun validate(samples: List<MetricsSample>, requireTripStart: Boolean): List<MetricsSample> {
        val result = mutableListOf<MetricsSample>()
        val paths = mutableSetOf<String>()
        for (sample in samples) {
            val previous = result.lastOrNull()
            if (sample == previous) continue
            val current = sample.position
            if (previous == null && requireTripStart) require(current.distanceFromTripStartM == 0.0)
            if (previous != null) {
                val prior = previous.position
                require(current.tripId == prior.tripId)
                require(current.distanceFromTripStartM >= prior.distanceFromTripStartM)
                if (current.pathSegmentId != prior.pathSegmentId) {
                    require(current.pathSegmentId !in paths)
                    require(current.distanceFromTripStartM == prior.distanceFromTripStartM)
                } else {
                    require(current.elapsedMs > prior.elapsedMs)
                    require(current.isPathStart != true)
                }
            }
            paths.add(current.pathSegmentId)
            result.add(sample)
        }
        return result
    }

    internal fun summarize(
        path: List<MetricsSample>, fromIndex: Int, toIndex: Int, turnCoverage: Set<Long>,
    ): DrivingMetrics? {
        if (fromIndex >= toIndex) return null
        val start = path[fromIndex].position
        val end = path[toIndex].position
        if (end.distanceFromTripStartM <= start.distanceFromTripStartM) return null
        return aggregate(path.subList(fromIndex, toIndex + 1), start, end, 0, start.isPathStart == true, turnCoverage)
    }

    private fun boundary(left: TrackPosition, right: TrackPosition, distanceM: Double): TrackPosition {
        val fraction = (distanceM - left.distanceFromTripStartM) /
            (right.distanceFromTripStartM - left.distanceFromTripStartM)
        val elapsedMs = left.elapsedMs + ((right.elapsedMs - left.elapsedMs) * fraction).roundToLong()
        return TrackPosition(left.tripId, left.pathSegmentId, null, distanceM, elapsedMs)
    }

    private fun aggregate(
        path: List<MetricsSample>,
        start: TrackPosition,
        end: TrackPosition,
        index: Int,
        includeStart: Boolean,
        turnCoverage: Set<Long>,
    ): DrivingMetrics {
        val intervals = path.zipWithNext().filter { (left, right) ->
            left.position.before(end) && start.before(right.position)
        }
        val events = path.filter {
            (start.before(it.position) || includeStart && it.position == start) && !end.before(it.position)
        }
        val continuous = intervals.isNotEmpty() && intervals.all { (left, right) ->
            right.position.elapsedMs - left.position.elapsedMs <= DetectionPolicy.METRICS_SAMPLE_GAP_MS
        }
        val intervalEnds = intervals.map { it.second }
        val brakeInputs = intervalEnds + events.filter { it.harshBrake?.started == true }
        val brakesKnown = continuous && brakeInputs.all { right ->
            val update = right.harshBrake
            update?.decelerationMps2?.isFinite() == true &&
                update.state.previous?.observedAtElapsedMs == right.position.elapsedMs &&
                update.state.previous.value != null
        }
        val steerInputs = intervalEnds + events.filter { it.harshSteer?.started == true }
        val steersKnown = continuous && steerInputs.all { right ->
            val update = right.harshSteer
            update?.angularVelocityDegPerSec?.isFinite() == true &&
                update.state.previous?.observedAtElapsedMs == right.position.elapsedMs &&
                update.state.previous.value != null
        }
        val turnsKnown = continuous && intervals.all { (_, right) -> right.position.elapsedMs in turnCoverage }
        val usage = events.mapNotNull { it.turnSignal?.turn }.fold(TurnSignalUsage()) { usage, turn ->
            usage.record(turn.signaled)
        }
        val key = "pleos.metrics.v1:${start.tripId}:$index"
        val id = UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
        return DrivingMetrics(id, start.tripId, index, start.pointId, start.pathSegmentId,
            start.distanceFromTripStartM, end.distanceFromTripStartM, start.elapsedMs, end.elapsedMs,
            if (brakesKnown) events.count { it.harshBrake?.started == true } else null,
            if (steersKnown) events.count { it.harshSteer?.started == true } else null,
            if (turnsKnown) usage.rate else null, efficiency(path, start, end),
            if (turnsKnown) usage.turnCount else null,
            if (turnsKnown && usage.unknownTurnCount == 0) usage.signaledTurnCount else null)
    }

    private fun TrackPosition.before(other: TrackPosition): Boolean =
        distanceFromTripStartM < other.distanceFromTripStartM ||
            distanceFromTripStartM == other.distanceFromTripStartM && elapsedMs < other.elapsedMs

    internal fun turnCoverage(path: List<MetricsSample>): Set<Long> {
        fun observed(sample: MetricsSample): Boolean {
            val update = sample.turnSignal ?: return false
            val angle = update.state.angle ?: return false
            return angle.value?.isFinite() == true && angle.observedAtElapsedMs == sample.position.elapsedMs &&
                (update.turn == null || update.turn.elapsedMs == sample.position.elapsedMs)
        }
        fun baseline(sample: MetricsSample): Boolean = observed(sample) &&
            (sample.turnSignal?.state?.armed == true || sample.turnSignal?.turn != null)

        val covered = mutableSetOf<Long>()
        val first = path.first()
        var trusted = observed(first) &&
            (first.turnSignal?.state?.usage?.hasUnobservedSteering == false || baseline(first))
        for ((left, right) in path.zipWithNext()) {
            val continuous = observed(left) && observed(right) &&
                right.position.elapsedMs - left.position.elapsedMs <= DetectionPolicy.METRICS_SAMPLE_GAP_MS
            val lost = left.turnSignal?.state?.usage?.hasUnobservedSteering == false &&
                right.turnSignal?.state?.usage?.hasUnobservedSteering == true
            if (trusted && continuous && !lost) covered.add(right.position.elapsedMs)
            trusted = trusted && continuous && !lost || baseline(right)
        }
        return covered.toSet()
    }

    private fun efficiency(path: List<MetricsSample>, start: TrackPosition, end: TrackPosition): Efficiency? {
        val samples = path.filter { it.position.elapsedMs in start.elapsedMs..end.elapsedMs }
        if (samples.firstOrNull()?.position != start || samples.lastOrNull()?.position != end) return null
        return calculator.averageEfficiency(efficiencyReadings(samples) ?: return null)
    }

    internal fun efficiencyReadings(samples: List<MetricsSample>): List<EfficiencySample>? =
        samples.map { sample ->
            val reading = sample.efficiency ?: return null
            val position = sample.position
            if (reading.distanceM.observedAtElapsedMs != position.elapsedMs ||
                reading.consumption.observedAtElapsedMs != position.elapsedMs ||
                reading.distanceM.value != position.distanceFromTripStartM) return null
            reading
        }
}
