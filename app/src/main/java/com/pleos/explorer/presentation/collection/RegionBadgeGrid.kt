package com.pleos.explorer.presentation.collection

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pleos.explorer.R
import com.pleos.explorer.data.catalog.RegionCard
import com.pleos.explorer.data.catalog.BadgeThumbnail
import com.pleos.explorer.domain.model.RegionBadge
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun RegionBadgeGrid(cards: List<RegionCard>, badges: List<RegionBadge>) {
    var selectedCode by rememberSaveable { mutableStateOf<String?>(null) }
    val byCode = remember(badges) { badges.associateBy { it.regionCode } }
    cards.groupBy { it.provinceCode }.toSortedMap().forEach { (_, group) ->
        Text(group.first().provinceName,fontSize=26.sp)
        group.sortedBy { it.name }.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                row.forEach { card ->
                    val acquired = card.code in byCode
                    OutlinedCard(Modifier.weight(1f).heightIn(min=150.dp).clickable(role=Role.Button) { selectedCode=card.code }) {
                        Column(Modifier.fillMaxWidth().padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                            BadgeArtwork(card,acquired)
                            Text(card.name,fontSize=20.sp)
                            Text(if (acquired) "획득" else "미획득",fontSize=18.sp)
                        }
                    }
                }
                repeat(3-row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    val selected = cards.firstOrNull { it.code == selectedCode }
    if (selected != null) {
        val badge = byCode[selected.code]
        AlertDialog(onDismissRequest={selectedCode=null},title={Text(selected.name,fontSize=28.sp)},
            text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                BadgeArtwork(selected,badge != null)
                Text("지역 안에서 1km 이동 또는 3분 체류하면 획득해요",fontSize=24.sp)
                if (badge != null) {
                    val date = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm").withZone(ZoneId.of("Asia/Seoul"))
                    Text("획득 ${date.format(Instant.ofEpochMilli(badge.acquiredAtMs))}",fontSize=24.sp)
                    Text("방문 ${badge.visitCount}회",fontSize=24.sp)
                } else Text("아직 방문하지 않은 지역이에요",fontSize=24.sp)
            }},confirmButton={TextButton(onClick={selectedCode=null}) {Text("닫기",fontSize=24.sp)}})
    }
}

@Composable
private fun BadgeArtwork(card: RegionCard, acquired: Boolean) {
    val context = LocalContext.current
    val bitmap = remember(context,card.badgeAssetPath) {
        card.badgeAssetPath?.let { path -> runCatching {
            BadgeThumbnail.decode { context.assets.open("catalog/$path") }?.asImageBitmap()
        }.getOrNull() }
    }
    Surface(shape=CircleShape,color=if (acquired) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant) {
        val modifier = Modifier.size(72.dp).padding(8.dp).alpha(if (acquired) 1f else 0.3f)
        if (bitmap != null) Image(bitmap,contentDescription=null,modifier=modifier)
        else Image(painterResource(R.drawable.ic_launcher_foreground),contentDescription=null,modifier=modifier)
    }
}
