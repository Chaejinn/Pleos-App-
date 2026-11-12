package com.pleos.explorer.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.pleos.explorer.presentation.archive.ArchiveScreen
import com.pleos.explorer.presentation.collection.CollectionScreen
import com.pleos.explorer.presentation.components.ActionButton
import com.pleos.explorer.presentation.map.MapScreen
import com.pleos.explorer.presentation.map.RecordingScreen
import com.pleos.explorer.presentation.settings.ConsentScreen
import com.pleos.explorer.presentation.settings.SettingsScreen

@Composable
fun ExplorerApp(model: AppViewModel, onStartDrive: (String) -> Unit, onStop: () -> Unit) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val drive by model.drive.collectAsStateWithLifecycle()
    val report by model.report.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var confirmDeletion by rememberSaveable { mutableStateOf(false) }
    val restricted = drive.running
    var wasRunning by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(drive.running, drive.finishing, settings.recordingConsentGranted) {
        if (drive.running) wasRunning = true
        else if (wasRunning && !drive.finishing) {
            wasRunning = false
            if (settings.recordingConsentGranted) { tab = 2; showSettings = false }
        }
    }

    MaterialTheme {
        if (confirmDeletion && !restricted) AlertDialog(
            onDismissRequest = { confirmDeletion = false },
            title = { Text("모든 기록을 삭제할까요?", fontSize = 24.sp) },
            text = { Text("주행 기록과 수집 기록을 삭제합니다. 동의와 음성 설정은 유지하며 자동으로 주행을 시작하지 않습니다.", fontSize = 24.sp) },
            confirmButton = { TextButton(onClick = { confirmDeletion = false; model.deleteAll() }) {
                Text("전체 삭제", fontSize = 24.sp)
            } },
            dismissButton = { TextButton(onClick = { confirmDeletion = false }) { Text("취소", fontSize = 24.sp) } },
        )
        if (error != null) AlertDialog(
            onDismissRequest = model::clearError,
            title = { Text("확인이 필요해요", fontSize = 24.sp) },
            text = { Text(error.orEmpty(), fontSize = 24.sp) },
            confirmButton = { TextButton(onClick = model::clearError) { Text("확인", fontSize = 24.sp) } },
        )
        Scaffold(bottomBar = {
            if (settings.recordingConsentGranted && !restricted) NavigationBar {
                listOf("탐험 지도", "배지 도감", "주행 기록").forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index; showSettings = false },
                        icon = { Text((index + 1).toString(), fontSize = 24.sp) },
                        label = { Text(label, fontSize = 24.sp) }, modifier = Modifier.heightIn(min = 76.dp))
                }
            }
        }) { padding ->
            var content = Modifier.fillMaxSize().padding(padding).padding(24.dp)
            if (!restricted) content = content.verticalScroll(rememberScrollState())
            Column(content, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("로드로그", fontSize = 32.sp)
                if (!settings.recordingConsentGranted) {
                    ConsentScreen(drive.deleting, { model.setConsent(true) }, { confirmDeletion = true })
                } else if (restricted) {
                    RecordingScreen(drive.mode, onStop)
                } else if (showSettings) {
                    SettingsScreen(
                        ttsEnabled = settings.ttsEnabled,
                        canDelete = !drive.deleting && !drive.finishing,
                        onTtsChange = model::setTts,
                        onDelete = { confirmDeletion = true },
                        onWithdraw = { model.setConsent(false); showSettings = false },
                        onBack = { showSettings = false },
                    )
                } else {
                    when (tab) {
                        0 -> MapScreen(drive.message, !drive.finishing && !drive.deleting, onStartDrive)
                        1 -> CollectionScreen(report.collection)
                        2 -> ArchiveScreen(report.trips, !drive.finishing && !drive.deleting, model::deleteTrip)
                    }
                    ActionButton("설정", onClick = { showSettings = true })
                }
            }
        }
    }
}
