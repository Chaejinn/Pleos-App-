package com.pleos.explorer.presentation.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp

import com.pleos.explorer.presentation.components.ActionButton

@Composable
internal fun ConsentScreen(deleting: Boolean, onConsent: () -> Unit, onDelete: () -> Unit) {
    Text("위치 기록 저장 동의", fontSize = 28.sp)
    Text("주행 경로와 방문·차량 기록을 기기에 저장합니다. 기록을 시작하려면 위치 권한과 저장 동의가 필요합니다. 동의 전에는 주행 기록을 만들지 않습니다.", fontSize = 24.sp)
    Text("동의는 설정에서 철회할 수 있습니다. 철회하면 수집을 멈추며 기존 기록은 유지합니다. 음성 알림은 기본으로 꺼져 있습니다.", fontSize = 24.sp)
    ActionButton("동의하고 계속", onClick = { onConsent() })
    Text("동의하지 않으면 주행 기록 기능을 사용할 수 없습니다.", fontSize = 24.sp)
    ActionButton("기록 전체 삭제", enabled = !deleting,
        onClick = { onDelete() })
}
