package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.geo.GeoDistance
import com.pleos.explorer.domain.geo.GhostMatch
import com.pleos.explorer.domain.geo.GhostMatcher
import com.pleos.explorer.domain.geo.GridProjector
import com.pleos.explorer.domain.model.DetectionPolicy
import com.pleos.explorer.domain.model.GhostEvent
import com.pleos.explorer.domain.model.GhostInput
import com.pleos.explorer.domain.model.GhostKey
import com.pleos.explorer.domain.model.GhostProgress
import com.pleos.explorer.domain.model.GhostSection
import com.pleos.explorer.domain.model.GhostSegment
import com.pleos.explorer.domain.model.GhostSnapshot
import com.pleos.explorer.domain.model.GhostUpdate
import com.pleos.explorer.domain.model.HistoricalTrack
import com.pleos.explorer.domain.model.ValidatedLocation
import com.pleos.explorer.domain.port.IdGenerator
import kotlin.math.abs

class GhostDetector(
    private val ids: IdGenerator,
    private val matcher: GhostMatcher = GhostMatcher(),
    private val grid: GridProjector = GridProjector(),
) {
    private data class CursorPosition(val key: GhostKey, val sequence: Long, val fraction: Double)
    private data class ProgressMatch(val progress: GhostProgress, val distanceM: Double)

    fun requiredGridKeys(state: GhostSnapshot, input: GhostInput.Location): Set<String> {
        val location = input.location
        val previous = state.previous
        val sections = if (previous != null && !input.newSegment &&
            location.elapsedMs > previous.elapsedMs &&
            location.elapsedMs - previous.elapsedMs < DetectionPolicy.LOCATION_GAP_MS) {
            grid.split(previous.point, location.point).map { it.gridKey }
        } else emptyList()
        return (sections + grid.key(location.point)).flatMap { grid.neighbors(it) }.toSet()
    }

    fun process(state: GhostSnapshot, input: GhostInput, sequence: Long,
        history: List<HistoricalTrack> = emptyList()): GhostUpdate {
        check(!state.finished)
        require(sequence >= 0 && sequence > state.lastInputSequence)
        val next = state.copy(revision = Math.addExact(state.revision, 1), lastInputSequence = sequence)
        return when (input) {
            is GhostInput.Location -> location(next, input, history)
            GhostInput.ContinuityLost -> disconnect(next)
            GhostInput.TripEnded -> disconnect(next).let { it.copy(snapshot = it.snapshot.copy(finished = true)) }
        }
    }

    fun resume(state: GhostSnapshot, sessionId: String): GhostUpdate {
        check(!state.finished)
        require(sessionId.isNotBlank() && sessionId != state.sessionId)
        val closed = disconnect(state)
        return closed.copy(snapshot = closed.snapshot.copy(sessionId = sessionId,
            revision = Math.addExact(state.revision, 1), lastInputSequence = -1))
    }

    private fun location(state: GhostSnapshot, input: GhostInput.Location,
        history: List<HistoricalTrack>): GhostUpdate {
        require(input.connectedDistanceM.isFinite() && input.connectedDistanceM >= 0)
        val location = input.location
        val previous = state.previous
        if (previous != null && location.elapsedMs <= previous.elapsedMs) return GhostUpdate(state)
        val connected = previous != null && !input.newSegment &&
            location.elapsedMs - previous.elapsedMs < DetectionPolicy.LOCATION_GAP_MS
        if (connected) {
            val distance = GeoDistance.meters(requireNotNull(previous).point, location.point)
            require(abs(distance - input.connectedDistanceM) <= maxOf(0.001, distance * 1e-6)) {
                "Connected distance must match the approved F03 segment"
            }
        } else require(input.connectedDistanceM == 0.0) { "Disconnected location cannot carry distance" }
        val reset = if (connected) GhostUpdate(state) else disconnect(state)
        val next = reset.snapshot
        val events = reset.events.toMutableList()
        val matches = matcher.matches(location, history.filter { it.tripId != state.tripId }).groupBy { it.cursor.key }
        val matchRuns = matches.mapValues { (_, options) -> matchingRuns(options) }
        var active = next.active
        val advancedCandidates = next.candidates.flatMap { prior ->
            matchRuns[prior.cursor.key].orEmpty().mapNotNull { options ->
                advance(prior, options, location, previous, input.connectedDistanceM, history)
            }
        }
        val seeds = matchRuns.filterKeys { it != active?.cursor?.key }.values.flatten().map { options ->
            val match = options.minWith(compareBy<GhostMatch> { it.distanceM }.thenBy { it.cursor.from.sequence })
            GhostProgress(match.cursor, location, location)
        }
        val pending = (advancedCandidates + seeds).groupBy { candidate ->
            val cursor = candidate.cursor
            if (cursor.fraction == 1.0) CursorPosition(cursor.key, cursor.to.sequence, 0.0)
            else CursorPosition(cursor.key, cursor.from.sequence, cursor.fraction)
        }.values.map { alternatives -> alternatives.maxBy { it.matchedDistanceM } }.toMutableList()
        if (active != null) {
            val advanced = advance(active, matches[active.cursor.key].orEmpty(), location, previous,
                input.connectedDistanceM, history)
            if (advanced != null) {
                if (advanced.matchedDistanceM > active.matchedDistanceM) {
                    events.add(GhostEvent.Updated(requireNotNull(active.id), advanced.matchedDistanceM))
                }
                active = advanced
                pending.clear()
            } else {
                active = active.copy(mismatchedDistanceM = active.mismatchedDistanceM + input.connectedDistanceM)
                if (active.mismatchedDistanceM >= DetectionPolicy.GHOST_EXIT_DISTANCE_M) {
                    events.add(finished(next, active))
                    active = null
                }
            }
        }
        if (active == null) {
            val eligible = pending.filter { it.matchedDistanceM >= DetectionPolicy.GHOST_START_DISTANCE_M }
                .sortedWith(compareByDescending<GhostProgress> { it.cursor.timeMs }
                    .thenBy { it.cursor.key.tripId }.thenBy { it.cursor.key.pathSegmentId })
            eligible.firstOrNull()?.let { chosen ->
                val id = ids.newId()
                check(id.isNotBlank())
                active = chosen.copy(id = id)
                events.add(GhostEvent.Started(id, chosen.cursor.key.tripId, location.timeMs))
                pending.clear()
            }
        }
        return GhostUpdate(next.copy(previous = location, active = active, candidates = pending.toList()),
            events.toList())
    }

    private fun matchingRuns(options: List<GhostMatch>): List<List<GhostMatch>> {
        val runs = mutableListOf<MutableList<GhostMatch>>()
        options.sortedBy { it.cursor.from.sequence }.forEach { match ->
            val last = runs.lastOrNull()
            if (last == null || last.last().cursor.to.sequence != match.cursor.from.sequence) {
                runs.add(mutableListOf(match))
            } else last.add(match)
        }
        return runs
    }

    private fun advance(prior: GhostProgress, options: List<GhostMatch>, location: ValidatedLocation,
        previous: ValidatedLocation?, distanceM: Double, history: List<HistoricalTrack>): GhostProgress? =
        advances(prior, options, location, previous, distanceM, history)
            .minWithOrNull(compareBy<ProgressMatch> { it.distanceM }
                .thenBy { it.progress.cursor.from.sequence })?.progress

    private fun advances(prior: GhostProgress, options: List<GhostMatch>, location: ValidatedLocation,
        previous: ValidatedLocation?, distanceM: Double, history: List<HistoricalTrack>): List<ProgressMatch> =
        options.mapNotNull { match ->
            val progress = matcher.advance(prior.cursor, match.cursor, history) ?: return@mapNotNull null
            if (progress > prior.mismatchedDistanceM + distanceM + 2 * DetectionPolicy.GHOST_DISTANCE_M) {
                return@mapNotNull null
            }
            val consecutive = previous?.elapsedMs == prior.lastMatchedLocation.elapsedMs
            if (consecutive && !matcher.covers(prior.lastMatchedLocation, location, prior.cursor, match.cursor, history)) {
                return@mapNotNull null
            }
            val credited = if (consecutive) minOf(distanceM, progress) else 0.0
            val sections = if (credited > 0) prior.sections + GhostSection(prior.lastMatchedLocation, location,
                prior.cursor, match.cursor, credited) else prior.sections
            ProgressMatch(prior.copy(cursor = match.cursor, lastMatchedLocation = location,
                matchedDistanceM = prior.matchedDistanceM + credited, mismatchedDistanceM = 0.0, sections = sections),
                match.distanceM)
        }

    private fun disconnect(state: GhostSnapshot): GhostUpdate = GhostUpdate(
        state.copy(previous = null, active = null, candidates = emptyList()),
        state.active?.let { listOf(finished(state, it)) }.orEmpty(),
    )

    private fun finished(state: GhostSnapshot, active: GhostProgress): GhostEvent.Finished =
        GhostEvent.Finished(GhostSegment(requireNotNull(active.id), state.tripId, active.cursor.key.tripId,
            active.matchedDistanceM, active.firstLocation.timeMs,
            maxOf(active.firstLocation.timeMs, active.lastMatchedLocation.timeMs), active.sections))
}
