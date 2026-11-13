package com.pleos.explorer.domain.model

data class RegionVisitCandidate(
    val regionCode: String,
    val entrySequence: Long,
    val distanceM: Double = 0.0,
    val dwellMs: Long = 0,
    val qualified: Boolean = false,
)

data class RegionVisitSnapshot(
    val sessionId: String,
    val tripId: String? = null,
    val revision: Long = 0,
    val lastInputSequence: Long = -1,
    val nextEntrySequence: Long = 0,
    val candidate: RegionVisitCandidate? = null,
    val previous: ValidatedLocation? = null,
    val lastLocationElapsedMs: Long? = null,
)

sealed interface RegionInput {
    data class Location(
        val location: ValidatedLocation,
        val connectedDistanceM: Double,
        val newSegment: Boolean = false,
    ) : RegionInput
    data class Tick(val elapsedMs: Long, val timeMs: Long) : RegionInput
    data class TripStarted(val tripId: String) : RegionInput
    data object TripEnded : RegionInput
    data object ContinuityLost : RegionInput
}

data class VisitQualified(val regionCode: String, val tripId: String?, val entrySequence: Long, val qualifiedAtMs: Long)

enum class BadgeType { REGION, UNIVERSITY }

data class RegionBadge(
    val badgeId: String,
    val regionCode: String,
    val type: BadgeType,
    val firstTripId: String?,
    val acquiredAtMs: Long,
    val visitCount: Int,
)

data class TripRegionVisit(
    val id: String,
    val tripId: String,
    val regionCode: String,
    val entrySequence: Long,
    val qualifiedAtMs: Long,
)

enum class RewardKind { BADGE, TITLE, GHOST_RESULT }

data class PendingReward(
    val id: String,
    val deduplicationKey: String,
    val tripId: String?,
    val kind: RewardKind,
    val subjectId: String,
    val occurredAtMs: Long,
    val acknowledgedAtMs: Long? = null,
)

data class RegionVisitUpdate(
    val snapshot: RegionVisitSnapshot,
    val qualifiedVisits: List<VisitQualified> = emptyList(),
    val badges: Map<String, RegionBadge> = emptyMap(),
    val visits: List<TripRegionVisit> = emptyList(),
    val rewards: List<PendingReward> = emptyList(),
)
