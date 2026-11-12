package com.pleos.explorer.presentation.archive

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.sp
import com.pleos.explorer.domain.model.legacy.Trip
import com.pleos.explorer.presentation.components.ActionButton
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun ArchiveScreen(trips: List<Trip>, canDelete: Boolean, onDelete: (String) -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = trips.firstOrNull { it.id == selectedId }
    Text("주행 기록", fontSize = 28.sp)
    Text("기존 데모 기록 · 최근 30개 보관", fontSize = 24.sp)
    if (trips.isEmpty()) Text("아직 주행 기록이 없어요", fontSize = 24.sp)
    if (selected == null) {
        trips.forEachIndexed { index, trip ->
            ActionButton("기록 ${trips.size - index} · ${trip.segments.size}구간", onClick = { selectedId = trip.id })
        }
    } else {
        Text(tripDate(selected), fontSize = 24.sp)
        Text("기록 구간 ${selected.segments.size}개 · 경로 격자 ${selected.route.size}개", fontSize = 24.sp)
        Text("기존 기록에는 정확한 거리·종료 시각·차량 신호 지원 여부가 저장되지 않았어요. 아래 수치는 데모 집계입니다.", fontSize = 24.sp)
        selected.segments.forEach { segment ->
            Text("${segment.index + 1}구간\n급제동 ${segment.harshBrakes} · 급조향 ${segment.harshSteers}\n방향지시등 누락 ${segment.turnsWithoutSignal}", fontSize = 24.sp)
        }
        ActionButton("이 주행 삭제", enabled = canDelete, onClick = { deletingId = selected.id })
        ActionButton("목록으로", onClick = { selectedId = null })
    }
    val deleteId = deletingId
    if (deleteId != null) AlertDialog(
        onDismissRequest = { deletingId = null },
        title = { Text("이 주행을 삭제할까요?", fontSize = 24.sp) },
        text = { Text("선택한 주행만 삭제하고 수집한 명소 기록은 유지합니다.", fontSize = 24.sp) },
        confirmButton = { TextButton(enabled = canDelete, onClick = {
            onDelete(deleteId); deletingId = null; selectedId = null
        }) { Text("삭제", fontSize = 24.sp) } },
        dismissButton = { TextButton(onClick = { deletingId = null }) { Text("취소", fontSize = 24.sp) } },
    )
}

private fun tripDate(trip: Trip): String = if (trip.startedAtMs < 946684800000L) {
    "시작 날짜 없음 · 기존 기록의 상대 시각"
} else DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")
    .withZone(ZoneId.of("Asia/Seoul")).format(Instant.ofEpochMilli(trip.startedAtMs))
