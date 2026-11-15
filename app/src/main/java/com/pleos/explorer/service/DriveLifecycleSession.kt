package com.pleos.explorer.service

import com.pleos.explorer.data.catalog.RegionCatalogState
import com.pleos.explorer.data.local.recording.RecordingInputSource
import com.pleos.explorer.data.local.recording.RoomTrackRecordingStore
import com.pleos.explorer.domain.engine.TrackRecorder
import com.pleos.explorer.domain.engine.TripStateMachine
import com.pleos.explorer.domain.model.*
import com.pleos.explorer.domain.port.IdGenerator
import com.pleos.explorer.domain.usecase.RecordTrackUseCase

internal data class DriveLifecycleOutcome(val lifecycle: TripLifecycleUpdate, val recordings: List<TrackUpdate>, val collectionChanged: Boolean = false)

/** Vehicle support and validated locations must come from the input adapter, never inferred here. */
internal class DriveLifecycleSession(
    private val store: RoomTrackRecordingStore,
    private val sessionId: String,
    private val source: RecordingInputSource,
    private val catalog: RegionCatalogState,
    private val history: List<HistoricalTrack> = emptyList(),
) {
    private val ids = IdGenerator { java.util.UUID.randomUUID().toString() }
    private val recorder = TrackRecorder(ids)
    private val recordingStore = if (source == RecordingInputSource.ANDROID_GPS) store.gpsTracks else com.pleos.explorer.data.local.recording.SourcedTrackStore(store,source)
    private val track = RecordTrackUseCase(recordingStore, recorder)
    private val machine = TripStateMachine(ids)

    suspend fun process(sequence: Long, input: TripInput): DriveLifecycleOutcome = store.atomic {
        val persisted = store.lifecycle(sessionId)
        val state = persisted ?: TripLifecycleSnapshot(sessionId)
        if (sequence <= state.lastInputSequence) return@atomic DriveLifecycleOutcome(TripLifecycleUpdate(state), emptyList())
        val update = machine.process(state, input, sequence)
        val recordings = mutableListOf<TrackUpdate>()
        var collectionChanged = false
        suspend fun handlers(tripId: String): Pair<RegionVisitSession, GhostRecordingSession> {
            val visits = RegionVisitSession(store, if (source == RecordingInputSource.ANDROID_GPS) catalog else RegionCatalogState.Missing)
            visits.start("$sessionId:parked", tripId)
            val ghosts = GhostRecordingSession(store, if (source == RecordingInputSource.ANDROID_GPS) history else emptyList())
            ghosts.start(tripId, sessionId)
            return visits to ghosts
        }
        suspend fun deliver(tripId: String, recording: TrackUpdate) {
            val (_, ghosts) = handlers(tripId)
            ghosts.process(recording)
            recordings.add(recording)
        }
        suspend fun append(tripId: String, location: ValidatedLocation) {
            val current = requireNotNull(store.load(tripId))
            deliver(tripId, track.process(tripId, sessionId, current.lastInputSequence + 1, LocationSample(
                location.timeMs,location.elapsedMs,location.point,location.speedMps,location.bearingDeg,location.accuracyM)))
        }
        update.transitions.forEach { transition -> when (transition) {
            is TripTransition.Started -> {
                track.start(transition.trip.id, transition.trip.startedAtMs, sessionId)
                store.setInputSource(transition.trip.id, source)
                handlers(transition.trip.id)
                transition.bufferedLocations.forEach { append(transition.trip.id,it) }
            }
            is TripTransition.Paused, is TripTransition.Resumed -> {
                val id = when (transition) {
                    is TripTransition.Paused -> transition.tripId
                    is TripTransition.Resumed -> transition.tripId
                    else -> error("Unexpected transition")
                }
                val current = requireNotNull(store.load(id))
                val interrupted = recorder.interrupt(current,current.lastInputSequence + 1)
                recordingStore.commit(current.revision, interrupted)
                deliver(id, interrupted)
            }
            is TripTransition.Ended -> {
                val (visits, ghosts) = handlers(transition.tripId)
                val finished = track.finish(transition.tripId, transition.atMs)
                if (source == RecordingInputSource.ANDROID_GPS) store.recordGpsTripAchievement(finished)
                if (finished.snapshot.status != TripStatus.DISCARDED) ghosts.process(finished)
                visits.finish(finished.snapshot.status == TripStatus.DISCARDED, transition.tripId,
                    breakContinuity = transition.reason == TripEndReason.ACCESS_REVOKED)
                recordings.add(finished)
            }
        } }
        val activeId = update.snapshot.tripId
        if (input is TripInput.Location && activeId != null && update.snapshot.recordingState == RecordingState.RECORDING) {
            if (update.recordCurrentLocation) append(activeId, requireNotNull((input.validation as? LocationValidation.Accepted)?.location))
            else if (input.validation is LocationValidation.Rejected) {
                val current = requireNotNull(store.load(activeId))
                val interrupted = recorder.interrupt(current,current.lastInputSequence + 1)
                recordingStore.commit(current.revision,interrupted)
                deliver(activeId,interrupted)
            }
        }
        if (input is TripInput.Location && source == RecordingInputSource.ANDROID_GPS &&
            update.snapshot.recordingConsentGranted && update.snapshot.locationPermissionGranted) {
            val visits = RegionVisitSession(store,catalog)
            if (visits.observeApproved(sessionId,input.validation)?.qualifiedVisits?.isNotEmpty() == true) collectionChanged = true
        }
        store.commitLifecycle(persisted?.revision,update.snapshot)
        DriveLifecycleOutcome(update, recordings, collectionChanged)
    }
}
