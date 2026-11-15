package com.pleos.explorer.demo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pleos.explorer.platform.vehicle.SeoulDemoRoute
import kotlinx.coroutines.delay
import java.util.Locale

internal val Ink = Color(0xFF193D38)
internal val Muted = Color(0xFF76847F)
internal val Paper = Color(0xFFF7F8F3)
internal val Accent = Color(0xFFE9AA59)
internal val Line = Color(0xFFE0E6DE)

@Composable
fun DemoApp(model: DemoViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(state.rewardId) {
        if (state.rewardId != null) { delay(6000); model.dismissReward() }
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = Ink, background = Paper, surface = Color.White)) {
        Row(Modifier.fillMaxSize().background(Paper)) {
            Column(Modifier.width(76.dp).fillMaxHeight().background(Ink).padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("R", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(27.dp))
                listOf("탐험" to "⌁", "도감" to "▦", "기록" to "◷").forEachIndexed { index, item ->
                    TextButton(onClick = { tab = index }, modifier = Modifier.fillMaxWidth().height(68.dp),
                        shape = RoundedCornerShape(0.dp), colors = ButtonDefaults.textButtonColors(
                            containerColor = if (tab == index) Color(0xFF30544B) else Color.Transparent)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(item.second, fontSize = 23.sp, color = if (tab == index) Accent else Color(0xFF9DB4AA))
                            Text(item.first, fontSize = 12.sp, color = Color.White)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("PLEOS", color = Color(0xFFB4C4B8), fontSize = 10.sp, letterSpacing = 1.sp)
            }
            Column(Modifier.weight(1f).padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
                Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("roadlog", color = Ink, fontSize = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                    Spacer(Modifier.width(14.dp))
                    Text("길 위에서 모으는 나만의 이야기", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Surface(color = Color(0xFFE8EEE4), shape = CircleShape) {
                        Text("●  서울 시연 모드", color = Ink, fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }
                when (tab) {
                    0 -> Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Surface(Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(24.dp),
                            color = Color(0xFFEBEFE6), border = BorderStroke(1.dp, Line)) {
                            Box {
                                SeoulMap(state)
                                Surface(Modifier.align(Alignment.TopStart).padding(16.dp), color = Color.White.copy(alpha = .95f),
                                    shape = RoundedCornerShape(14.dp)) {
                                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                        Text("SEOUL · JONGNO", fontSize = 10.sp, letterSpacing = 2.sp, color = Muted)
                                        Text(if (state.completed) "오늘의 발견을 모았어요" else "도시의 이야기를 따라", fontSize = 19.sp,
                                            fontWeight = FontWeight.Bold, color = Ink)
                                    }
                                }
                                Surface(Modifier.align(Alignment.TopEnd).padding(16.dp), shape = CircleShape, color = Color.White) {
                                    Text("N ↑", modifier = Modifier.padding(12.dp), color = Ink, fontSize = 12.sp)
                                }
                                Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                                    AnimatedVisibility(state.rewardId != null) {
                                        val badge = SeoulDemoRoute.landmarks.find { it.id == state.rewardId }
                                        if (badge != null) Surface(color = Ink, shape = RoundedCornerShape(18.dp), shadowElevation = 6.dp) {
                                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                BadgeArt(SeoulDemoRoute.landmarks.indexOf(badge), true, Modifier.size(52.dp))
                                                Column {
                                                    Text("NEW DISCOVERY", color = Accent, fontSize = 10.sp, letterSpacing = 1.5.sp)
                                                    Text("${badge.name} 뱃지 획득", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                                    Text(badge.story.orEmpty(), color = Color(0xFFCED9D2), fontSize = 11.sp)
                                                }
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text("시연 위치 · 도로 경로 재생   |   © OpenStreetMap contributors", color = Ink, fontSize = 9.sp)
                                }
                            }
                        }
                        RoutePanel(state, model, Modifier.width(258.dp).fillMaxHeight())
                    }
                    1 -> CollectionPanel(state, Modifier.weight(1f))
                    2 -> HistoryPanel(state, model, { tab = 0 }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RoutePanel(state: DemoState, model: DemoViewModel, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("서울, 시간을 잇는 길", fontSize = 23.sp, color = Ink, fontWeight = FontWeight.Bold)
        Text("광화문에서 국립민속박물관까지", fontSize = 12.sp, color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallTag("1.2 km")
            SmallTag("명소 2곳")
            SmallTag("72초 시연")
        }
        Surface(color = Color.White, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Line)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("오늘의 수집", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("${state.collected.size} / 2", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                SeoulDemoRoute.landmarks.forEachIndexed { index, badge ->
                    val collected = badge.id in state.collected
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BadgeArt(index, collected, Modifier.size(43.dp))
                        Column {
                            Text(badge.name, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(if (collected) "수집 완료 ✓" else "가까이 지나면 발견해요", color = if (collected) Ink else Muted, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(when { state.completed -> "탐험 완료"; state.playing -> "도로를 따라 탐험 중";
                state.started -> "잠시 멈췄어요"; else -> "서울 시연 위치 준비 완료" }, color = Ink, fontSize = 11.sp)
            Text(String.format(Locale.US, "%.1f / 1.2 km", state.fraction * SeoulDemoRoute.lengthM / 1000),
                color = Muted, fontSize = 10.sp)
        }
        LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth().height(4.dp),
            color = Ink, trackColor = Line)
        Spacer(Modifier.height(12.dp))
        Surface(color = Color(0xFFE8EDE0), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (state.completed) "JOURNEY COMPLETE" else "YOUR NEXT DISCOVERY", color = Muted,
                    fontSize = 9.sp, letterSpacing = 1.5.sp)
                Text(when (state.collected.size) {
                    0 -> "서울의 시간을\n여는 문, 광화문."
                    1 -> "일상의 이야기가\n모이는 박물관으로."
                    else -> "오늘의 길이\n두 개의 발견으로."
                }, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Medium, lineHeight = 30.sp)
                Text("목적지에 닿기 전에도,\n여행은 이미 시작되었으니까.", color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
            }
        }
      }
        Button(onClick = { if (state.playing) model.pause() else model.play() }, modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp)) {
            Text(when { state.playing -> "Ⅱ   일시정지"; state.completed -> "↻   다시 탐험하기";
                state.started -> "▶   이어서 탐험하기"; else -> "▶   서울 탐험 시작" }, fontSize = 14.sp)
        }
        Text("GPS 없이 재생 · 시연 기록은 이 세션에서만 유지", fontSize = 10.sp, color = Muted)
    }
}

@Composable
private fun SmallTag(text: String) {
    Surface(shape = RoundedCornerShape(7.dp), color = Color(0xFFE9EEE5)) {
        Text(text, color = Ink, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
    }
}

@Composable
private fun CollectionPanel(state: DemoState, modifier: Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("나의 발견 컬렉션", fontSize = 28.sp, color = Ink, fontWeight = FontWeight.Bold)
        Text("익숙한 길도, 모으면 특별해지니까.   ${state.collected.size} / 2 수집", fontSize = 14.sp, color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            SeoulDemoRoute.landmarks.forEachIndexed { index, badge ->
                val collected = badge.id in state.collected
                Surface(Modifier.weight(1f), shape = RoundedCornerShape(22.dp), color = Color.White,
                    border = BorderStroke(1.dp, Line)) {
                    Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        BadgeArt(index, collected, Modifier.size(92.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("SEOUL  /  0${index + 1}", fontSize = 10.sp, color = Muted, letterSpacing = 2.sp)
                            Text(badge.name, fontSize = 19.sp, color = Ink, fontWeight = FontWeight.Bold)
                            Text(badge.story.orEmpty(), fontSize = 12.sp, color = Muted)
                            Text(if (collected) "✓  오늘 발견한 장소" else "아직 만나지 않은 장소", color = Ink, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        Text("시연 컬렉션은 다시 탐험하기를 누르면 초기화됩니다.", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun HistoryPanel(state: DemoState, model: DemoViewModel, onMap: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("길 위의 기록", fontSize = 28.sp, color = Ink, fontWeight = FontWeight.Bold)
        Text("오늘 지나온 길이, 다음 이야기의 시작이 됩니다.", fontSize = 14.sp, color = Muted)
        Surface(shape = RoundedCornerShape(22.dp), color = Color.White, border = BorderStroke(1.dp, Line)) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (state.completedRuns > 0) "서울, 시간을 잇는 길" else "첫 번째 탐험을 기다리고 있어요", color = Ink,
                    fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(if (state.completedRuns > 0) "완료한 시연 ${state.completedRuns}회 · 코스 1.2km · 명소 2곳"
                    else "탐험 지도에서 서울 코스를 시작해 보세요.", color = Muted, fontSize = 14.sp)
                Text("세종대로 → 사직로 → 삼청로", color = Ink, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onMap) { Text("탐험 지도로") }
                    TextButton(onClick = { model.reset(); onMap() }) { Text("새 시연 준비") }
                }
            }
        }
        Text("이 화면은 시연 세션 기록입니다. 앱을 종료하면 초기화되며 실제 GPS 기록을 저장하지 않습니다.",
            color = Muted, fontSize = 12.sp)
    }
}
