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
