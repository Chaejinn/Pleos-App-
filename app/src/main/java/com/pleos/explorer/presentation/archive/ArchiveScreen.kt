package com.pleos.explorer.presentation.archive

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp

@Composable
internal fun ArchiveScreen(summary: String) {
    Text("주행 기록", fontSize = 28.sp)
    Text(summary, fontSize = 24.sp)
}
