package com.pleos.explorer.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pleos.explorer.data.repository.LegacyReport
import com.pleos.explorer.data.repository.LegacyReportRepository
import com.pleos.explorer.domain.port.SettingsRepository
import com.pleos.explorer.service.DriveStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AppViewModel(
    private val settingsRepository: SettingsRepository,
    private val driveStatus: DriveStatus,
    private val reports: LegacyReportRepository,
    private val uxAccessGate: com.pleos.explorer.platform.ux.UxAccessGate,
    private val mapPreferences: com.pleos.explorer.data.repository.MapPreferences? = null,
) : ViewModel() {
    val onlineMapEnabled = mapPreferences?.enabled ?: MutableStateFlow(false).asStateFlow()
    fun setOnlineMap(enabled: Boolean) {
        val repository = mapPreferences ?: return
        if (!enabled || (canInteract() && settings.value.recordingConsentGranted))
            change(allowRestricted = !enabled) { repository.setEnabled(enabled) }
    }
    val uxAccess = uxAccessGate.state
    val settings = settingsRepository.settings
    val drive = driveStatus.state
    private val mutableReport = MutableStateFlow(LegacyReport())
    val report = mutableReport.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    init {
        viewModelScope.launch {
            drive.collectLatest { state ->
                if (!state.running && settings.value.recordingConsentGranted) loadReport()
            }
        }
        viewModelScope.launch {
            settings.collectLatest { value ->
                if (value.recordingConsentGranted && !drive.value.running) loadReport()
                if (!value.recordingConsentGranted) mutableReport.value = LegacyReport()
            }
        }
    }

    private suspend fun loadReport() {
        try { mutableReport.value = reports.load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { mutableError.value = "주행 기록을 읽지 못했어요" }
    }

    fun deleteAll() {
        if (!canInteract()) return
        driveStatus.deleting = true
        viewModelScope.launch {
            try {
                if (!uxAccess.value.canShowDetails) return@launch
                reports.deleteAll()
                mutableReport.value = LegacyReport()
                driveStatus.clearChannels()
                driveStatus.message = "모든 기록을 삭제했어요"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableError.value = "기록 삭제를 완료하지 못했어요. 다시 시도해 주세요" }
            finally { driveStatus.deleting = false }
        }
    }

    fun setConsent(granted: Boolean) {
        if (!granted || canInteract()) change(allowRestricted = !granted) { settingsRepository.setRecordingConsent(granted) }
    }
    fun deleteTrip(id: String) {
        if (!canInteract()) return
        driveStatus.deleting = true
        viewModelScope.launch {
            try {
                if (!uxAccess.value.canShowDetails) return@launch
                reports.deleteTrip(id); loadReport()
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableError.value = "주행 삭제를 완료하지 못했어요" }
            finally { driveStatus.deleting = false }
        }
    }
    fun setTts(enabled: Boolean) {
        if (!enabled || canInteract()) change(allowRestricted = !enabled) { settingsRepository.setTtsEnabled(enabled) }
    }
    fun deleteRecording(id: String) = editRecording { reports.deleteRecording(id) }
    fun finishInterrupted(id: String) = editRecording { reports.finishInterrupted(id) }
    private fun editRecording(action: suspend () -> Unit) {
        if (!canInteract() ||
            !settings.value.recordingConsentGranted) return
        driveStatus.deleting = true
        viewModelScope.launch {
            try {
                if (!uxAccess.value.canShowDetails) return@launch
                action(); loadReport()
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableError.value = "기록 변경을 완료하지 못했어요" }
            finally { driveStatus.deleting = false }
        }
    }
    fun clearError() { mutableError.value = null }
    private fun canInteract() = uxAccess.value.canShowDetails && !drive.value.running &&
        !drive.value.finishing && !drive.value.deleting
    private fun change(allowRestricted: Boolean = false, action: suspend () -> Unit) {
        viewModelScope.launch {
            if (!allowRestricted && !uxAccess.value.canShowDetails) return@launch
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableError.value = "설정을 저장하지 못했어요. 다시 시도해 주세요" }
        }
    }
}
