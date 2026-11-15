package com.pleos.explorer.presentation.archive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pleos.explorer.domain.model.RecordingSnapshot
import com.pleos.explorer.domain.model.TripStatus
import com.pleos.explorer.presentation.components.ActionButton
import com.pleos.explorer.presentation.components.SectionHeading
import com.pleos.explorer.presentation.components.TravelPanel
import com.pleos.explorer.presentation.components.RoadIcon
import com.pleos.explorer.presentation.components.JourneyArt
import com.pleos.explorer.presentation.components.SecondaryButton
import com.pleos.explorer.data.local.recording.RecordingInputSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos

@Composable
internal fun RecordingArchiveScreen(records: List<RecordingSnapshot>, sources: Map<String, RecordingInputSource>, enabled: Boolean,
    onDelete: (String) -> Unit, onFinishInterrupted: (String) -> Unit,
    landmarks: List<com.pleos.explorer.domain.model.legacy.Landmark> = emptyList(),
    visits: List<com.pleos.explorer.domain.model.LandmarkVisit> = emptyList(), onlineMapEnabled: Boolean = false,
    ghostResults: List<com.pleos.explorer.domain.model.GhostResult> = emptyList()) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var ghostMapId by rememberSaveable(selectedId) { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    SectionHeading("길 위에 남긴 이야기", "지나온 경로와 수집한 명소를 다시 만나보세요.")
    if (records.isEmpty()) TravelPanel {
        JourneyArt(Modifier.fillMaxWidth().height(140.dp))
        Text("첫 번째 탐험을 기다리고 있어요", style = MaterialTheme.typography.titleMedium)
        Text("지도에서 탐험을 시작해 보세요. 200m 이상 이동한 경로가 여기에 남습니다.")
    }
    val selected = records.firstOrNull { it.trip.id == selectedId }
    if (selected == null) records.forEach { record ->
        val status = if (record.status == TripStatus.ACTIVE) "중단 기록" else "완료"
        val source = sources[record.trip.id] ?: RecordingInputSource.UNKNOWN
        Card(onClick = { selectedId = record.trip.id }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp), shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                RoadIcon(2)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(date(record.trip.startedAtMs), style = MaterialTheme.typography.titleMedium)
                    Text(String.format(Locale.KOREA, "%.2f km · 명소 %d곳", record.trip.distanceM / 1000,
                        visits.count { it.tripId == record.trip.id }))
                    Text("$status · ${source.label}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    } else {
        val trip = selected.trip
        Text("입력: ${(sources[trip.id] ?: RecordingInputSource.UNKNOWN).label}", fontSize = 24.sp)
        Text("시작 ${date(trip.startedAtMs)}", fontSize = 24.sp)
        Text("종료 ${trip.endedAtMs?.let(::date) ?: "미완료"}", fontSize = 24.sp)
        Text(String.format(Locale.KOREA, "승인 경로 %.0fm · %d개 경로 구간", trip.distanceM,
            trip.simplifiedPath.size), fontSize = 24.sp)
        val acquired = visits.filter { it.tripId == trip.id }
        Text("이 탐험에서 수집한 명소 ${acquired.size}곳", fontSize = 24.sp)
        acquired.forEach { visit ->
            landmarks.firstOrNull { it.id == visit.landmarkId }?.let { Text("${it.region} · ${it.name}", fontSize = 24.sp) }
        }
        if (selected.status == TripStatus.ACTIVE) {
            Text("중단된 기록입니다. 저장된 지점까지만 복구하여 종료할 수 있어요.", fontSize = 24.sp)
            ActionButton("저장된 지점으로 복구 종료", enabled, onClick = { onFinishInterrupted(trip.id) })
        } else {
            Text("저장 경로 미리보기 · 지도 배경 없음", fontSize = 24.sp)
            SavedPath(selected)
            if (onlineMapEnabled && ghostMapId == null) com.pleos.explorer.presentation.map.RouteMap(emptyList(), trip.simplifiedPath,
                trip.simplifiedPath.firstOrNull()?.points?.firstOrNull(), landmarks = landmarks.filter { landmark ->
                    acquired.any { it.landmarkId == landmark.id }
                }, collectedIds = acquired.map { it.landmarkId }.toSet())
            val ghosts = ghostResults.filter { it.tripId == trip.id }
            Text("과거의 나와 만난 구간 ${ghosts.size}개", fontSize = 24.sp)
            ghosts.forEach { result ->
                Text(String.format(Locale.KOREA, "같은 방향 %.0fm · 이후 새로운 길 %.0fm", result.matchedDistanceM,
                    result.newRoadM), fontSize = 24.sp)
                Text("처음 지난 날 ${date(result.firstPassedAtMs)}", fontSize = 24.sp)
                Text("차량 주행 특성 비교: 측정 데이터 없음", fontSize = 24.sp)
                if (onlineMapEnabled) {
                    SecondaryButton(if (ghostMapId == result.id) "비교 지도 닫기" else "과거 경로 겹쳐 보기") {
                        ghostMapId = if (ghostMapId == result.id) null else result.id
                    }
                    if (ghostMapId == result.id) com.pleos.explorer.presentation.map.RouteMap(result.pastPath,
                        result.currentPath, result.currentPath.firstOrNull()?.points?.firstOrNull())
                }
            }
            ActionButton("이 경로 기록 삭제", enabled, onClick = { deletingId = trip.id })
        }
        ActionButton("경로 목록으로", onClick = { selectedId = null })
    }
    val id = deletingId
    if (id != null) AlertDialog(onDismissRequest = { deletingId = null },
        title = { Text("경로 기록을 삭제할까요?", fontSize = 24.sp) },
        text = { Text("선택한 주행과 원본 경로를 삭제합니다. 누적 탐험 기록은 유지합니다.", fontSize = 24.sp) },
        confirmButton = { TextButton(enabled = enabled, onClick = { onDelete(id); deletingId = null; selectedId = null }) {
            Text("삭제", fontSize = 24.sp)
        } }, dismissButton = { TextButton(onClick = { deletingId = null }) { Text("취소", fontSize = 24.sp) } })
}

@Composable
private fun SavedPath(record: RecordingSnapshot) {
    val segments = record.trip.simplifiedPath
    val points = segments.flatMap { it.points }
    if (points.isEmpty()) return
    Canvas(Modifier.fillMaxWidth().height(220.dp)) {
        val minLat = points.minOf { it.latitudeDeg }
        val maxLat = points.maxOf { it.latitudeDeg }
        val minLon = points.minOf { it.longitudeDeg }
        val maxLon = points.maxOf { it.longitudeDeg }
        val longitudeScale = cos(Math.toRadians((minLat + maxLat) / 2)).coerceAtLeast(0.01)
        val dx = ((maxLon - minLon) * longitudeScale).coerceAtLeast(0.000001)
        val dy = (maxLat - minLat).coerceAtLeast(0.000001)
        val scale = minOf((size.width - 32.dp.toPx()) / dx, (size.height - 32.dp.toPx()) / dy)
        val left = (size.width - dx * scale) / 2
        val top = (size.height - dy * scale) / 2
        segments.forEach { segment ->
            val path = Path()
            segment.points.forEachIndexed { i, point ->
                val x = (left + (point.longitudeDeg - minLon) * longitudeScale * scale).toFloat()
                val y = (top + (maxLat - point.latitudeDeg) * scale).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, Color(0xFF6750A4), style = Stroke(width = 4.dp.toPx()))
        }
    }
}

private fun date(atMs: Long): String = DateTimeFormatter.ofPattern("MM.dd HH:mm:ss")
    .withZone(ZoneId.of("Asia/Seoul")).format(Instant.ofEpochMilli(atMs))
