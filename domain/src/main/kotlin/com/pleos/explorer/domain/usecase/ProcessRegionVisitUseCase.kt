package com.pleos.explorer.domain.usecase

import com.pleos.explorer.domain.engine.RegionVisitDetector
import com.pleos.explorer.domain.model.BadgeType
import com.pleos.explorer.domain.model.PendingReward
import com.pleos.explorer.domain.model.RegionBadge
import com.pleos.explorer.domain.model.RegionInput
import com.pleos.explorer.domain.model.RegionVisitSnapshot
import com.pleos.explorer.domain.model.RegionVisitUpdate
import com.pleos.explorer.domain.model.RewardKind
import com.pleos.explorer.domain.model.TripRegionVisit
import com.pleos.explorer.domain.port.IdGenerator
import com.pleos.explorer.domain.port.RegionVisitStore

class ProcessRegionVisitUseCase(
    private val store: RegionVisitStore,
    private val detector: RegionVisitDetector,
    private val ids: IdGenerator,
) {
    suspend fun start(sessionId: String, tripId: String? = null): RegionVisitUpdate {
        require(sessionId.isNotBlank() && (tripId == null || tripId.isNotBlank()))
        val current = store.load()
        if (current != null) {
            require(current.sessionId == sessionId && current.tripId == tripId)
            return RegionVisitUpdate(current)
        }
        return RegionVisitUpdate(RegionVisitSnapshot(sessionId, tripId)).also { store.commit(null, it) }
    }

    suspend fun process(sessionId: String, sequence: Long, input: RegionInput): RegionVisitUpdate {
        val state = requireNotNull(store.load()) { "Visit session is not initialized" }
        require(sessionId == state.sessionId && sequence >= 0)
        if (sequence <= state.lastInputSequence) return RegionVisitUpdate(state)
        val proposed = detector.process(state, input, sequence)
        val known = if (proposed.qualifiedVisits.isEmpty()) emptyMap() else {
            store.findBadges(proposed.qualifiedVisits.map { it.regionCode }.toSet())
        }
        val badges = mutableMapOf<String, RegionBadge>()
        val visits = mutableListOf<TripRegionVisit>()
        val rewards = mutableListOf<PendingReward>()
        proposed.qualifiedVisits.forEach { visit ->
            val badgeId = "REGION:${visit.regionCode}"
            val old = badges[badgeId] ?: known[badgeId]
            if (old != null) {
                check(old.badgeId == badgeId && old.regionCode == visit.regionCode &&
                    old.type == BadgeType.REGION && old.visitCount > 0) { "Invalid persisted region badge" }
            }
            badges[badgeId] = old?.copy(visitCount = Math.addExact(old.visitCount, 1))
                ?: RegionBadge(badgeId, visit.regionCode, BadgeType.REGION, visit.tripId, visit.qualifiedAtMs, 1)
            visit.tripId?.let { tripId -> visits.add(TripRegionVisit(ids.newId(), tripId, visit.regionCode,
                visit.entrySequence, visit.qualifiedAtMs)) }
            if (old == null) rewards.add(PendingReward(ids.newId(), "BADGE:$badgeId", visit.tripId,
                RewardKind.BADGE, badgeId, visit.qualifiedAtMs))
        }
        val update = proposed.copy(badges = badges.toMap(), visits = visits.toList(), rewards = rewards.toList())
        store.commit(state.revision, update)
        return update
    }

    suspend fun resume(sessionId: String): RegionVisitUpdate {
        require(sessionId.isNotBlank())
        val state = requireNotNull(store.load()) { "Visit session is not initialized" }
        if (state.sessionId == sessionId) return RegionVisitUpdate(state)
        return detector.resume(state, sessionId).also { store.commit(state.revision, it) }
    }
}
