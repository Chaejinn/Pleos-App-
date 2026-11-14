package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.model.AchievementFact
import com.pleos.explorer.domain.model.AchievementProgress
import com.pleos.explorer.domain.model.DetectionPolicy
import com.pleos.explorer.domain.model.GhostResult
import com.pleos.explorer.domain.model.TitleQualified
import kotlin.math.abs

class TitleEvaluator {
    fun evaluate(fact: AchievementFact, progress: AchievementProgress): List<TitleQualified> {
        require(progress.regionBadgeCount >= 0 && progress.acquiredTitleIds.none { it.isBlank() })
        require(progress.newRoadDistanceM.isFinite() && progress.newRoadDistanceM >= 0)
        val proposed = when (fact) {
            is AchievementFact.BadgeGranted -> {
                val parts = fact.badgeId.split(':', limit = 2)
                require(parts.size == 2 && parts[0] in setOf("REGION", "UNIVERSITY") && parts[1].isNotBlank())
                require(fact.atMs >= 0 && (fact.tripId == null || fact.tripId.isNotBlank()))
                if (parts[0] == "REGION" && progress.regionBadgeCount >= DetectionPolicy.TITLE_REGION_COUNT) {
                    TitleQualified("REGION_EXPLORER", fact.tripId, fact.atMs, "서로 다른 시·군·구 지역 뱃지 30개 획득")
                } else null
            }
            is AchievementFact.GhostFinished -> {
                require(fact.atMs >= 0)
                val distance = revisitedDistance(fact.result)
                if (fact.result.matchedDistanceM >= DetectionPolicy.TITLE_REVISIT_DISTANCE_M &&
                    distance >= DetectionPolicy.TITLE_REVISIT_DISTANCE_M) {
                    TitleQualified("ROAD_REVISITOR", fact.result.tripId, fact.atMs,
                        "마지막 통과에서 30일 이상 지난 고스트 구간 5km 이상 재주행")
                } else null
            }
            is AchievementFact.TripFinished -> {
                require(fact.tripId.isNotBlank() && fact.atMs >= 0)
                require(fact.newRoadM.isFinite() && fact.newRoadM >= 0 &&
                    fact.newRoadM <= progress.newRoadDistanceM + 0.001)
                if (progress.newRoadDistanceM >= DetectionPolicy.TITLE_NEW_ROAD_DISTANCE_M) {
                    TitleQualified("NEW_ROAD_DISCOVERER", fact.tripId, fact.atMs, "처음 기록된 도로 누적 100km 탐험")
                } else null
            }
        }
        return listOfNotNull(proposed).filter { it.titleId !in progress.acquiredTitleIds }
    }

    private fun revisitedDistance(result: GhostResult): Double {
        require(result.id.isNotBlank() && result.tripId.isNotBlank())
        require(result.pastTripId == null || (result.pastTripId.isNotBlank() && result.pastTripId != result.tripId))
        require(result.matchedDistanceM.isFinite() && result.matchedDistanceM >= DetectionPolicy.GHOST_START_DISTANCE_M)
        if (result.passages.isEmpty()) return 0.0
        var sectionIndex = -1
        var end = 1.0
        var distance = 0.0
        val eligible = result.passages.sumOf { passage ->
            require(passage.fromFraction.isFinite() && passage.toFraction.isFinite())
            require(passage.fromFraction >= 0 && passage.toFraction <= 1 && passage.toFraction > passage.fromFraction)
            if (passage.sectionIndex != sectionIndex) {
                require(passage.sectionIndex.toLong() == sectionIndex.toLong() + 1 && abs(end - 1) <= 1e-10)
                require(passage.fromFraction <= 1e-10)
                sectionIndex = passage.sectionIndex
            } else require(abs(passage.fromFraction - end) <= 1e-10)
            passage.visits.forEach { visit ->
                require(visit.pathSegmentId.isNotBlank())
                require(visit.pastTripId == null || (visit.pastTripId.isNotBlank() && visit.pastTripId != result.tripId))
            }
            end = passage.toFraction
            distance += passage.distanceM
            passage.revisitedDistanceM(DetectionPolicy.TITLE_REVISIT_AGE_MS)
        }
        require(abs(end - 1) <= 1e-10 && abs(distance - result.matchedDistanceM) <= 0.001)
        return eligible
    }
}
