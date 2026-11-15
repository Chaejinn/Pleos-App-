package com.pleos.explorer.service

import com.pleos.explorer.data.catalog.RegionCatalogState
import com.pleos.explorer.data.local.recording.RoomTrackRecordingStore
import com.pleos.explorer.domain.engine.RegionVisitDetector
import com.pleos.explorer.domain.model.*
import com.pleos.explorer.domain.port.IdGenerator
import com.pleos.explorer.domain.usecase.ProcessRegionVisitUseCase

/** Approved GPS input uses the production catalog; synthetic replay stays separate. */
internal class RegionVisitSession(private val store: RoomTrackRecordingStore, catalog: RegionCatalogState) {
    private val engine = (catalog as? RegionCatalogState.Ready)?.let {
        ProcessRegionVisitUseCase(store.regionVisits, RegionVisitDetector(it.index), IdGenerator { java.util.UUID.randomUUID().toString() })
    }
    private var session: String? = null
    private var sequence = 0L
    suspend fun start(sessionId: String, tripId: String) {
        val useCase = engine ?: return
        val previous = store.regionVisits.load()
        if (previous == null) useCase.start(sessionId, tripId)
        else if (previous.sessionId == sessionId) {
            sequence = previous.lastInputSequence + 1
            if (previous.tripId != tripId) useCase.process(sessionId, sequence++, RegionInput.TripStarted(tripId))
        }
        else {
            useCase.resume(sessionId)
            useCase.process(sessionId, sequence++, RegionInput.TripStarted(tripId))
        }
        session = sessionId
    }
    suspend fun process(update: TrackUpdate): RegionVisitUpdate? {
        val id = session ?: return null
        val validation = update.validation
        return if (validation is LocationValidation.Accepted) engine?.process(id, sequence++, RegionInput.Location(
            validation.location, update.connectedDistanceM, update.newSegment))
        else engine?.process(id, sequence++, RegionInput.ContinuityLost)
    }
    suspend fun observeApproved(rootSession: String, validation: LocationValidation): RegionVisitUpdate? {
        val useCase = engine ?: return null
        val old = store.regionVisits.load()
        val id = old?.sessionId?.takeIf { it.startsWith("$rootSession:") } ?: "$rootSession:parked"
        val current = when {
            old == null -> useCase.start(id).snapshot
            old.sessionId != id -> useCase.resume(id).snapshot
            else -> old
        }
        if (validation !is LocationValidation.Accepted)
            return useCase.process(id,current.lastInputSequence + 1,RegionInput.ContinuityLost)
        val previous = current.previous
        val location = validation.location
        val connected = previous != null && location.elapsedMs > previous.elapsedMs &&
            location.elapsedMs - previous.elapsedMs < DetectionPolicy.LOCATION_GAP_MS
        val distance = if (connected) com.pleos.explorer.domain.geo.GeoDistance.meters(requireNotNull(previous).point,location.point) else 0.0
        return useCase.process(id,current.lastInputSequence + 1,RegionInput.Location(location,distance,!connected))
    }
    suspend fun finish(discarded: Boolean, tripId: String, breakContinuity: Boolean = true) {
        val id = session ?: return
        engine?.process(id, sequence++, if (discarded) RegionInput.TripDetached(tripId) else RegionInput.TripEnded)
        if (breakContinuity) engine?.process(id, sequence++, RegionInput.ContinuityLost)
        session = null
    }
}
