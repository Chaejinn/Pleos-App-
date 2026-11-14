package com.pleos.explorer.domain.usecase

import com.pleos.explorer.domain.engine.GhostResultAssembler
import com.pleos.explorer.domain.engine.GhostResultBuilder
import com.pleos.explorer.domain.model.GhostComparisonInput
import com.pleos.explorer.domain.model.GhostComparisonSnapshot
import com.pleos.explorer.domain.model.GhostComparisonUpdate
import com.pleos.explorer.domain.model.GhostEvent
import com.pleos.explorer.domain.model.PendingReward
import com.pleos.explorer.domain.model.RewardKind
import com.pleos.explorer.domain.port.GhostComparisonStore
import com.pleos.explorer.domain.port.HistoryRepository
import com.pleos.explorer.domain.port.IdGenerator

class ProcessGhostComparisonUseCase(
    private val store: GhostComparisonStore,
    private val history: HistoryRepository,
    private val ids: IdGenerator,
    private val builder: GhostResultBuilder = GhostResultBuilder(),
    private val assembler: GhostResultAssembler = GhostResultAssembler(builder),
) {
    suspend fun start(tripId: String, sessionId: String): GhostComparisonUpdate {
        require(tripId.isNotBlank() && sessionId.isNotBlank())
        val state = store.load(tripId)
        if (state != null) {
            require(state.tripId == tripId && state.sessionId == sessionId)
            return GhostComparisonUpdate(state)
        }
        return GhostComparisonUpdate(GhostComparisonSnapshot(tripId, sessionId)).also { store.commit(null, it) }
    }

    suspend fun process(tripId: String, sessionId: String, sequence: Long,
        input: GhostComparisonInput): GhostComparisonUpdate {
        val state = requireNotNull(store.load(tripId)) { "Comparison is not initialized" }
        require(state.tripId == tripId && state.sessionId == sessionId && sequence >= 0)
        if (sequence <= state.lastInputSequence) return GhostComparisonUpdate(state)
        check(!state.finished) { "Trip is closed" }
        require(input.ghost.snapshot.lastInputSequence == sequence)
        return update(state, input, sessionId, false)
    }

    suspend fun resume(tripId: String, sessionId: String, input: GhostComparisonInput): GhostComparisonUpdate {
        require(sessionId.isNotBlank())
        val state = requireNotNull(store.load(tripId)) { "Comparison is not initialized" }
        require(state.tripId == tripId)
        if (state.sessionId == sessionId) return GhostComparisonUpdate(state)
        check(!state.finished) { "Trip is closed" }
        return update(state, input, sessionId, true)
    }

    private suspend fun update(state: GhostComparisonSnapshot, input: GhostComparisonInput,
        sessionId: String, resuming: Boolean): GhostComparisonUpdate {
        assembler.validate(state, input, sessionId, resuming)
        val segments = input.ghost.events.filterIsInstance<GhostEvent.Finished>().map { it.segment }
        val tracks = if (segments.isEmpty()) emptyList() else {
            history.findNearby(builder.requiredGridKeys(segments), state.tripId)
        }
        val proposed = assembler.process(state, input, tracks, sessionId)
        val rewards = segments.map { segment ->
            val id = ids.newId()
            check(id.isNotBlank())
            PendingReward(id, "GHOST_RESULT:${segment.id}", state.tripId, RewardKind.GHOST_RESULT,
                segment.id, segment.endedAtMs)
        }
        return proposed.copy(rewards = rewards).also { store.commit(state.revision, it) }
    }
}
