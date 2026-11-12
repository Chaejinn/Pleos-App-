package com.pleos.explorer.data.repository

import android.content.Context
import com.pleos.explorer.domain.model.UserSettings
import com.pleos.explorer.domain.port.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PreferenceSettingsRepository(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SettingsRepository {
    private val preferences = context.applicationContext.getSharedPreferences("recording_settings", Context.MODE_PRIVATE)
    private val changes = Mutex()
    private val mutableSettings = MutableStateFlow(
        UserSettings(
            recordingConsentGranted = preferences.getBoolean("recording_consent", false),
            ttsEnabled = preferences.getBoolean("tts_enabled", false),
        ),
    )
    override val settings = mutableSettings.asStateFlow()

    override suspend fun setRecordingConsent(granted: Boolean) = update { it.copy(recordingConsentGranted = granted) }
    override suspend fun setTtsEnabled(enabled: Boolean) = update { it.copy(ttsEnabled = enabled) }

    private suspend fun update(change: (UserSettings) -> UserSettings) {
        changes.withLock {
            val next = change(mutableSettings.value)
            withContext(ioDispatcher) {
                check(preferences.edit()
                    .putBoolean("recording_consent", next.recordingConsentGranted)
                    .putBoolean("tts_enabled", next.ttsEnabled)
                    .commit()) { "설정을 저장하지 못했습니다" }
            }
            mutableSettings.value = next
        }
    }
}
