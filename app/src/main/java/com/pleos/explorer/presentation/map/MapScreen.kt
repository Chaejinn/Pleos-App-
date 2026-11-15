package com.pleos.explorer.presentation.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pleos.explorer.BuildConfig
import com.pleos.explorer.domain.model.GeoPoint
import com.pleos.explorer.domain.model.LandmarkVisit
import com.pleos.explorer.domain.model.RecordingSnapshot
import com.pleos.explorer.domain.model.TripStatus
import com.pleos.explorer.domain.model.legacy.Landmark
import com.pleos.explorer.presentation.collection.LandmarkBrowser
import com.pleos.explorer.presentation.components.ActionButton
import com.pleos.explorer.presentation.components.SecondaryButton
import com.pleos.explorer.presentation.components.JourneyArt
import com.pleos.explorer.presentation.components.SectionHeading
import com.pleos.explorer.presentation.components.TravelPanel
import com.pleos.explorer.service.RoadLogService
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun MapScreen(message: String, canStart: Boolean, onStartDrive: (String) -> Unit,
    onlineMapEnabled: Boolean, onOnlineMapChange: (Boolean) -> Unit, records: List<RecordingSnapshot>,
    landmarks: List<Landmark>, visits: List<LandmarkVisit>, selectedId: String?, onSelect: (String) -> Unit) {
    var confirmOnlineMap by remember { mutableStateOf(false) }
    var showDemo by rememberSaveable { mutableStateOf(false) }
    var browse by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val realVisits = remember(visits) { visits.filterNot { it.isReplay }.map { it.landmarkId }.toSet() }
    val selected = landmarks.firstOrNull { it.id == selectedId }
    SectionHeading("지나온 길이, 나의 이야기로", "새로운 길을 달리고 전국의 명소를 모아보세요.")
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 840.dp
        val mapContent: @Composable () -> Unit = {
            if (onlineMapEnabled) {
                val grouped = remember(records) {
                    records.filter { it.status == TripStatus.FINISHED }.partition {
                        Instant.ofEpochMilli(it.trip.startedAtMs).atZone(ZoneId.of("Asia/Seoul")).toLocalDate() ==
                            LocalDate.now(ZoneId.of("Asia/Seoul"))
                    }
                }
                val focus = selected?.let { GeoPoint(it.lat, it.lon) }
                    ?: records.firstNotNullOfOrNull { it.trip.simplifiedPath.lastOrNull()?.points?.lastOrNull() }
                RouteMap(grouped.second.flatMap { it.trip.simplifiedPath },
                    grouped.first.flatMap { it.trip.simplifiedPath }, focus,
                    modifier = Modifier.clip(RoundedCornerShape(24.dp)), landmarks = landmarks, collectedIds = realVisits)
                if (selected != null) Text("선택한 명소 · ${selected.name}", fontWeight = FontWeight.SemiBold)
                SecondaryButton("온라인 지도 끄기") { onOnlineMapChange(false) }
            } else {
                TravelPanel(dark = true) {
                    Text("오늘은 어떤 길을 만나볼까요?", style = MaterialTheme.typography.titleLarge)
                    JourneyArt(Modifier.fillMaxWidth().height(180.dp))
                    Text("전국 ${landmarks.size}개 명소가 기다리고 있어요.")
                    Text("지도 없이도 경로 기록과 명소 수집은 계속됩니다.")
                }
                ActionButton("카카오 탐험 지도 열기", canStart) { confirmOnlineMap = true }
            }
        }
        val actions: @Composable () -> Unit = {
            TravelPanel {
                Text("나의 탐험", style = MaterialTheme.typography.titleLarge)
                Text("${realVisits.size} / ${landmarks.size}", style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary)
                Text("방문한 랜드마크")
                ActionButton("탐험 시작", canStart) { onStartDrive(RoadLogService.MODE_EXPLORATION) }
                Text("정차 중 시작하세요. 이동 경로와 명소 방문을 자동으로 기록해요.")
                if (message != "주행을 시작해 주세요") Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE))
                    SecondaryButton("Pleos 차량 연결", canStart) { onStartDrive(RoadLogService.MODE_VEHICLE) }
            }
        }
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { mapContent() }
            Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { actions() }
        } else Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { mapContent(); actions() }
    }
    SecondaryButton(if (browse) "명소 목록 접기" else "전국 명소 둘러보기") { browse = !browse }
    if (browse) LandmarkBrowser(landmarks, visits, onShowMap = { landmark ->
        onSelect(landmark.id)
        if (!onlineMapEnabled) confirmOnlineMap = true
    })
    if (BuildConfig.DEBUG) {
        SecondaryButton(if (showDemo) "시연 메뉴 접기" else "가상 주행 체험") { showDemo = !showDemo }
        if (showDemo) TravelPanel {
            Text("명소 수집 → 기록 → 고스트", style = MaterialTheme.typography.titleMedium)
            Text("같은 경로를 두 번 재생하면 과거 주행과 만납니다. 시연 결과는 실제 방문과 분리돼요.")
            ActionButton("명소 수집 시연 · 약 12초", canStart) { onStartDrive(RoadLogService.MODE_TRACK_REPLAY) }
            SecondaryButton("GPS 입력 확인", canStart) { onStartDrive(RoadLogService.MODE_GPS_DEBUG) }
            SecondaryButton("차량 신호 시연 1", canStart) { onStartDrive(RoadLogService.MODE_DEMO_ROUGH) }
            SecondaryButton("차량 신호 시연 2", canStart) { onStartDrive(RoadLogService.MODE_DEMO_SMOOTH) }
        }
    }
    if (confirmOnlineMap) AlertDialog(onDismissRequest = { confirmOnlineMap = false },
        title = { Text("카카오지도를 연결할까요?") },
        text = { Text("지도 인증과 표시를 위해 앱 정보, IP 주소와 지도 표시 영역 등이 카카오에 전달될 수 있어요. 저장된 주행 기록을 업로드하는 기능은 사용하지 않습니다.") },
        confirmButton = { TextButton(onClick = { confirmOnlineMap = false; onOnlineMapChange(true) }) { Text("지도 열기") } },
        dismissButton = { TextButton(onClick = { confirmOnlineMap = false }) { Text("취소") } })
}
