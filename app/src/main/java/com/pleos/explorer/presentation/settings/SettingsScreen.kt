package com.pleos.explorer.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.pleos.explorer.presentation.components.ActionButton

@Composable
internal fun SettingsScreen(ttsEnabled: Boolean, canDelete: Boolean, onTtsChange: (Boolean) -> Unit, onDelete: () -> Unit, onWithdraw: () -> Unit, onBack: () -> Unit) {
    Text("설정", fontSize = 28.sp)
    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("음성 알림", fontSize = 24.sp)
        Switch(checked = ttsEnabled, onCheckedChange = onTtsChange,
            modifier = Modifier.heightIn(min = 76.dp))
    }
    Text("음성 알림은 주행 중 허용되는 상황에서만 제공됩니다.", fontSize = 24.sp)
    ActionButton("기록 전체 삭제", enabled = canDelete,
        onClick = { onDelete() })
    ActionButton("기록 저장 동의 철회", onClick = { onWithdraw() })
    ActionButton("돌아가기", onClick = { onBack() })
}
