package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.geo.GeoDistance
import com.pleos.explorer.domain.geo.GhostMatch
import com.pleos.explorer.domain.geo.GhostMatcher
import com.pleos.explorer.domain.geo.GridProjector
import com.pleos.explorer.domain.model.DetectionPolicy
import com.pleos.explorer.domain.model.GhostEvent
import com.pleos.explorer.domain.model.GhostInput
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
        var active = next.active
        var pending = next.candidates.mapNotNull { prior ->
            advance(prior, matches[prior.cursor.key].orEmpty(), location, previous, input.connectedDistanceM, history)
        }.associateBy { it.cursor.key }.toMutableMap()
        matches.forEach { (key, options) ->
            if (key !in pending && key != active?.cursor?.key) {
                val match = options.minWith(compareBy<GhostMatch> { it.distanceM }.thenBy { it.cursor.from.sequence })
                pending[key] = GhostProgress(match.cursor, location, location)
            }
        }
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
            val eligible = pending.values.filter { it.matchedDistanceM >= DetectionPolicy.GHOST_START_DISTANCE_M }
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
        return GhostUpdate(next.copy(previous = location, active = active, candidates = pending.values.toList()),
            events.toList())
    }

    private fun advance(prior: GhostProgress, options: List<GhostMatch>, location: ValidatedLocation,
        previous: ValidatedLocation?, distanceM: Double, history: List<HistoricalTrack>): GhostProgress? {
        val option = options.mapNotNull { match ->
            val progress = matcher.advance(prior.cursor, match.cursor, history) ?: return@mapNotNull null
            if (progress > prior.mismatchedDistanceM + distanceM + 2 * DetectionPolicy.GHOST_DISTANCE_M) null
            else if (previous?.elapsedMs == prior.lastMatchedLocation.elapsedMs &&
                !matcher.covers(prior.lastMatchedLocation, location, prior.cursor, match.cursor, history)) null
            else match to progress
        }.minWithOrNull(compareBy<Pair<GhostMatch, Double>> { it.first.distanceM }
            .thenBy { it.first.cursor.from.sequence }) ?: return null
        val credited = if (previous?.elapsedMs == prior.lastMatchedLocation.elapsedMs) {
            minOf(distanceM, option.second)
        } else 0.0
        val sections = if (credited > 0) prior.sections + GhostSection(prior.lastMatchedLocation, location,
            prior.cursor, option.first.cursor, credited) else prior.sections
        return prior.copy(cursor = option.first.cursor, lastMatchedLocation = location,
            matchedDistanceM = prior.matchedDistanceM + credited, mismatchedDistanceM = 0.0, sections = sections)
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
