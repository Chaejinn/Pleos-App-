package com.pleos.explorer.presentation.collection

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import com.pleos.explorer.BuildConfig
import com.pleos.explorer.data.repository.LegacyReport
import com.pleos.explorer.presentation.components.ActionButton

@Composable
internal fun CollectionScreen(report: LegacyReport, onAcknowledge: (String) -> Unit,
    onShowMap: (com.pleos.explorer.domain.model.legacy.Landmark) -> Unit = {}) {
    LandmarkBrowser(report.landmarks,report.landmarkVisits,onShowMap,collectionOnly=true)
    Text("배지 도감", fontSize = 28.sp)
    if(report.regionCards.isNotEmpty()) Text(report.regionCatalogMessage, fontSize = 24.sp)
    if (report.regionNames.isNotEmpty()) Text("획득 ${report.regionBadges.count { it.regionCode in report.regionNames }} / ${report.regionNames.size}", fontSize = 24.sp)
    report.pendingRewards.firstOrNull()?.let { reward ->
        val code = reward.subjectId.removePrefix("REGION:")
        Text(when (reward.kind) {
            com.pleos.explorer.domain.model.RewardKind.BADGE -> "새 배지를 획득했어요 · ${report.regionNames[code] ?: code}"
            com.pleos.explorer.domain.model.RewardKind.TITLE -> "새 칭호를 획득했어요 · ${report.titles.firstOrNull { it.titleId == reward.subjectId }?.conditionText ?: reward.subjectId}"
            com.pleos.explorer.domain.model.RewardKind.GHOST_RESULT -> "지난 주행과 비교한 결과가 있어요"
        }, fontSize = 24.sp)
        ActionButton("획득 확인", onClick = { onAcknowledge(reward.id) })
    }
    if (report.titles.isNotEmpty()) Text("획득한 칭호", fontSize = 28.sp)
    report.titles.forEach { Text(it.conditionText, fontSize = 24.sp) }
    report.replayTitles.forEach { Text("[시연] ${it.title.conditionText}",fontSize=24.sp) }
    RegionBadgeGrid(report.regionCards,report.regionBadges)
    report.catalogAttributions.forEach { Text(it,fontSize=16.sp) }
    if (BuildConfig.DEBUG && report.collection.isNotBlank()) Text(report.collection, fontSize = 24.sp)
}
