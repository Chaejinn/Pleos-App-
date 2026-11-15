package com.pleos.explorer.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.pleos.explorer.presentation.archive.ArchiveScreen
import com.pleos.explorer.presentation.archive.RecordingArchiveScreen
import com.pleos.explorer.presentation.collection.CollectionScreen
import com.pleos.explorer.presentation.components.ActionButton
import com.pleos.explorer.presentation.components.RoadLogTheme
import com.pleos.explorer.presentation.components.RoadIcon
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
    var selectedLandmarkId by rememberSaveable { mutableStateOf<String?>(null) }
    val screenScroll = rememberScrollState()
    LaunchedEffect(tab, showSettings, selectedLandmarkId) { screenScroll.scrollTo(0) }
    val restricted = drive.running || !ux.canShowDetails
    LaunchedEffect(restricted) {
        if (restricted) { confirmDeletion = false; showSettings = false; tab = 0 }
    }
    var wasRunning by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(drive.running, drive.finishing, settings.recordingConsentGranted) {
        if (drive.running) wasRunning = true
        else if (wasRunning && !drive.finishing) {
            wasRunning = false
            if (settings.recordingConsentGranted && ux.canShowDetails) { tab = 2; showSettings = false }
        }
    }

    RoadLogTheme {
        val reward = report.landmarkVisits.firstOrNull { !it.acknowledged }
        val rewardLandmark = report.landmarks.firstOrNull { it.id == reward?.landmarkId }
        val titleReward = report.titles.firstOrNull { !it.acknowledged }
        if (reward == null && titleReward != null && !restricted && !drive.finishing && !drive.deleting &&
            settings.recordingConsentGranted && error == null && !confirmDeletion) AlertDialog(
            onDismissRequest = { model.acknowledgeTitle(titleReward.key) },
            title = { Text(if (titleReward.isReplay) "시연 칭호를 획득했어요" else "새 칭호를 획득했어요", fontSize = 24.sp) },
            text = { Text(titleReward.title.conditionText, fontSize = 24.sp) },
            confirmButton = { TextButton(onClick = { model.acknowledgeTitle(titleReward.key) },
                modifier = Modifier.heightIn(min = 76.dp)) { Text("확인", fontSize = 24.sp) } },
        )
        if (reward != null && rewardLandmark != null && !restricted && !drive.finishing &&
            !drive.deleting && settings.recordingConsentGranted && error == null && !confirmDeletion) AlertDialog(
            onDismissRequest = { model.acknowledgeLandmark(reward.key) },
            title = { Text(if (reward.isReplay) "시연 배지를 수집했어요" else "새 명소를 수집했어요", fontSize = 24.sp) },
            text = { Text("${rewardLandmark.name}\n${rewardLandmark.region} · 도감에서 방문 기록을 확인하세요." +
                if (reward.isReplay) "\n합성 경로 시연이며 실제 방문과 구분됩니다." else "", fontSize = 24.sp) },
            confirmButton = { TextButton(onClick = { model.acknowledgeLandmark(reward.key) },
                modifier = Modifier.heightIn(min = 76.dp)) { Text("확인", fontSize = 24.sp) } },
        )
        if (confirmDeletion && !restricted) AlertDialog(
            onDismissRequest = { confirmDeletion = false },
            title = { Text("모든 기록을 삭제할까요?", fontSize = 24.sp) },
            text = { Text("주행 기록과 수집 기록을 삭제합니다. 동의와 음성 설정은 유지하며 자동으로 주행을 시작하지 않습니다.", fontSize = 24.sp) },
            confirmButton = { TextButton(onClick = { confirmDeletion = false; model.deleteAll() }) {
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
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val navigationVisible = settings.recordingConsentGranted && !restricted
        val labels = listOf("지도", "도감", "기록")
        Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).heightIn(min = 76.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoadIcon(0)
                    Text("로드로그", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (navigationVisible) TextButton(onClick = { showSettings = !showSettings },
                        modifier = Modifier.heightIn(min = 76.dp)) { Text(if (showSettings) "닫기" else "설정") }
                }
            }
        }, bottomBar = {
            if (navigationVisible && !wide) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index && !showSettings,
                        onClick = { tab = index; showSettings = false }, icon = { RoadIcon(index) },
                        label = { Text(label) }, modifier = Modifier.heightIn(min = 76.dp))
                }
            }
        }) { padding ->
          Row(Modifier.fillMaxSize().padding(padding)) {
            if (wide && navigationVisible) NavigationRail(Modifier.fillMaxHeight().width(112.dp),
                containerColor = MaterialTheme.colorScheme.surface) {
                labels.forEachIndexed { index, label ->
                    NavigationRailItem(selected = tab == index && !showSettings,
                        onClick = { tab = index; showSettings = false }, icon = { RoadIcon(index) },
                        label = { Text(label) }, modifier = Modifier.padding(vertical = 12.dp).heightIn(min = 76.dp))
                }
            }
            var content = Modifier.weight(1f).fillMaxHeight()
            if (!restricted) content = content.verticalScroll(screenScroll)
            Column(content.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
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
                        0 -> MapScreen(drive.message, !drive.finishing && !drive.deleting, onStartDrive,
                            onlineMapEnabled, model::setOnlineMap, report.recordings, report.landmarks,
                            report.landmarkVisits, selectedLandmarkId, { selectedLandmarkId = it })
                        1 -> CollectionScreen(report) { selectedLandmarkId = it.id; tab = 0 }
                        2 -> {
                            RecordingArchiveScreen(report.recordings, report.inputSources, !drive.finishing && !drive.deleting,
                                model::deleteRecording, model::finishInterrupted, report.landmarks, report.landmarkVisits,
                                onlineMapEnabled, report.ghostResults)
                            if (report.trips.isNotEmpty()) ArchiveScreen(report.trips,
                                !drive.finishing && !drive.deleting, model::deleteTrip)
                        }
                    }
                }
            }
          }
        }
        }
    }
}
