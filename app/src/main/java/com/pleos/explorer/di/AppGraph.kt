package com.pleos.explorer.di

import android.content.Context
import com.pleos.explorer.data.repository.PreferenceSettingsRepository
import com.pleos.explorer.domain.port.SettingsRepository
import com.pleos.explorer.service.DriveStatus
import com.pleos.explorer.platform.speech.SpeechGate

class AppGraph(context: Context) {
    val settingsRepository: SettingsRepository = PreferenceSettingsRepository(context)
    val driveStatus = DriveStatus()
    val speechGate = SpeechGate()

    companion object {
        fun from(context: Context): AppGraph = (context.applicationContext as ExplorerApplication).graph
    }
}
