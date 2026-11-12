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
) : ViewModel() {
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
        if (drive.value.running || drive.value.finishing || drive.value.deleting) return
        driveStatus.deleting = true
        viewModelScope.launch {
            try {
                reports.deleteAll()
                mutableReport.value = LegacyReport()
                driveStatus.clearChannels()
                driveStatus.message = "모든 기록을 삭제했어요"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableError.value = "기록 삭제를 완료하지 못했어요. 다시 시도해 주세요" }
            finally { driveStatus.deleting = false }
        }
    }

    fun setConsent(granted: Boolean) = change { settingsRepository.setRecordingConsent(granted) }
    fun deleteTrip(id: String) {
        if (drive.value.running || drive.value.finishing || drive.value.deleting) return
        driveStatus.deleting = true
        viewModelScope.launch {
            try { reports.deleteTrip(id); loadReport() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableError.value = "주행 삭제를 완료하지 못했어요" }
            finally { driveStatus.deleting = false }
        }
    }
    fun setTts(enabled: Boolean) = change { settingsRepository.setTtsEnabled(enabled) }
    fun clearError() { mutableError.value = null }
    private fun change(action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableError.value = "설정을 저장하지 못했어요. 다시 시도해 주세요" }
        }
    }
}
