package com.pleos.explorer.domain.port

import com.pleos.explorer.domain.model.UserSettings
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val settings: StateFlow<UserSettings>
    suspend fun setRecordingConsent(granted: Boolean)
    suspend fun setTtsEnabled(enabled: Boolean)
}
