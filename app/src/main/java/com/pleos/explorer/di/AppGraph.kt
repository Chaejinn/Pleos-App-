package com.pleos.explorer.di

import android.content.Context
import com.pleos.explorer.data.repository.PreferenceSettingsRepository
import com.pleos.explorer.domain.port.SettingsRepository
import com.pleos.explorer.service.DriveStatus
import com.pleos.explorer.platform.speech.SpeechGate
import com.pleos.explorer.data.local.recording.RoomTrackRecordingStore
import com.pleos.explorer.domain.engine.TrackRecorder
import com.pleos.explorer.domain.port.IdGenerator
import com.pleos.explorer.domain.usecase.RecordTrackUseCase

class AppGraph(context: Context) {
    private val applicationContext = context.applicationContext
    val trackRecordingStore by lazy { RoomTrackRecordingStore.create(applicationContext) }
    fun newTrackRecordingUseCase() = RecordTrackUseCase(trackRecordingStore,
        TrackRecorder(IdGenerator { java.util.UUID.randomUUID().toString() }))
    val settingsRepository: SettingsRepository = PreferenceSettingsRepository(context)
    val driveStatus = DriveStatus()
    val speechGate = SpeechGate()

    companion object {
        fun from(context: Context): AppGraph = (context.applicationContext as ExplorerApplication).graph
    }
}
