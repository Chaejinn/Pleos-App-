package com.pleos.explorer.presentation.map

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import com.pleos.explorer.BuildConfig
import com.pleos.explorer.service.RoadLogService

import com.pleos.explorer.presentation.components.ActionButton

@Composable
internal fun MapScreen(message: String, canStart: Boolean, onStartDrive: (String) -> Unit) {
    Text("탐험 지도", fontSize = 28.sp)
    Text("지도 표시를 준비하고 있어요", fontSize = 24.sp)
    Text(message, fontSize = 24.sp)
    ActionButton("주행 시작", enabled = canStart,
        onClick = { onStartDrive(RoadLogService.MODE_VEHICLE) })
    if (BuildConfig.DEBUG) {
        Text("개발용 가상 주행", fontSize = 24.sp)
        ActionButton("가상 주행 1", enabled = canStart,
            onClick = { onStartDrive(RoadLogService.MODE_DEMO_ROUGH) })
        ActionButton("가상 주행 2", enabled = canStart,
            onClick = { onStartDrive(RoadLogService.MODE_DEMO_SMOOTH) })
    }
}
