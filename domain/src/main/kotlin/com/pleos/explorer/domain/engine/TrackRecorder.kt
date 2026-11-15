package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.geo.GeoDistance
import com.pleos.explorer.domain.geo.GridProjector
import com.pleos.explorer.domain.geo.PathSimplifier
import com.pleos.explorer.domain.geo.SectionCoverage
import com.pleos.explorer.domain.model.DetectionPolicy
import com.pleos.explorer.domain.model.ExploredRoad
import com.pleos.explorer.domain.model.ExploredSection
import com.pleos.explorer.domain.model.LocationValidation
import com.pleos.explorer.domain.model.PathSegment
import com.pleos.explorer.domain.model.RecordingSnapshot
import com.pleos.explorer.domain.model.TrackPoint
import com.pleos.explorer.domain.model.TrackUpdate
import com.pleos.explorer.domain.model.Trip
import com.pleos.explorer.domain.model.TripStatus
import com.pleos.explorer.domain.model.ValidatedLocation
import com.pleos.explorer.domain.port.IdGenerator

class TrackRecorder(
    private val ids: IdGenerator,
    private val grid: GridProjector = GridProjector(),
    private val simplifier: PathSimplifier = PathSimplifier(),
) {
    private val coverage = SectionCoverage(grid)

    fun start(tripId: String, startedAtMs: Long, sessionId: String): TrackUpdate {
        require(tripId.isNotBlank() && sessionId.isNotBlank() && startedAtMs >= 0)
        return TrackUpdate(RecordingSnapshot(Trip(tripId, startedAtMs), sessionId))
    }

    fun requiredGridKeys(state: RecordingSnapshot, location: ValidatedLocation): Set<String> {
        val sections = if (isConnected(state, location)) {
            grid.split(requireNotNull(state.previous).point, location.point)
        } else emptyList()
        return (sections.map { it.gridKey } + grid.key(location.point)).toSet() - state.pendingRoads.keys
    }

    fun process(
        state: RecordingSnapshot,
        validation: LocationValidation,
        inputSequence: Long,
        knownRoads: Map<String, ExploredRoad>,
    ): TrackUpdate {
        check(state.status == TripStatus.ACTIVE)
        require(inputSequence > state.lastInputSequence)
        val points = mutableListOf<TrackPoint>()
        var next = state.copy(revision = state.revision + 1, lastInputSequence = inputSequence)
        if (validation is LocationValidation.Rejected) {
            next = flushTail(next, points).copy(continuityBroken = true)
            return TrackUpdate(next, points, validation = validation)
        }
        val location = (validation as LocationValidation.Accepted).location
        val connected = isConnected(state, location)
        val previous = state.previous
        val distance = if (connected) GeoDistance.meters(requireNotNull(previous).point, location.point) else 0.0
        if (!connected) {
            next = flushTail(next, points).copy(segmentId = ids.newId(), lastStored = null,
                lastStoredElapsedMs = null)
        }
        val saved = next.lastStored
        if (saved == null || GeoDistance.meters(saved.point, location.point) >= DetectionPolicy.TRACK_DISTANCE_M ||
            location.elapsedMs - requireNotNull(next.lastStoredElapsedMs) >= DetectionPolicy.TRACK_INTERVAL_MS) {
            next = append(next, location, points)
        }
        val roads = next.pendingRoads.toMutableMap()
        val newKeys = next.newGridKeys.toMutableSet()
        val credited = next.creditedSections.toMutableMap()
        val sections = if (connected) grid.split(requireNotNull(previous).point, location.point) else emptyList()
        for (key in sections.map { it.gridKey } + grid.key(location.point)) {
            val pending = roads[key]
            if (pending != null) {
                roads[key] = pending.copy(lastVisitedAtMs = maxOf(pending.lastVisitedAtMs, location.timeMs))
            } else {
                val old = knownRoads[key]
                roads[key] = old?.copy(lastVisitedAtMs = maxOf(old.lastVisitedAtMs, location.timeMs),
                    visitCount = Math.addExact(old.visitCount, 1))
                    ?: ExploredRoad(key, state.trip.id, location.timeMs, location.timeMs, 1, 0.0)
                if (old == null) newKeys.add(key)
            }
        }
        for (section in sections) {
            if (section.gridKey !in newKeys) continue
            val prior = credited[section.gridKey].orEmpty()
            val fraction = coverage.uncoveredFraction(section, prior)
            val road = roads.getValue(section.gridKey)
            roads[section.gridKey] = road.copy(firstExploredDistanceM = road.firstExploredDistanceM +
                section.distanceM * fraction)
            if (fraction > 1e-12) {
                credited[section.gridKey] = prior + ExploredSection(section.gridKey, section.from, section.to)
            }
        }
        next = next.copy(trip = state.trip.copy(distanceM = state.trip.distanceM + distance), previous = location,
            continuityBroken = false, pendingRoads = roads.toMap(), newGridKeys = newKeys.toSet(),
            creditedSections = credited.toMap())
        return TrackUpdate(next, points.toList(), connectedDistanceM = distance, newSegment = !connected,
            validation = validation)
    }

    fun interrupt(state: RecordingSnapshot, inputSequence: Long): TrackUpdate {
        check(state.status == TripStatus.ACTIVE)
        require(inputSequence > state.lastInputSequence)
        val points = mutableListOf<TrackPoint>()
        val next = flushTail(state, points).copy(revision = Math.addExact(state.revision, 1),
            lastInputSequence = inputSequence, continuityBroken = true)
        return TrackUpdate(next, points.toList())
    }

    fun resume(state: RecordingSnapshot, sessionId: String): TrackUpdate {
        check(state.status == TripStatus.ACTIVE)
        require(sessionId.isNotBlank() && sessionId != state.sessionId)
        val points = mutableListOf<TrackPoint>()
        val next = flushTail(state, points).copy(sessionId = sessionId, revision = state.revision + 1,
            lastInputSequence = -1, previous = null, lastStored = null, lastStoredElapsedMs = null,
            segmentId = null, continuityBroken = true)
        return TrackUpdate(next, points.toList())
    }

    fun finish(state: RecordingSnapshot, atMs: Long, storedPoints: List<TrackPoint>): TrackUpdate {
        check(state.status == TripStatus.ACTIVE)
        require(atMs >= 0)
        val points = mutableListOf<TrackPoint>()
        val flushed = flushTail(state, points)
        val discarded = state.trip.distanceM < DetectionPolicy.MIN_TRIP_DISTANCE_M
        val path = if (discarded) emptyList() else (storedPoints + points).groupBy { it.pathSegmentId }
            .map { (id, values) -> PathSegment(id, simplifier.simplify(values.map { it.point })) }
        val lastApprovedAtMs = state.previous?.timeMs ?: storedPoints.lastOrNull()?.timeMs ?: 0L
        val trip = state.trip.copy(endedAtMs = maxOf(atMs, state.trip.startedAtMs, lastApprovedAtMs),
            simplifiedPath = path)
        val next = flushed.copy(trip = trip, revision = state.revision + 1,
            status = if (discarded) TripStatus.DISCARDED else TripStatus.FINISHED,
            previous = null, lastStored = null, lastStoredElapsedMs = null, segmentId = null,
            continuityBroken = true, pendingRoads = emptyMap(), newGridKeys = emptySet(),
            creditedSections = emptyMap())
        return TrackUpdate(next, if (discarded) emptyList() else points.toList(),
            roads = if (discarded) emptyMap() else state.pendingRoads)
    }

    private fun isConnected(state: RecordingSnapshot, location: ValidatedLocation): Boolean {
        val previous = state.previous ?: return false
        return !state.continuityBroken && location.elapsedMs - previous.elapsedMs < DetectionPolicy.LOCATION_GAP_MS
    }

    private fun flushTail(state: RecordingSnapshot, points: MutableList<TrackPoint>): RecordingSnapshot {
        val previous = state.previous ?: return state
        if (state.lastStoredElapsedMs == previous.elapsedMs) return state
        return append(state, previous, points)
    }

    private fun append(
        state: RecordingSnapshot,
        location: ValidatedLocation,
        points: MutableList<TrackPoint>,
    ): RecordingSnapshot {
        val point = TrackPoint(ids.newId(), state.trip.id, location.timeMs, location.point, location.speedMps,
            location.bearingDeg, location.accuracyM, grid.key(location.point), requireNotNull(state.segmentId),
            state.nextPointSequence)
        points.add(point)
        return state.copy(lastStored = point, lastStoredElapsedMs = location.elapsedMs,
            nextPointSequence = state.nextPointSequence + 1)
    }
}
