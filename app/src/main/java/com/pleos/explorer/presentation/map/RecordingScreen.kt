package com.pleos.explorer.presentation.map

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import com.pleos.explorer.BuildConfig
import com.pleos.explorer.service.RoadLogService

import com.pleos.explorer.presentation.components.ActionButton

@Composable
internal fun RecordingScreen(mode: String?, onStop: () -> Unit) {
    Text("주행 기록 중 · 상세 정보는 정차 후 확인해 주세요", fontSize = 24.sp)
    if (BuildConfig.DEBUG && mode != RoadLogService.MODE_VEHICLE) {
        ActionButton("가상 주행 종료", onClick = onStop)
    }
}
