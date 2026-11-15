package com.pleos.explorer.service

import com.pleos.explorer.data.local.recording.RoomTrackRecordingStore
import com.pleos.explorer.domain.engine.GhostDetector
import com.pleos.explorer.domain.model.*
import com.pleos.explorer.domain.port.HistoryRepository
import com.pleos.explorer.domain.port.IdGenerator
import com.pleos.explorer.domain.usecase.ProcessGhostComparisonUseCase
import com.pleos.explorer.domain.usecase.ProcessGhostInputUseCase

internal class GhostRecordingSession(store: RoomTrackRecordingStore, tracks: List<HistoricalTrack>) {
    private val ids = IdGenerator { java.util.UUID.randomUUID().toString() }
    private val history = object : HistoryRepository {
        // Version 1 keeps full retained tracks to preserve edge continuity across grid boundaries.
        override suspend fun findNearby(gridKeys: Set<String>, excludedTripId: String) = tracks.filter { it.tripId != excludedTripId }
        override suspend fun findForward(cursor: GhostCursor, maxDistanceM: Double) = tracks.single { it.tripId == cursor.key.tripId }
    }
    private val detector = ProcessGhostInputUseCase(store.ghosts, history, GhostDetector(ids))
    private val comparison = ProcessGhostComparisonUseCase(store.ghostComparisons, history, ids)
    private var tripId = ""
    private var sessionId = ""
    suspend fun start(trip: String, session: String) {
        tripId = trip; sessionId = session
        detector.start(trip, session)
        comparison.start(trip, session)
    }
    suspend fun resume(recording: TrackUpdate) {
        tripId = recording.snapshot.trip.id
        sessionId = recording.snapshot.sessionId
        val ghost = detector.resume(tripId, sessionId)
        comparison.resume(tripId, sessionId, GhostComparisonInput(recording, ghost))
    }
    suspend fun process(recording: TrackUpdate) {
        val input = if (recording.snapshot.status != TripStatus.ACTIVE) GhostInput.TripEnded
            else (recording.validation as? LocationValidation.Accepted)?.let {
                GhostInput.Location(it.location, recording.connectedDistanceM, recording.newSegment)
            } ?: GhostInput.ContinuityLost
        val next = recording.snapshot.lastInputSequence + if (recording.snapshot.status == TripStatus.ACTIVE) 0L else 1L
        val ghost = detector.process(tripId, sessionId, next, input)
        comparison.process(tripId, sessionId, next, GhostComparisonInput(recording, ghost))
    }
}
