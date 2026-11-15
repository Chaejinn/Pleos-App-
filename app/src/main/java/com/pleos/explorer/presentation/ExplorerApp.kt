package com.pleos.explorer.presentation

import com.pleos.explorer.presentation.archive.GhostResultsScreen

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
import com.pleos.explorer.presentation.archive.RecordingArchiveScreen
import com.pleos.explorer.presentation.collection.CollectionScreen
import com.pleos.explorer.presentation.components.ActionButton
import com.pleos.explorer.presentation.map.MapScreen
import com.pleos.explorer.presentation.map.RecordingScreen
import com.pleos.explorer.presentation.settings.ConsentScreen
import com.pleos.explorer.presentation.settings.SettingsScreen
import com.pleos.explorer.platform.ux.UxAvailability

@Composable
fun ExplorerApp(model: AppViewModel, onStartDrive: (String) -> Unit, onStop: () -> Unit) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val drive by model.drive.collectAsStateWithLifecycle()
    val report by model.report.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val ux by model.uxAccess.collectAsStateWithLifecycle()
    val onlineMapEnabled by model.onlineMapEnabled.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var confirmDeletion by rememberSaveable { mutableStateOf(false) }
    var postponedRewardId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedLandmarkId by rememberSaveable { mutableStateOf<String?>(null) }
    var postponedLandmark by rememberSaveable { mutableStateOf<String?>(null) }
    var postponedTitle by rememberSaveable { mutableStateOf<String?>(null) }
    val restricted = drive.running || !ux.canShowDetails
    LaunchedEffect(restricted) {
        if (restricted) { confirmDeletion = false; showSettings = false; tab = 0 }
    }
    var wasRunning by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(drive.running, drive.hasDeparted, drive.finishing, settings.recordingConsentGranted) {
        if (drive.running) wasRunning = true
        else if (wasRunning && !drive.hasDeparted && !drive.finishing) {
            wasRunning = false
            if (settings.recordingConsentGranted && ux.canShowDetails) { tab = 2; showSettings = false }
        }
    }

    MaterialTheme {
        val landmarkReward=report.landmarkVisits.firstOrNull { !it.acknowledged }
        val replayTitle=report.replayTitles.firstOrNull { !it.acknowledged }
        val showOtherRewards=!restricted && !drive.finishing && !drive.deleting && error==null && !confirmDeletion && settings.recordingConsentGranted && !showSettings && report.pendingRewards.isEmpty()
        if(showOtherRewards && landmarkReward!=null && landmarkReward.key!=postponedLandmark) {
            val landmark=report.landmarks.firstOrNull { it.id==landmarkReward.landmarkId }
            AlertDialog(onDismissRequest={postponedLandmark=landmarkReward.key},
                title={Text(if(landmarkReward.isReplay) "[시연] 명소를 수집했어요" else "명소를 수집했어요",fontSize=28.sp)},
                text={Text(landmark?.name ?: "새로운 명소 방문이 기록됐어요",fontSize=24.sp)},
                confirmButton={TextButton(onClick={model.acknowledgeLandmark(landmarkReward.key)}){Text("확인",fontSize=24.sp)}},
                dismissButton={TextButton(onClick={postponedLandmark=landmarkReward.key}){Text("나중에",fontSize=24.sp)}})
        } else if(showOtherRewards && landmarkReward==null && replayTitle!=null && replayTitle.key!=postponedTitle) {
            AlertDialog(onDismissRequest={postponedTitle=replayTitle.key},title={Text("[시연] 새 칭호",fontSize=28.sp)},
                text={Text(replayTitle.title.conditionText,fontSize=24.sp)},
                confirmButton={TextButton(onClick={model.acknowledgeTitle(replayTitle.key)}){Text("확인",fontSize=24.sp)}},
                dismissButton={TextButton(onClick={postponedTitle=replayTitle.key}){Text("나중에",fontSize=24.sp)}})
        }
        val reward = report.pendingRewards.firstOrNull()
        if (!restricted && !drive.finishing && !drive.deleting && error == null && !confirmDeletion && settings.recordingConsentGranted && !showSettings && reward != null && reward.id != postponedRewardId) {
            AlertDialog(onDismissRequest = { postponedRewardId = reward.id },
                title = { Text(when (reward.kind) {
                    com.pleos.explorer.domain.model.RewardKind.BADGE -> "새 배지를 획득했어요"
                    com.pleos.explorer.domain.model.RewardKind.TITLE -> "새 칭호를 획득했어요"
                    com.pleos.explorer.domain.model.RewardKind.GHOST_RESULT -> "지난 주행과 비교했어요"
                }, fontSize = 28.sp) },
                text = { Text(when (reward.kind) {
                    com.pleos.explorer.domain.model.RewardKind.BADGE -> report.regionNames[reward.subjectId.removePrefix("REGION:")] ?: "지역 방문이 기록됐어요"
                    com.pleos.explorer.domain.model.RewardKind.TITLE -> report.titles.firstOrNull { it.titleId == reward.subjectId }?.conditionText ?: "새로운 탐험 칭호가 기록됐어요"
                    com.pleos.explorer.domain.model.RewardKind.GHOST_RESULT -> "비교 결과를 주행 기록에서 확인할 수 있어요"
                }, fontSize = 24.sp) },
                confirmButton = { TextButton(onClick = { model.acknowledgeReward(reward.id) }) { Text("확인",fontSize=24.sp) } },
                dismissButton = { TextButton(onClick = { postponedRewardId = reward.id }) { Text("나중에",fontSize=24.sp) } })
        }
        if (confirmDeletion && !restricted) AlertDialog(
            onDismissRequest = { confirmDeletion = false },
            title = { Text("모든 기록을 삭제할까요?", fontSize = 24.sp) },
            text = { Text("주행 기록과 수집 기록을 삭제합니다. 동의와 음성 설정은 유지하며 자동으로 주행을 시작하지 않습니다.", fontSize = 24.sp) },
            confirmButton = { TextButton(enabled = !drive.monitoring, onClick = { confirmDeletion = false; model.deleteAll() }) {
                Text("전체 삭제", fontSize = 24.sp)
            } },
            dismissButton = { TextButton(onClick = { confirmDeletion = false }) { Text("취소", fontSize = 24.sp) } },
        )
        if (error != null && !restricted) AlertDialog(
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
                if (drive.running) {
                    RecordingScreen(drive.mode, onStop, allowDebugStop = ux.canShowDetails, message = drive.message,
                        drive = drive, onlineMapEnabled = onlineMapEnabled)
                } else if (!ux.canShowDetails) {
                    Text(if (ux.availability == UxAvailability.AVAILABLE)
                        "주행 중에는 상세 화면을 볼 수 없어요. 정차 후 이용해 주세요."
                    else "차량 상태를 확인하고 있어요. 확인될 때까지 상세 화면을 잠시 제한합니다.", fontSize = 24.sp)
                } else if (!settings.recordingConsentGranted) {
                    ConsentScreen(drive.deleting, { model.setConsent(true) }, { confirmDeletion = true })
                } else if (showSettings) {
                    if (drive.monitoring) Text("기록 대기를 종료한 후 기록을 삭제할 수 있어요",fontSize=24.sp)
                    SettingsScreen(
                        ttsEnabled = settings.ttsEnabled,
                        canDelete = !drive.deleting && !drive.finishing && !drive.monitoring,
                        onTtsChange = model::setTts,
                        onDelete = { confirmDeletion = true },
                        onWithdraw = { model.setConsent(false); showSettings = false },
                        onBack = { showSettings = false },
                    )
                } else {
                    when (tab) {
                        0 -> {
                            if (drive.monitoring) ActionButton("기록 대기 종료",onClick=onStop)
                            MapScreen(drive.message, !drive.finishing && !drive.deleting, onStartDrive,
                                onlineMapEnabled, model::setOnlineMap, report.recordings,
                                landmarks=report.landmarks,visits=report.landmarkVisits,selectedId=selectedLandmarkId,
                                onSelect={selectedLandmarkId=it},allowDriveStart = !drive.monitoring)
                        }
                        1 -> CollectionScreen(report, model::acknowledgeReward) { selectedLandmarkId=it.id;tab=0 }
                        2 -> {
                            GhostResultsScreen(report.ghostResults,report.inputSources)
                            RecordingArchiveScreen(report.recordings, report.inputSources, !drive.finishing && !drive.deleting,
                                model::deleteRecording, model::finishInterrupted, allowChanges = !drive.monitoring)
                            ArchiveScreen(report.trips, !drive.finishing && !drive.deleting && !drive.monitoring, model::deleteTrip)
                        }
                    }
                    ActionButton("설정", onClick = { showSettings = true })
                }
            }
        }
    }
}
