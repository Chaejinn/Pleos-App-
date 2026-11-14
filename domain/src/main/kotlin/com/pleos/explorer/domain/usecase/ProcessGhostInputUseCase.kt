package com.pleos.explorer.domain.usecase

import com.pleos.explorer.domain.engine.GhostDetector
import com.pleos.explorer.domain.model.DetectionPolicy
import com.pleos.explorer.domain.model.GhostInput
import com.pleos.explorer.domain.model.GhostSnapshot
import com.pleos.explorer.domain.model.GhostUpdate
import com.pleos.explorer.domain.model.HistoricalTrack
import com.pleos.explorer.domain.port.GhostStore
import com.pleos.explorer.domain.port.HistoryRepository

class ProcessGhostInputUseCase(
    private val store: GhostStore,
    private val history: HistoryRepository,
    private val detector: GhostDetector,
) {
    suspend fun start(tripId: String, sessionId: String): GhostUpdate {
        require(tripId.isNotBlank() && sessionId.isNotBlank())
        val state = store.load(tripId)
        if (state != null) {
            require(state.tripId == tripId && state.sessionId == sessionId)
            return GhostUpdate(state)
        }
        return GhostUpdate(GhostSnapshot(tripId, sessionId)).also { store.commit(null, it) }
    }

    suspend fun process(tripId: String, sessionId: String, sequence: Long, input: GhostInput): GhostUpdate {
        val state = requireNotNull(store.load(tripId)) { "Ghost session is not initialized" }
        require(state.tripId == tripId && state.sessionId == sessionId && sequence >= 0)
        if (sequence <= state.lastInputSequence) return GhostUpdate(state)
        check(!state.finished) { "Trip is closed" }
        var candidates = if (input is GhostInput.Location &&
            input.location.elapsedMs > (state.previous?.elapsedMs ?: -1)) {
            require(input.connectedDistanceM.isFinite() && input.connectedDistanceM >= 0)
            history.findNearby(detector.requiredGridKeys(state, input), tripId)
        } else emptyList()
        val previous = state.previous
        val active = state.active
        if (input is GhostInput.Location && active != null && active.mismatchedDistanceM > 0 &&
            previous != null && !input.newSegment && input.location.elapsedMs > previous.elapsedMs &&
            input.location.elapsedMs - previous.elapsedMs < DetectionPolicy.LOCATION_GAP_MS) {
            val forward = history.findForward(active.cursor,
                active.mismatchedDistanceM + input.connectedDistanceM + 2 * DetectionPolicy.GHOST_DISTANCE_M)
            require(forward.tripId == active.cursor.key.tripId) { "Unexpected historical trip" }
            candidates = merge(candidates, forward)
        }
        return detector.process(state, input, sequence, candidates).also { store.commit(state.revision, it) }
    }

    private fun merge(nearby: List<HistoricalTrack>, forward: HistoricalTrack): List<HistoricalTrack> {
        require(nearby.map { it.tripId }.distinct().size == nearby.size) { "Duplicate historical trip" }
        (nearby + forward).forEach { track ->
            require(track.points.zipWithNext().all { it.first.sequence < it.second.sequence })
        }
        return (nearby + forward).groupBy { it.tripId }.map { (tripId, tracks) ->
            val points = tracks.flatMap { it.points }.groupBy { it.sequence }.map { (_, values) ->
                require(values.distinct().size == 1) { "Historical point changed during lookup" }
                values.first()
            }.sortedBy { it.sequence }
            HistoricalTrack(tripId, points)
        }
    }

    suspend fun resume(tripId: String, sessionId: String): GhostUpdate {
        require(sessionId.isNotBlank())
        val state = requireNotNull(store.load(tripId)) { "Ghost session is not initialized" }
        require(state.tripId == tripId)
        if (state.sessionId == sessionId) return GhostUpdate(state)
        return detector.resume(state, sessionId).also { store.commit(state.revision, it) }
    }
}
