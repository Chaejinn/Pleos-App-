package com.pleos.explorer.domain.usecase

import com.pleos.explorer.domain.engine.GhostDetector
import com.pleos.explorer.domain.model.GhostInput
import com.pleos.explorer.domain.model.GhostSnapshot
import com.pleos.explorer.domain.model.GhostUpdate
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
        val candidates = if (input is GhostInput.Location &&
            input.location.elapsedMs > (state.previous?.elapsedMs ?: -1)) {
            history.findNearby(detector.requiredGridKeys(state, input), tripId)
        } else emptyList()
        return detector.process(state, input, sequence, candidates).also { store.commit(state.revision, it) }
    }

    suspend fun resume(tripId: String, sessionId: String): GhostUpdate {
        require(sessionId.isNotBlank())
        val state = requireNotNull(store.load(tripId)) { "Ghost session is not initialized" }
        require(state.tripId == tripId)
        if (state.sessionId == sessionId) return GhostUpdate(state)
        return detector.resume(state, sessionId).also { store.commit(state.revision, it) }
    }
}
