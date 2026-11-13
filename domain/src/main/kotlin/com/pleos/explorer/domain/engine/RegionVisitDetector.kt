package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.geo.GeoDistance
import com.pleos.explorer.domain.geo.RegionGeometryResolver
import com.pleos.explorer.domain.model.DetectionPolicy
import com.pleos.explorer.domain.model.RegionInput
import com.pleos.explorer.domain.model.RegionVisitCandidate
import com.pleos.explorer.domain.model.RegionVisitSnapshot
import com.pleos.explorer.domain.model.RegionVisitUpdate
import com.pleos.explorer.domain.model.VisitQualified
import com.pleos.explorer.domain.port.RegionCatalog
import kotlin.math.abs

class RegionVisitDetector(catalog: RegionCatalog) {
    private val geometry = RegionGeometryResolver(catalog)

    fun process(state: RegionVisitSnapshot, input: RegionInput, inputSequence: Long): RegionVisitUpdate {
        require(inputSequence >= 0 && inputSequence > state.lastInputSequence)
        val next = state.copy(revision = Math.addExact(state.revision, 1), lastInputSequence = inputSequence)
        return when (input) {
            is RegionInput.Location -> processLocation(next, input)
            is RegionInput.Tick -> {
                require(input.elapsedMs >= 0 && input.timeMs >= 0)
                RegionVisitUpdate(next)
            }
            is RegionInput.TripStarted -> {
                require(input.tripId.isNotBlank())
                RegionVisitUpdate(next.copy(tripId = input.tripId))
            }
            RegionInput.TripEnded -> RegionVisitUpdate(next)
            RegionInput.ContinuityLost -> RegionVisitUpdate(breakContinuity(next))
        }
    }

    fun resume(state: RegionVisitSnapshot, sessionId: String): RegionVisitUpdate {
        require(sessionId.isNotBlank() && sessionId != state.sessionId)
        return RegionVisitUpdate(breakContinuity(state).copy(sessionId = sessionId,
            revision = Math.addExact(state.revision, 1), lastInputSequence = -1,
            lastLocationElapsedMs = null))
    }

    private fun processLocation(state: RegionVisitSnapshot, input: RegionInput.Location): RegionVisitUpdate {
        require(input.connectedDistanceM.isFinite() && input.connectedDistanceM >= 0)
        val location = input.location
        if (location.elapsedMs <= (state.lastLocationElapsedMs ?: -1)) return RegionVisitUpdate(state)
        val previous = state.previous
        val connected = previous != null && !input.newSegment &&
            location.elapsedMs - previous.elapsedMs < DetectionPolicy.LOCATION_GAP_MS
        var next = if (connected) state else breakContinuity(state)
        val qualified = mutableListOf<VisitQualified>()
        if (connected) {
            val distance = GeoDistance.meters(requireNotNull(previous).point, location.point)
            require(abs(distance - input.connectedDistanceM) <= maxOf(0.001, distance * 1e-6)) {
                "Connected distance must match the approved F03 segment"
            }
            val sections = geometry.split(previous.point, location.point, state.candidate?.regionCode)
            val continuousRegion = sections.size == 1 && sections.single().regionCode == state.candidate?.regionCode
            sections.forEach { section ->
                next = enter(next, section.regionCode)
                val candidate = next.candidate ?: return@forEach
                val updated = candidate.copy(
                    distanceM = candidate.distanceM + input.connectedDistanceM *
                        (section.endFraction - section.startFraction),
                    dwellMs = if (continuousRegion) Math.addExact(candidate.dwellMs,
                        location.elapsedMs - previous.elapsedMs) else candidate.dwellMs,
                )
                val grants = !updated.qualified && (updated.distanceM >= DetectionPolicy.REGION_VISIT_DISTANCE_M ||
                    updated.dwellMs >= DetectionPolicy.REGION_VISIT_DWELL_MS)
                if (grants) qualified.add(VisitQualified(updated.regionCode, next.tripId,
                    updated.entrySequence, location.timeMs))
                next = next.copy(candidate = updated.copy(qualified = updated.qualified || grants))
            }
        } else require(input.connectedDistanceM == 0.0) { "Disconnected location cannot carry distance" }
        next = enter(next, geometry.locate(location.point, next.candidate?.regionCode))
        next = next.copy(previous = location, lastLocationElapsedMs = location.elapsedMs)
        return RegionVisitUpdate(next, qualified.toList())
    }

    private fun enter(state: RegionVisitSnapshot, regionCode: String?): RegionVisitSnapshot {
        if (regionCode == state.candidate?.regionCode) return state
        if (regionCode == null) return state.copy(candidate = null)
        return state.copy(candidate = RegionVisitCandidate(regionCode, state.nextEntrySequence),
            nextEntrySequence = Math.addExact(state.nextEntrySequence, 1))
    }

    private fun breakContinuity(state: RegionVisitSnapshot): RegionVisitSnapshot = state.copy(
        previous = null,
        candidate = state.candidate?.let { if (it.qualified) it else it.copy(distanceM = 0.0, dwellMs = 0) },
    )
}
