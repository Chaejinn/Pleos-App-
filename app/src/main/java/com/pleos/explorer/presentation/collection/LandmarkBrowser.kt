package com.pleos.explorer.presentation.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pleos.explorer.domain.model.LandmarkVisit
import com.pleos.explorer.domain.model.legacy.Landmark
import com.pleos.explorer.domain.model.legacy.LandmarkType
import com.pleos.explorer.presentation.components.ActionButton
import com.pleos.explorer.presentation.components.SecondaryButton
import com.pleos.explorer.presentation.components.SectionHeading
import com.pleos.explorer.presentation.components.RoadIcon
import com.pleos.explorer.presentation.components.Forest
import com.pleos.explorer.presentation.components.Mist
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun LandmarkType.label(): String = when (this) {
    LandmarkType.NATURE -> "자연"
    LandmarkType.HISTORY -> "역사"
    LandmarkType.BRIDGE -> "다리"
    LandmarkType.TUNNEL -> "터널"
    LandmarkType.ATTRACTION -> "관광"
    LandmarkType.PASS -> "고개"
    LandmarkType.COASTAL_ROAD -> "해안"
    LandmarkType.REST_AREA -> "쉼터"
    LandmarkType.LANDMARK -> "명소"
}

@Composable
internal fun LandmarkBrowser(
    landmarks: List<Landmark>, visits: List<LandmarkVisit>,
    onShowMap: ((Landmark) -> Unit)? = null, collectionOnly: Boolean = false,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var replay by rememberSaveable { mutableStateOf(false) }
    var acquiredOnly by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable(query, replay, acquiredOnly) { mutableIntStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val filteredVisits = remember(visits, replay) { visits.filter { it.isReplay == replay }.associateBy { it.landmarkId } }
    val results = remember(landmarks, query, acquiredOnly, filteredVisits) {
        landmarks.filter { landmark ->
            (!acquiredOnly || landmark.id in filteredVisits) &&
                (query.isBlank() || "${landmark.name} ${landmark.region} ${landmark.type.label()}".contains(query.trim(), true))
        }
    }
    SectionHeading(if (collectionOnly) "나만의 탐험 도감" else "전국 랜드마크", "방문한 장소가 하나씩 나의 배지가 됩니다.")
    Text("${if (replay) "시연 수집" else "방문 수집"} ${filteredVisits.keys.count { id -> landmarks.any { it.id == id } }} / ${landmarks.size}",
        fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
    if (visits.any { it.isReplay }) SecondaryButton(if (replay) "실제 방문 기록 보기" else "시연 기록 보기") { replay = !replay }
    if (replay) Text("합성 위치로 획득한 시연 배지입니다. 실제 방문과 분리해 보관합니다.", fontSize = 24.sp)
    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("명소·지역·유형 검색", fontSize = 24.sp) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp), singleLine = true, textStyle = TextStyle(fontSize = 24.sp))
    SecondaryButton(if (acquiredOnly) "모든 명소 보기" else "수집한 명소만 보기") { acquiredOnly = !acquiredOnly }
    Text("${results.size}곳 · 반경 진입 시 자동 수집", fontSize = 24.sp)
    if (results.isEmpty()) Text("조건에 맞는 명소가 없어요. 검색어나 수집 필터를 바꿔보세요.", fontSize = 24.sp)
    val pages = ((results.size + 11) / 12).coerceAtLeast(1)
    val currentPage = page.coerceIn(0, pages - 1)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
      val columns = if (maxWidth >= 1000.dp) 3 else if (maxWidth >= 640.dp) 2 else 1
      Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
       results.drop(currentPage * 12).take(12).chunked(columns).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
         row.forEach { landmark ->
        val acquired = landmark.id in filteredVisits
        Card(onClick = { selectedId = landmark.id }, modifier = Modifier.weight(1f).heightIn(min = 160.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = if (acquired) Mist else Color(0xFFE7E8E2))) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(64.dp).background(if (acquired) Forest else Color(0xFF616C65), CircleShape),
                    contentAlignment = Alignment.Center) { RoadIcon(if (acquired) 1 else 0, color = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(landmark.name, style = MaterialTheme.typography.titleMedium)
                    Text("${landmark.region} · ${landmark.type.label()}", fontSize = 24.sp)
                    Text(if (acquired) "수집 완료" else "아직 만나지 않은 곳", fontSize = 24.sp,
                        color = if (acquired) Forest else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
         }
         repeat(columns - row.size) { Box(Modifier.weight(1f)) }
        }
       }
      }
    }
    if (pages > 1) {
        Text("${currentPage + 1} / $pages 페이지", fontSize = 24.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { ActionButton("이전", currentPage > 0) { page = currentPage - 1 } }
            Box(Modifier.weight(1f)) { ActionButton("다음", currentPage + 1 < pages) { page = currentPage + 1 } }
        }
    }
    val selected = landmarks.firstOrNull { it.id == selectedId }
    if (selected != null) LandmarkDetails(selected, filteredVisits[selected.id], onDismiss = { selectedId = null },
        onShowMap = onShowMap?.let { callback -> { selectedId = null; callback(selected) } })
}

@Composable
internal fun LandmarkDetails(landmark: Landmark, visit: LandmarkVisit?, onDismiss: () -> Unit,
    onShowMap: (() -> Unit)? = null, collected: Boolean = visit != null) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(landmark.name, fontSize = 24.sp) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${landmark.region} · ${landmark.type.label()}", fontSize = 24.sp)
                Text("수집 반경 ${landmark.radiusM.toInt()}m", fontSize = 24.sp)
                Text(landmark.story ?: "주변을 지나며 나만의 방문 기록을 남겨보세요.", fontSize = 24.sp)
                Text(if (visit == null) (if (collected) "수집 완료 · 도감에서 방문 기록을 확인하세요" else "아직 수집하지 않았어요") else {
                    val time = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm").withZone(ZoneId.of("Asia/Seoul"))
                        .format(Instant.ofEpochMilli(visit.acquiredAtMs))
                    "${if (visit.isReplay) "시연 획득" else "최초 방문"} $time"
                }, fontSize = 24.sp)
                if (visit != null) Text(if (visit.tripId == null) "연결된 주행이 없어요. 배지는 유지됩니다."
                    else "관련 주행은 주행 기록 탭에서 같은 방문 날짜로 확인할 수 있어요.", fontSize = 24.sp)
                Text("운전 중 조작하거나 명소 안으로 진입할 필요가 없습니다. 정차 후 내용을 확인하세요.", fontSize = 24.sp)
            }
        }, confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 76.dp)) { Text("닫기", fontSize = 24.sp) } },
        dismissButton = onShowMap?.let { action -> { TextButton(onClick = action, modifier = Modifier.heightIn(min = 76.dp)) {
            Text("지도에서 보기", fontSize = 24.sp)
        } } })
}
