package com.pleos.explorer.presentation.map

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import com.pleos.explorer.BuildConfig
import com.pleos.explorer.service.RoadLogService

import com.pleos.explorer.presentation.components.ActionButton

@Composable
internal fun RecordingScreen(mode: String?, onStop: () -> Unit, allowDebugStop: Boolean = true,
    message: String = "주행 기록 중 · 상세 정보는 정차 후 확인해 주세요",
    drive: com.pleos.explorer.service.DriveUiState? = null, onlineMapEnabled: Boolean = false) {
    Text(message, fontSize = 24.sp)
    if (onlineMapEnabled && drive?.currentPoint != null && allowDebugStop) {
        RouteMap(emptyList(), drive.livePath, drive.currentPoint, follow = true)
    }
    if (allowDebugStop && mode != RoadLogService.MODE_VEHICLE &&
        (BuildConfig.DEBUG || mode == RoadLogService.MODE_EXPLORATION)) {
        ActionButton(if (mode == RoadLogService.MODE_GPS_DEBUG || mode == RoadLogService.MODE_EXPLORATION)
            "탐험 종료" else "가상 주행 종료", onClick = onStop)
    }
}
