package com.pleos.explorer.presentation.collection

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import com.pleos.explorer.BuildConfig

@Composable
internal fun CollectionScreen(report: com.pleos.explorer.data.repository.LegacyReport,
    onShowMap: (com.pleos.explorer.domain.model.legacy.Landmark) -> Unit) {
    LandmarkBrowser(report.landmarks, report.landmarkVisits, onShowMap, collectionOnly = true)
    Text("탐험 칭호", fontSize = 28.sp)
    if (report.titles.isEmpty()) Text("새로운 길 100km 탐험, 30일 지난 길 5km 재방문으로 칭호를 모아보세요.", fontSize = 24.sp)
    report.titles.forEach {
        Text("${if (it.isReplay) "[시연] " else ""}${it.title.conditionText}", fontSize = 24.sp)
    }
}
