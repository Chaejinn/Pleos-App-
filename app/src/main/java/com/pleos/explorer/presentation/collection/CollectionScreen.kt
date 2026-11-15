package com.pleos.explorer.presentation.collection

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import com.pleos.explorer.BuildConfig
import com.pleos.explorer.presentation.components.TravelPanel
import androidx.compose.material3.MaterialTheme

@Composable
internal fun CollectionScreen(report: com.pleos.explorer.data.repository.LegacyReport,
    onShowMap: (com.pleos.explorer.domain.model.legacy.Landmark) -> Unit) {
    LandmarkBrowser(report.landmarks, report.landmarkVisits, onShowMap, collectionOnly = true)
    TravelPanel(dark = true) {
        Text("길이 쌓이면, 새로운 칭호", style = MaterialTheme.typography.titleLarge)
        if (report.titles.isEmpty()) {
            Text("새로운 길 발견자 · 처음 기록한 도로 100km", fontSize = 24.sp)
            Text("다시 찾은 길 · 30일 지난 길 5km 재방문", fontSize = 24.sp)
        }
        report.titles.forEach {
            Text("${if (it.isReplay) "[시연] " else ""}${it.title.conditionText}", fontSize = 24.sp)
        }
    }
}
