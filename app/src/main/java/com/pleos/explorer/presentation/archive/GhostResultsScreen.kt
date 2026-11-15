package com.pleos.explorer.presentation.archive

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.sp
import com.pleos.explorer.domain.model.GhostResult
import com.pleos.explorer.presentation.components.ActionButton
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun GhostResultsScreen(results: List<GhostResult>, sources: Map<String,com.pleos.explorer.data.local.recording.RecordingInputSource> = emptyMap()) {
    if (results.isEmpty()) return
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    Text("지난 주행과 비교", fontSize = 28.sp)
    results.sortedByDescending { it.passages.lastOrNull()?.currentPassedAtMs ?: 0L }.forEach { result ->
        Text(when(sources[result.tripId]) {
            com.pleos.explorer.data.local.recording.RecordingInputSource.REPLAY -> "[시연] 합성 경로 비교"
            com.pleos.explorer.data.local.recording.RecordingInputSource.ANDROID_GPS -> "Android 위치 입력 비교"
            else -> "입력 종류 미확인"
        },fontSize=20.sp)
        val date = DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneId.of("Asia/Seoul"))
        Text("같은 방향으로 ${result.matchedDistanceM.toInt()}m 다시 달렸어요", fontSize = 24.sp)
        Text("첫 통과 ${date.format(Instant.ofEpochMilli(result.firstPassedAtMs))} · 이전 통과 ${date.format(Instant.ofEpochMilli(result.previousLastPassedAtMs))}", fontSize = 24.sp)
        Text(if (result.finalized) "이후 새로 탐험한 길 ${result.newRoadM.toInt()}m" else "추가 주행을 반영 중인 결과예요", fontSize = 24.sp)
        Text("주행 지표 비교는 지원되는 차량 신호 확인 후 제공해요", fontSize = 24.sp)
        if (result.pastTripId == null) Text("이전 주행 원본은 삭제됐으며 비교 경로만 보관돼 있어요", fontSize = 24.sp)
        ActionButton(if (selected==result.id) "경로 비교 접기" else "겹친 경로 보기",onClick={selected=if(selected==result.id)null else result.id})
        if (selected==result.id) {
            Text("연한 선은 지난 주행, 진한 선은 이번 주행이에요 · 지도 배경 없는 경로 비교",fontSize=24.sp)
            GhostPathPreview(result.pastPath,result.currentPath)
        }
    }
}
