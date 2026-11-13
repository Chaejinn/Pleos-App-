package com.pleos.explorer.domain.usecase

import com.pleos.explorer.domain.engine.LocationValidator
import com.pleos.explorer.domain.engine.TrackRecorder
import com.pleos.explorer.domain.model.LocationSample
import com.pleos.explorer.domain.model.LocationValidation
import com.pleos.explorer.domain.model.RecordingSnapshot
import com.pleos.explorer.domain.model.TrackUpdate
import com.pleos.explorer.domain.model.TripStatus
import com.pleos.explorer.domain.port.TrackRecordingStore

class RecordTrackUseCase(
    private val store: TrackRecordingStore,
    private val recorder: TrackRecorder,
    private val validator: LocationValidator = LocationValidator(),
) {
    suspend fun start(tripId: String, startedAtMs: Long, sessionId: String): TrackUpdate {
        val current = store.load(tripId)
        if (current != null) {
            require(current.trip.startedAtMs == startedAtMs && current.sessionId == sessionId)
            return TrackUpdate(current)
        }
        return recorder.start(tripId, startedAtMs, sessionId).also { store.commit(null, it) }
    }

    suspend fun process(tripId: String, sessionId: String, sequence: Long, sample: LocationSample): TrackUpdate {
        val state = active(tripId)
        require(sessionId == state.sessionId && sequence >= 0)
        if (sequence <= state.lastInputSequence) return TrackUpdate(state)
        val validation = validator.validate(sample, state.previous)
        val roads = if (validation is LocationValidation.Accepted) {
            store.findRoads(recorder.requiredGridKeys(state, validation.location))
        } else emptyMap()
        val update = recorder.process(state, validation, sequence, roads)
        store.commit(state.revision, update)
        return update
    }

    suspend fun resume(tripId: String, sessionId: String): TrackUpdate {
        val state = active(tripId)
        if (state.sessionId == sessionId) return TrackUpdate(state)
        return recorder.resume(state, sessionId).also { store.commit(state.revision, it) }
    }

    suspend fun finish(tripId: String, atMs: Long): TrackUpdate {
        val state = requireNotNull(store.load(tripId)) { "Unknown trip" }
        if (state.status != TripStatus.ACTIVE) return TrackUpdate(state)
        return recorder.finish(state, atMs, store.loadPoints(tripId)).also { store.commit(state.revision, it) }
    }

    private suspend fun active(tripId: String): RecordingSnapshot {
        val state = requireNotNull(store.load(tripId)) { "Unknown trip" }
        check(state.status == TripStatus.ACTIVE) { "Trip is closed" }
        return state
    }
}
