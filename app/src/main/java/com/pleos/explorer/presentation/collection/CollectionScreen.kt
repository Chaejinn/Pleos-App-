package com.pleos.explorer.presentation.collection

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import com.pleos.explorer.BuildConfig

@Composable
internal fun CollectionScreen(summary: String) {
    Text("배지 도감", fontSize = 28.sp)
    Text("지역 배지 수집을 준비하고 있어요", fontSize = 24.sp)
    if (BuildConfig.DEBUG) Text(summary, fontSize = 24.sp)
}
