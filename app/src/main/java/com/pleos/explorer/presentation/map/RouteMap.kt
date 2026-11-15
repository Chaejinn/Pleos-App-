package com.pleos.explorer.presentation.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kakao.vectormap.*
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.Label
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import com.kakao.vectormap.route.*
import com.pleos.explorer.BuildConfig
import com.pleos.explorer.domain.model.GeoPoint
import com.pleos.explorer.domain.model.PathSegment
import com.pleos.explorer.platform.map.KakaoMapAvailability
import com.pleos.explorer.platform.map.KakaoMapCache
import com.pleos.explorer.platform.map.kakaoMapAvailability
import com.pleos.explorer.presentation.components.ActionButton
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun RouteMap(past: List<PathSegment>, today: List<PathSegment>, focus: GeoPoint?,
    modifier: Modifier = Modifier, follow: Boolean = false,
    landmarks: List<com.pleos.explorer.domain.model.legacy.Landmark> = emptyList(), collectedIds: Set<String> = emptySet(),
    landmarkVisits: List<com.pleos.explorer.domain.model.LandmarkVisit> = emptyList()) {
    val availability = kakaoMapAvailability(BuildConfig.KAKAO_NATIVE_APP_KEY, Build.SUPPORTED_ABIS.toList())
    if (BuildConfig.KAKAO_JAVASCRIPT_APP_KEY.isNotBlank() &&
        (availability != KakaoMapAvailability.READY || Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("x86") == true)) {
        KakaoWebMap(past, today, focus, landmarks, collectedIds, modifier, follow)
        return
    }
    if (availability != KakaoMapAvailability.READY) {
        Text("현재 지도를 표시할 수 없어요. 경로 기록과 조회는 계속 사용할 수 있어요.", fontSize = 24.sp)
        if (BuildConfig.DEBUG) Text(when (availability) {
            KakaoMapAvailability.MISSING_KEY -> "개발 설정: 카카오 네이티브 앱 키 등록이 필요합니다."
            else -> "개발 환경: 카카오 Android SDK는 ARM 기기를 지원합니다. 이 에뮬레이터의 아키텍처를 확인해 주세요."
        }, fontSize = 24.sp)
        return
    }
    var retry by remember { mutableIntStateOf(0) }
    key(retry) { KakaoRouteMap(past, today, focus, modifier, follow, landmarks, collectedIds, landmarkVisits,onRetry = { retry++ }) }
}

