package com.pleos.explorer.presentation.map

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.pleos.explorer.presentation.components.TravelPanel
import com.pleos.explorer.presentation.components.RoadIcon
import com.pleos.explorer.BuildConfig
import com.pleos.explorer.service.RoadLogService

import com.pleos.explorer.presentation.components.ActionButton

@Composable
internal fun RecordingScreen(mode: String?, onStop: () -> Unit, allowDebugStop: Boolean = true,
    message: String = "주행 기록 중 · 상세 정보는 정차 후 확인해 주세요",
    drive: com.pleos.explorer.service.DriveUiState? = null, onlineMapEnabled: Boolean = false) {
    val replay = mode == RoadLogService.MODE_TRACK_REPLAY || mode == RoadLogService.MODE_DEMO_ROUGH ||
        mode == RoadLogService.MODE_DEMO_SMOOTH
    val tall = LocalConfiguration.current.screenHeightDp >= 650
    TravelPanel(dark = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RoadIcon(0, color = androidx.compose.ui.graphics.Color.White)
            Text(if (replay) "가상 경로를 탐험하고 있어요" else "당신의 길을 기록하고 있어요",
                style = MaterialTheme.typography.titleMedium)
        }
        Text(message, fontSize = 24.sp)
        if (tall) Text(if (replay) "시연 결과는 실제 방문과 구분됩니다." else "수집한 배지와 지난 길의 이야기는 정차 후에 만나요.", fontSize = 24.sp)
    }
    if (allowDebugStop && mode != RoadLogService.MODE_VEHICLE &&
        (BuildConfig.DEBUG || mode == RoadLogService.MODE_EXPLORATION)) {
        ActionButton(if (mode == RoadLogService.MODE_GPS_DEBUG || mode == RoadLogService.MODE_EXPLORATION)
            "탐험 종료" else "가상 주행 종료", onClick = onStop)
    }
    if (tall && onlineMapEnabled && drive?.currentPoint != null && allowDebugStop) {
        RouteMap(emptyList(), drive.livePath, drive.currentPoint, follow = true)
    }
}
