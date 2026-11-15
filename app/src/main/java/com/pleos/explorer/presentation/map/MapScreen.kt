package com.pleos.explorer.presentation.map

import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.unit.sp
import com.pleos.explorer.BuildConfig
import com.pleos.explorer.service.RoadLogService

import com.pleos.explorer.presentation.components.ActionButton
import com.pleos.explorer.domain.model.RecordingSnapshot
import com.pleos.explorer.domain.model.TripStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun MapScreen(message: String, canStart: Boolean, onStartDrive: (String) -> Unit,
    onlineMapEnabled: Boolean, onOnlineMapChange: (Boolean) -> Unit, records: List<RecordingSnapshot>,
    landmarks: List<com.pleos.explorer.domain.model.legacy.Landmark> = emptyList(), visits: List<com.pleos.explorer.domain.model.LandmarkVisit> = emptyList(),
    selectedId: String? = null, onSelect: (String) -> Unit = {}, allowDriveStart: Boolean = true) {
    var confirmOnlineMap by remember { mutableStateOf(false) }
    LaunchedEffect(selectedId) { if(selectedId!=null && !onlineMapEnabled) confirmOnlineMap=true }
    Text("탐험 지도", fontSize = 28.sp)
    if (onlineMapEnabled) {
        val grouped = remember(records) {
            records.filter { it.status == TripStatus.FINISHED }.partition {
                Instant.ofEpochMilli(it.trip.startedAtMs).atZone(ZoneId.of("Asia/Seoul")).toLocalDate() == LocalDate.now(ZoneId.of("Asia/Seoul"))
            }
        }
        val focus = landmarks.firstOrNull { it.id == selectedId }?.let { com.pleos.explorer.domain.model.GeoPoint(it.lat, it.lon) }
            ?: records.firstNotNullOfOrNull { it.trip.simplifiedPath.lastOrNull()?.points?.lastOrNull() }
        RouteMap(grouped.second.flatMap { it.trip.simplifiedPath },
            grouped.first.flatMap { it.trip.simplifiedPath }, focus, landmarks = landmarks,
            collectedIds = visits.filterNot { it.isReplay }.map { it.landmarkId }.toSet(),landmarkVisits=visits)
        ActionButton("온라인 지도 끄기", onClick = { onOnlineMapChange(false) })
    } else {
        Text("지도 배경은 꺼져 있어요. 경로 기록은 인터넷 없이 사용할 수 있어요.", fontSize = 24.sp)
        ActionButton("온라인 지도 켜기", enabled = canStart, onClick = { confirmOnlineMap = true })
    }
    if (confirmOnlineMap) AlertDialog(onDismissRequest = { confirmOnlineMap = false },
        title = { Text("온라인 지도를 사용할까요?", fontSize = 24.sp) },
        text = { Text("카카오에서 지도를 불러옵니다. 지도 인증·표시를 위해 앱 정보, IP 주소와 지도 표시 영역 등이 카카오에 전달될 수 있어요. 저장된 주행 경로·차량 기록을 업로드하는 기능은 사용하지 않습니다.", fontSize = 24.sp) },
        confirmButton = { TextButton(onClick = { confirmOnlineMap = false; onOnlineMapChange(true) }) {
            Text("지도를 켜기", fontSize = 24.sp)
        } }, dismissButton = { TextButton(onClick = { confirmOnlineMap = false }) { Text("취소", fontSize = 24.sp) } })
    Text(message, fontSize = 24.sp)
    Text("정차 중 탐험을 시작하면 이동 경로와 명소 방문을 자동으로 기록합니다.", fontSize = 24.sp)
    ActionButton("GPS 탐험 시작", enabled = canStart && allowDriveStart,
        onClick = { onStartDrive(RoadLogService.MODE_EXPLORATION) })
    val context = androidx.compose.ui.platform.LocalContext.current
    if (context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE))
        ActionButton("Pleos 차량 신호 연결", enabled = canStart && allowDriveStart,
            onClick = { onStartDrive(RoadLogService.MODE_VEHICLE) })
    if (BuildConfig.DEBUG) {
        Text("개발용 가상 주행", fontSize = 24.sp)
        ActionButton("자동 시작·종료 판정 재생",enabled=canStart && allowDriveStart,
            onClick={onStartDrive(RoadLogService.MODE_LIFECYCLE_REPLAY)})
        ActionButton("위치 입력 기록 확인", enabled = canStart && allowDriveStart,
            onClick = { onStartDrive(RoadLogService.MODE_GPS_DEBUG) })
        ActionButton("명소 수집 시연 · 약 12초", enabled = canStart && allowDriveStart,
            onClick = { onStartDrive(RoadLogService.MODE_TRACK_REPLAY) })
        ActionButton("가상 주행 1", enabled = canStart && allowDriveStart,
            onClick = { onStartDrive(RoadLogService.MODE_DEMO_ROUGH) })
        ActionButton("가상 주행 2", enabled = canStart && allowDriveStart,
            onClick = { onStartDrive(RoadLogService.MODE_DEMO_SMOOTH) })
    }
    landmarks.firstOrNull { it.id == selectedId }?.let { Text("선택한 명소: ${it.name}", fontSize = 24.sp) }
    com.pleos.explorer.presentation.collection.LandmarkBrowser(landmarks, visits,
        onShowMap = { landmark -> onSelect(landmark.id); if (!onlineMapEnabled) confirmOnlineMap = true })
}
