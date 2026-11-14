package com.pleos.explorer.presentation.archive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.pleos.explorer.data.local.recording.RecordingInputSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos

@Composable
internal fun RecordingArchiveScreen(records: List<RecordingSnapshot>, sources: Map<String, RecordingInputSource>, enabled: Boolean,
    onDelete: (String) -> Unit, onFinishInterrupted: (String) -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    Text("검증 경로 기록", fontSize = 28.sp)
    Text("재생·위치 입력으로 검증한 기록입니다. 실제 차량 연동 결과와 구분해 주세요.", fontSize = 24.sp)
    if (records.isEmpty()) Text("저장된 검증 경로가 없어요", fontSize = 24.sp)
    val selected = records.firstOrNull { it.trip.id == selectedId }
    if (selected == null) records.forEach { record ->
        val status = if (record.status == TripStatus.ACTIVE) "중단 기록" else "완료"
        val source = sources[record.trip.id] ?: RecordingInputSource.UNKNOWN
        ActionButton("${date(record.trip.startedAtMs)} · $status · ${source.label}", enabled,
            onClick = { selectedId = record.trip.id })
    } else {
        val trip = selected.trip
        Text("입력: ${(sources[trip.id] ?: RecordingInputSource.UNKNOWN).label}", fontSize = 24.sp)
        Text("시작 ${date(trip.startedAtMs)}", fontSize = 24.sp)
        Text("종료 ${trip.endedAtMs?.let(::date) ?: "미완료"}", fontSize = 24.sp)
        Text(String.format(Locale.KOREA, "승인 경로 %.0fm · %d개 경로 구간", trip.distanceM,
            trip.simplifiedPath.size), fontSize = 24.sp)
        if (selected.status == TripStatus.ACTIVE) {
            Text("중단된 기록입니다. 저장된 지점까지만 복구하여 종료할 수 있어요.", fontSize = 24.sp)
            ActionButton("저장된 지점으로 복구 종료", enabled, onClick = { onFinishInterrupted(trip.id) })
        } else {
            Text("저장 경로 미리보기 · 지도 배경 없음", fontSize = 24.sp)
            SavedPath(selected)
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
