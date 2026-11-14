package com.pleos.explorer.domain.model

enum class EfficiencyUnit { KM_PER_L, KM_PER_KWH }

data class Efficiency(val value: Double, val unit: EfficiencyUnit)

data class MetricsDiff(
    val harshBrakesDelta: Int?,
    val harshSteersDelta: Int?,
    val turnSignalRateDelta: Double?,
    val efficiencyDelta: Efficiency?,
)

data class GhostPassage(
    val sectionIndex: Int,
    val fromFraction: Double,
    val toFraction: Double,
    val distanceM: Double,
    val currentPassedAtMs: Long,
    val firstPassedAtMs: Long,
    val previousLastPassedAtMs: Long,
    val visits: List<GhostPassageVisit> = emptyList(),
) {
    fun revisitedDistanceM(minAgeMs: Long): Double {
        require(minAgeMs >= 0 && distanceM.isFinite() && distanceM >= 0)
        if (visits.isEmpty()) return 0.0
        visits.forEach { visit ->
            require(visit.currentFromAtMs >= 0 && visit.currentToAtMs >= 0 &&
                visit.pastFromAtMs >= 0 && visit.pastToAtMs >= 0)
            require(visit.currentFromAtMs == visits.first().currentFromAtMs &&
                visit.currentToAtMs == visits.first().currentToAtMs)
        }
        var lower = 0.0
        var upper = 1.0
        visits.forEach { visit ->
            val fromAge = (visit.currentFromAtMs - visit.pastFromAtMs).toDouble()
            val toAge = (visit.currentToAtMs - visit.pastToAtMs).toDouble()
            val limit = minAgeMs.toDouble()
            if (fromAge < limit && toAge < limit) return 0.0
            if (fromAge < limit) lower = maxOf(lower, (limit - fromAge) / (toAge - fromAge))
            if (toAge < limit) upper = minOf(upper, (limit - fromAge) / (toAge - fromAge))
        }
        return distanceM * (upper - lower).coerceIn(0.0, 1.0)
    }
}

data class GhostPassageVisit(
    val pastTripId: String?,
    val pathSegmentId: String,
    val currentFromAtMs: Long,
    val currentToAtMs: Long,
    val pastFromAtMs: Long,
    val pastToAtMs: Long,
)

data class GhostResult(
    val id: String,
    val tripId: String,
    val pastTripId: String?,
    val matchedDistanceM: Double,
    val firstPassedAtMs: Long,
    val previousLastPassedAtMs: Long,
    val newRoadM: Double,
    val metricsDiff: MetricsDiff?,
    val currentPath: List<PathSegment>,
    val pastPath: List<PathSegment>,
    val passages: List<GhostPassage>,
    val finalized: Boolean = false,
)

data class GhostComparisonInput(val recording: TrackUpdate, val ghost: GhostUpdate)

data class GhostComparisonAnchor(
    val id: String,
    val firstElapsedMs: Long,
    val firstNewRoadM: Double,
    val lastMatchedElapsedMs: Long,
    val lastMatchedNewRoadM: Double,
)

data class PendingGhostResult(val result: GhostResult, val fromNewRoadM: Double)

data class GhostComparisonSnapshot(
    val tripId: String,
    val sessionId: String,
    val revision: Long = 0,
    val lastInputSequence: Long = -1,
    val recordingRevision: Long = 0,
    val ghostRevision: Long = 0,
    val newRoadM: Double = 0.0,
    val candidateNewRoadM: Map<Long, Double> = emptyMap(),
    val active: GhostComparisonAnchor? = null,
    val pending: PendingGhostResult? = null,
    val finished: Boolean = false,
)

data class GhostComparisonUpdate(
    val snapshot: GhostComparisonSnapshot,
    val results: List<GhostResult> = emptyList(),
    val rewards: List<PendingReward> = emptyList(),
)
