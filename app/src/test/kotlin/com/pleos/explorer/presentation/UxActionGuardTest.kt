package com.pleos.explorer.presentation

import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.pleos.explorer.data.repository.LegacyReportRepository
import com.pleos.explorer.di.ExplorerApplication
import com.pleos.explorer.domain.model.UserSettings
import com.pleos.explorer.domain.port.SettingsRepository
import com.pleos.explorer.platform.ux.*
import com.pleos.explorer.service.DriveStatus
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ExplorerApplication::class)
class UxActionGuardTest {
    @Test fun restrictedStateBlocksStaleDeleteAndEnableActionsEvenWhenServiceIsIdle() {
        val context = RuntimeEnvironment.getApplication()
        context.filesDir.resolve("trips.json").writeText("[]")
        val gate = UxAccessGate()
        val session = gate.begin()
        gate.update(session, UxAccessState(UxAvailability.AVAILABLE, true, 1))
        val settings = MemorySettings()
        val drive = DriveStatus()
        val model = AppViewModel(settings, drive, LegacyReportRepository(context), gate)
        val models = ViewModelStore().also { it.put("app", model) }
        try {
            model.setConsent(true)
            model.setTts(true)
            model.deleteAll()
            model.deleteTrip("old")
            model.deleteRecording("new")
            model.finishInterrupted("new")
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(settings.settings.value.recordingConsentGranted)
            assertFalse(settings.settings.value.ttsEnabled)
            assertFalse(drive.state.value.running)
            assertFalse(drive.state.value.deleting)
            assertTrue(context.filesDir.resolve("trips.json").exists())
        } finally { models.clear() }
    }

    @Test fun restrictedStateStillAllowsConsentWithdrawalAndVoiceOff() {
        val context = RuntimeEnvironment.getApplication()
        val gate = UxAccessGate()
        val settings = MemorySettings(UserSettings(recordingConsentGranted = true, ttsEnabled = true))
        val drive = DriveStatus().apply { running = true }
        val model = AppViewModel(settings, drive, LegacyReportRepository(context), gate)
        val models = ViewModelStore().also { it.put("app", model) }
        try {
            model.setTts(false)
            model.setConsent(false)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(settings.settings.value.recordingConsentGranted)
            assertFalse(settings.settings.value.ttsEnabled)
        } finally { models.clear() }
    }

    private class MemorySettings(initial: UserSettings = UserSettings()) : SettingsRepository {
        override val settings = MutableStateFlow(initial)
        override suspend fun setRecordingConsent(granted: Boolean) {
            settings.value = settings.value.copy(recordingConsentGranted = granted)
        }
        override suspend fun setTtsEnabled(enabled: Boolean) {
            settings.value = settings.value.copy(ttsEnabled = enabled)
        }
    }
}
