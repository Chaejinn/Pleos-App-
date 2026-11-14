package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.model.GhostComparisonAnchor
import com.pleos.explorer.domain.model.GhostComparisonInput
import com.pleos.explorer.domain.model.GhostComparisonSnapshot
import com.pleos.explorer.domain.model.GhostComparisonUpdate
import com.pleos.explorer.domain.model.GhostEvent
import com.pleos.explorer.domain.model.GhostResult
import com.pleos.explorer.domain.model.HistoricalTrack
import com.pleos.explorer.domain.model.LocationValidation
import com.pleos.explorer.domain.model.PendingGhostResult
import com.pleos.explorer.domain.model.TripStatus

class GhostResultAssembler(private val builder: GhostResultBuilder = GhostResultBuilder()) {
    fun validate(state: GhostComparisonSnapshot, input: GhostComparisonInput, sessionId: String, resuming: Boolean) {
        val track = input.recording.snapshot
        val ghost = input.ghost.snapshot
        require(track.trip.id == state.tripId && ghost.tripId == state.tripId)
        require(track.sessionId == sessionId && ghost.sessionId == sessionId)
        require(track.revision == state.recordingRevision + 1 && ghost.revision == state.ghostRevision + 1) {
            "Every F03 and F12 update must be delivered in order"
        }
        require(ghost.finished == (track.status != TripStatus.ACTIVE))
        if (resuming) require(track.lastInputSequence == -1L && ghost.lastInputSequence == -1L && ghost.previous == null)
        else if (track.status == TripStatus.ACTIVE) require(track.lastInputSequence == ghost.lastInputSequence)
        require(input.ghost.events.filterIsInstance<GhostEvent.Finished>().size <= 1)
        require(input.ghost.events.filterIsInstance<GhostEvent.Started>().size <= 1)
        require(input.ghost.events.filterIsInstance<GhostEvent.Finished>().all { it.segment.tripId == state.tripId })
        val current = input.recording.validation
        if (current is LocationValidation.Accepted) {
            val previous = requireNotNull(ghost.previous)
            require(previous.elapsedMs == current.location.elapsedMs && previous.timeMs == current.location.timeMs &&
                previous.point == current.location.point)
        }
    }

    fun process(state: GhostComparisonSnapshot, input: GhostComparisonInput,
        history: List<HistoricalTrack>, sessionId: String = state.sessionId): GhostComparisonUpdate {
        validate(state, input, sessionId, sessionId != state.sessionId)
        val ghost = input.ghost.snapshot
        val track = input.recording.snapshot
        val roads = track.pendingRoads.filterKeys { it in track.newGridKeys }
        require(track.newGridKeys.all { it in track.pendingRoads })
        val total = if (track.status == TripStatus.ACTIVE) roads.values.sumOf { it.firstExploredDistanceM } else state.newRoadM
        require(roads.values.all { it.firstExploredDistanceM.isFinite() && it.firstExploredDistanceM >= 0 })
        require(total.isFinite() && total + 1e-6 >= state.newRoadM) { "F03 new road total cannot decrease" }
        val checkpoints = state.candidateNewRoadM.toMutableMap()
        ghost.previous?.let { checkpoints[it.elapsedMs] = total }
        state.active?.let { checkpoints[it.firstElapsedMs] = it.firstNewRoadM }
        var pending = state.pending
        var active = state.active
        val results = linkedMapOf<String, GhostResult>()
        input.ghost.events.filterIsInstance<GhostEvent.Finished>().singleOrNull()?.let { event ->
            val anchor = requireNotNull(active) { "Missing active comparison checkpoint" }
            require(anchor.id == event.segment.id)
            check(pending == null)
            pending = PendingGhostResult(builder.build(event.segment, history), anchor.lastMatchedNewRoadM)
            active = null
        }
        val started = input.ghost.events.filterIsInstance<GhostEvent.Started>().singleOrNull()
        if (started != null) {
            val progress = requireNotNull(ghost.active)
            require(progress.id == started.id)
            require(progress.cursor.key.tripId == started.pastTripId)
            val firstM = requireNotNull(checkpoints[progress.firstLocation.elapsedMs]) { "Missing candidate checkpoint" }
            pending?.let { old ->
                require(firstM + 1e-6 >= old.fromNewRoadM)
                results[old.result.id] = old.result.copy(newRoadM = maxOf(0.0, firstM - old.fromNewRoadM), finalized = true)
            }
            pending = null
            active = GhostComparisonAnchor(started.id, progress.firstLocation.elapsedMs, firstM,
                progress.lastMatchedLocation.elapsedMs, total)
        } else ghost.active?.let { progress ->
            val anchor = requireNotNull(active) { "Missing active comparison" }
            require(anchor.id == progress.id)
            if (progress.lastMatchedLocation.elapsedMs != anchor.lastMatchedElapsedMs) {
                require(progress.lastMatchedLocation.elapsedMs == ghost.previous?.elapsedMs)
                active = anchor.copy(lastMatchedElapsedMs = progress.lastMatchedLocation.elapsedMs,
                    lastMatchedNewRoadM = total)
            }
        }
        require((active != null) == (ghost.active != null))
        pending?.let { old ->
            val result = old.result.copy(newRoadM = maxOf(0.0, total - old.fromNewRoadM), finalized = ghost.finished)
            if (state.pending?.result != result) results[result.id] = result
            pending = if (ghost.finished) null else old.copy(result = result)
        }
        val nextCheckpoints = ghost.candidates.map { it.firstLocation.elapsedMs }.distinct().associateWith { elapsed ->
            requireNotNull(checkpoints[elapsed]) { "Missing candidate checkpoint" }
        }
        return GhostComparisonUpdate(state.copy(sessionId = sessionId, revision = Math.addExact(state.revision, 1),
            lastInputSequence = ghost.lastInputSequence, recordingRevision = track.revision, ghostRevision = ghost.revision,
            newRoadM = total, candidateNewRoadM = nextCheckpoints, active = active, pending = pending,
            finished = ghost.finished), results.values.toList())
    }
}
