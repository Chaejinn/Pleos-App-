package com.pleos.explorer.domain.model

data class AchievementTitle(
    val titleId: String,
    val conditionText: String,
    val acquiredAtMs: Long,
    val tripId: String?,
)

sealed interface AchievementFact {
    data class CoastEntered(val visit: CoastVisit) : AchievementFact
    data class BadgeGranted(val badgeId: String, val tripId: String?, val atMs: Long) : AchievementFact
    data class GhostFinished(val result: GhostResult, val atMs: Long) : AchievementFact
    data class TripFinished(val tripId: String, val atMs: Long, val newRoadM: Double) : AchievementFact {
        companion object {
            fun from(update: TrackUpdate): TripFinished? {
                val state = update.snapshot
                require(state.status != TripStatus.ACTIVE) { "Recording has not finished" }
                if (state.status == TripStatus.DISCARDED) return null
                val trip = state.trip
                val endedAtMs = requireNotNull(trip.endedAtMs)
                require(trip.id.isNotBlank() && trip.startedAtMs >= 0 && endedAtMs >= trip.startedAtMs)
                require(trip.distanceM.isFinite() && trip.distanceM >= DetectionPolicy.MIN_TRIP_DISTANCE_M)
                require(update.roads.all { (key, road) ->
                    key.isNotBlank() && key == road.gridKey && road.firstExploredDistanceM.isFinite() &&
                        road.firstExploredDistanceM >= 0 && (road.firstTripId == null || road.firstTripId.isNotBlank())
                })
                val distance = update.roads.values.filter { it.firstTripId == trip.id }.sumOf { it.firstExploredDistanceM }
                require(distance.isFinite() && distance <= trip.distanceM + 0.001)
                return TripFinished(trip.id, endedAtMs, distance)
            }
        }
    }
}

data class AchievementProgress(
    val regionBadgeCount: Int = 0,
    val acquiredTitleIds: Set<String> = emptySet(),
    val newRoadDistanceM: Double = 0.0,
    val coastVisitsByDate: Map<String, Map<Coast, CoastVisit>> = emptyMap(),
) {
    fun recordCoastVisit(visit: CoastVisit): AchievementProgress {
        if ("COAST_TO_COAST" in acquiredTitleIds) return this
        val visits = coastVisitsByDate[visit.date].orEmpty()
        val previous = visits[visit.coast]
        if (previous != null && previous.atMs <= visit.atMs) return this
        return copy(coastVisitsByDate = coastVisitsByDate + (visit.date to (visits + (visit.coast to visit))))
    }
}

data class TitleQualified(
    val titleId: String,
    val tripId: String?,
    val acquiredAtMs: Long,
    val conditionText: String,
)

data class TitleSnapshot(val revision: Long = 0, val progress: AchievementProgress = AchievementProgress())

data class TitleUpdate(
    val snapshot: TitleSnapshot,
    val titles: List<AchievementTitle> = emptyList(),
    val rewards: List<PendingReward> = emptyList(),
)