@Composable
private fun KakaoRouteMap(past: List<PathSegment>, today: List<PathSegment>, focus: GeoPoint?,
    modifier: Modifier, follow: Boolean, landmarks: List<com.pleos.explorer.domain.model.legacy.Landmark>,
    collectedIds: Set<String>, landmarkVisits: List<com.pleos.explorer.domain.model.LandmarkVisit>,onRetry: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val uri = LocalUriHandler.current
    val mapHeight = (LocalConfiguration.current.screenHeightDp * 0.4f)
        .coerceIn(140f, if (follow) 200f else 340f).dp
    val view = remember(context) {
        try {
            if (!KakaoMapSdk.isInitialized()) KakaoMapSdk.init(context.applicationContext, BuildConfig.KAKAO_NATIVE_APP_KEY)
            MapView(context)
        } catch (_: Exception) { null } catch (_: LinkageError) { null }
    }
    if (view == null) {
        Text("카카오지도를 시작하지 못했어요. 저장된 경로는 주행 기록에서 확인할 수 있어요.", fontSize = 24.sp)
        return
    }
    val alive = remember(view) { AtomicBoolean(true) }
    var map by remember(view) { mutableStateOf<KakaoMap?>(null) }
    var failed by remember(view) { mutableStateOf(false) }
    var focused by remember(view) { mutableStateOf(false) }
    var previousFocus by remember(view) { mutableStateOf<GeoPoint?>(null) }
    var selectedId by remember(view) { mutableStateOf<String?>(null) }
    val currentFollow by rememberUpdatedState(follow)
    var marker by remember(view) { mutableStateOf<Label?>(null) }
    val markerBitmap = remember(view) {
        val size = (24 * context.resources.displayMetrics.density).toInt().coerceAtLeast(24)
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
            paint.color = Color.rgb(21, 101, 192)
            canvas.drawCircle(size / 2f, size / 2f, size * 0.36f, paint)
        }
    }

    DisposableEffect(view, lifecycle) {
        var resumed = false
        var finished = false
        var readyMap: KakaoMap? = null
        fun finish() {
            if (finished) return
            finished = true
            alive.set(false)
            readyMap?.let { KakaoMapCache.release(context, it) }
            runCatching { view.finish() }
        }
        fun sync() {
            if (finished) return
            val shouldResume = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (resumed && !shouldResume) { view.pause(); resumed = false }
            if (!resumed && shouldResume) { view.resume(); resumed = true }
        }
        try {
            view.start(object : MapLifeCycleCallback() {
                override fun onMapDestroy() { view.post { if (alive.get()) { map = null; failed = true } } }
                override fun onMapError(error: Exception) { view.post { if (alive.get()) { map = null; failed = true } } }
            }, object : KakaoMapReadyCallback() {
                override fun getPosition(): LatLng = focus?.let { LatLng.from(it.latitudeDeg, it.longitudeDeg) }
                    ?: LatLng.from(36.0, 127.0)
                override fun getZoomLevel(): Int = if (focus == null) 7 else 15
                override fun onMapReady(value: KakaoMap) {
                    view.post {
                        if (alive.get()) try {
                            KakaoMapCache.ready(context, value)
                            value.setGestureEnable(GestureType.Rotate, false)
                            value.setGestureEnable(GestureType.RotateZoom, false)
                            value.setGestureEnable(GestureType.Tilt, false)
                            value.setGestureEnable(GestureType.LongTapAndDrag, false)
                            readyMap = value
                            value.setOnLabelClickListener { _, _, label ->
                                if (!currentFollow) { selectedId = label.tag as? String }
                                return@setOnLabelClickListener !currentFollow
                            }
                            map = value
                        } catch (_: Exception) { failed = true }
                    }
                }
            })
            view.setFinishManually(true)
        } catch (_: Exception) { failed = true } catch (_: LinkageError) { failed = true }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) finish() else if (!failed) sync()
        }
        lifecycle.addObserver(observer)
        if (!failed) sync()
        onDispose { lifecycle.removeObserver(observer); finish() }
    }

    LaunchedEffect(map, past, today, focus, follow) {
        val nativeMap = map ?: return@LaunchedEffect
        try {
            val layer = requireNotNull(requireNotNull(nativeMap.routeLineManager).layer)
            layer.removeAll()
            fun draw(paths: List<PathSegment>, color: Int, width: Float) {
                val styles = RouteLineStylesSet.from(RouteLineStyles.from(RouteLineStyle.from(
                    width * context.resources.displayMetrics.density, color)))
                drawableRouteSegments(paths).forEach { points ->
                    val segment = RouteLineSegment.from(points.map { LatLng.from(it.latitudeDeg, it.longitudeDeg) }, styles.getStyles(0))
                    layer.addRouteLine(RouteLineOptions.from(segment).setStylesSet(styles))
                }
            }
            draw(past, Color.rgb(165, 180, 204), 4f)
            draw(today, Color.rgb(103, 80, 164), 6f)
            nativeMap.setGestureEnable(GestureType.Pan, !follow)
            nativeMap.setPoiClickable(!follow)
            if (follow && focus != null) {
                val position = LatLng.from(focus.latitudeDeg, focus.longitudeDeg)
                val oldMarker = marker
                if (oldMarker == null) {
                    val labels = requireNotNull(nativeMap.labelManager)
                    val styles = labels.addLabelStyles(LabelStyles.from(
                        LabelStyle.from(markerBitmap).setAnchorPoint(0.5f, 0.5f)))
                    marker = requireNotNull(labels.layer).addLabel(LabelOptions.from(position).setStyles(styles))
                } else oldMarker.moveTo(position)
            } else { marker?.remove(); marker = null }
            if (focus != null && (!focused || follow || previousFocus != focus)) {
                val position = LatLng.from(focus.latitudeDeg, focus.longitudeDeg)
                nativeMap.moveCamera(if (focused) CameraUpdateFactory.newCenterPosition(position)
                    else CameraUpdateFactory.newCenterPosition(position, 15))
                focused = true
                previousFocus = focus
            }
        } catch (_: Exception) { failed = true }
    }
    LaunchedEffect(map, landmarks, collectedIds, follow) {
        val nativeMap = map ?: return@LaunchedEffect
        try {
            val manager = requireNotNull(nativeMap.labelManager)
            val layer = requireNotNull(manager.getLayer("landmarks") ?: manager.addLayer(
                com.kakao.vectormap.label.LabelLayerOptions.from("landmarks")))
            layer.removeAll()
            if (!follow) {
                fun icon(color: Int): Bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply {
                    val canvas = Canvas(this)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE }
                    canvas.drawCircle(24f, 24f, 23f, paint)
                    paint.color = color
                    canvas.drawCircle(24f, 24f, 18f, paint)
                }
                val collectedStyle = manager.addLabelStyles(LabelStyles.from(LabelStyle.from(icon(Color.rgb(23, 107, 83)))))
                val availableStyle = manager.addLabelStyles(LabelStyles.from(LabelStyle.from(icon(Color.rgb(103, 80, 164)))))
                landmarks.forEach { landmark ->
                    layer.addLabel(LabelOptions.from(landmark.id, LatLng.from(landmark.lat, landmark.lon))
                        .setStyles(if (landmark.id in collectedIds) collectedStyle else availableStyle)
                        .setClickable(true).setTag(landmark.id))
                }
            } else selectedId = null
        } catch (_: Exception) { failed = true }
    }
    AndroidView(factory = { view }, modifier = modifier.fillMaxWidth().height(mapHeight))
    if (!follow) landmarks.firstOrNull { it.id == selectedId }?.let { landmark ->
        com.pleos.explorer.presentation.collection.LandmarkDetails(landmark,landmarkVisits.firstOrNull { it.landmarkId==landmark.id && !it.isReplay }, onDismiss = { selectedId = null })
    }
    if (map == null && !failed) Text("카카오지도를 불러오고 있어요", fontSize = 24.sp)
    if (failed) {
        Text("카카오지도를 불러오지 못했어요. 경로 기록은 계속 사용할 수 있어요.", fontSize = 24.sp)
        if (!follow) ActionButton("지도 다시 불러오기", onClick = onRetry)
    }
    Text(if (follow) "현재 경로를 따라 표시합니다." else "과거 경로는 연한 선, 오늘 시작한 주행은 진한 선입니다.", fontSize = 24.sp)
    if (follow) Text("지도 제공: 카카오", fontSize = 24.sp)
    else TextButton(onClick = { uri.openUri("https://map.kakao.com/") },
        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp)) { Text("지도 제공: 카카오", fontSize = 24.sp) }
}
