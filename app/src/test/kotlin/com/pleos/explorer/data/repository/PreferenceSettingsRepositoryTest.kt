package com.pleos.explorer.data.repository

import android.content.Context
import com.pleos.explorer.di.ExplorerApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ExplorerApplication::class)
class PreferenceSettingsRepositoryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clearSettings() {
        context.getSharedPreferences("recording_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun freshInstallDoesNotConsentOrEnableSpeech() {
        val repository = PreferenceSettingsRepository(context, Dispatchers.Unconfined)
        assertFalse(repository.settings.value.recordingConsentGranted)
        assertFalse(repository.settings.value.ttsEnabled)
    }

    @Test fun settingsSurviveRepositoryRecreation() = runBlocking {
        val repository = PreferenceSettingsRepository(context, Dispatchers.Unconfined)
        repository.setRecordingConsent(true)
        repository.setTtsEnabled(true)
        val restored = PreferenceSettingsRepository(context, Dispatchers.Unconfined)
        assertTrue(restored.settings.value.recordingConsentGranted)
        assertTrue(restored.settings.value.ttsEnabled)
    }

    @Test fun revokingConsentIsPersistedWithoutChangingVoicePreference() = runBlocking {
        val repository = PreferenceSettingsRepository(context, Dispatchers.Unconfined)
        repository.setRecordingConsent(true)
        repository.setTtsEnabled(true)
        repository.setRecordingConsent(false)
        val restored = PreferenceSettingsRepository(context, Dispatchers.Unconfined)
        assertFalse(restored.settings.value.recordingConsentGranted)
        assertTrue(restored.settings.value.ttsEnabled)
    }
}
