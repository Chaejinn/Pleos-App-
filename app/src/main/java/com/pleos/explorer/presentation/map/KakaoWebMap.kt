package com.pleos.explorer.presentation.map

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pleos.explorer.BuildConfig
import com.pleos.explorer.domain.model.GeoPoint
import com.pleos.explorer.domain.model.PathSegment
import com.pleos.explorer.domain.model.legacy.Landmark
import com.google.gson.Gson

private const val MAP_URL = "https://appassets.androidplatform.net/roadlog/map.html"

/** Local HTTPS document with no native JS bridge, no filesystem access and no top-level navigation. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun KakaoWebMap(past: List<PathSegment>, today: List<PathSegment>, focus: GeoPoint?,
    landmarks: List<Landmark>, collectedIds: Set<String>, modifier: Modifier, follow: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val html = remember { context.assets.open("map/kakao.html").bufferedReader().use { it.readText() }
        .replace("__KAKAO_KEY__", BuildConfig.KAKAO_JAVASCRIPT_APP_KEY) }
    val view = remember(context) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.setGeolocationEnabled(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    return if (request?.url?.toString() == MAP_URL)
                        WebResourceResponse("text/html", "UTF-8", html.byteInputStream(Charsets.UTF_8)) else null
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { loaded = false }
                override fun onPageFinished(view: WebView?, url: String?) { loaded = url == MAP_URL }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    if (request?.isForMainFrame == true) failed = true
                }
            }
            loadUrl(MAP_URL)
        }
    }
    DisposableEffect(view, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) { Lifecycle.Event.ON_RESUME -> view.onResume(); Lifecycle.Event.ON_PAUSE -> view.onPause(); else -> Unit }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume() else view.onPause()
        onDispose { lifecycle.removeObserver(observer); view.stopLoading(); view.clearCache(true); view.destroy() }
    }
    LaunchedEffect(view, loaded, past, today, focus, landmarks, collectedIds, follow) {
        if (loaded) {
            fun paths(segments: List<PathSegment>) = drawableRouteSegments(segments).map { points ->
                points.map { listOf(it.latitudeDeg, it.longitudeDeg) }
            }
            val payload = mapOf("past" to paths(past), "today" to paths(today), "follow" to follow,
                "focus" to focus?.let { listOf(it.latitudeDeg, it.longitudeDeg) },
                "landmarks" to if (follow) emptyList() else landmarks.map {
                    mapOf("id" to it.id, "name" to it.name, "region" to it.region, "lat" to it.lat, "lon" to it.lon,
                        "radiusM" to it.radiusM, "collected" to (it.id in collectedIds))
                })
            view.evaluateJavascript("window.roadlogUpdate && window.roadlogUpdate(${Gson().toJson(payload)});", null)
        }
    }
    AndroidView(factory = { view }, modifier = modifier.fillMaxWidth().height(if (follow) 200.dp else 360.dp), update = {
        it.setOnTouchListener { web, event ->
            if (!follow && event.action == MotionEvent.ACTION_DOWN) web.parent.requestDisallowInterceptTouchEvent(true)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL)
                web.parent.requestDisallowInterceptTouchEvent(false)
            false
        }
    })
    if (failed) Text("지도를 불러오지 못했어요. 네트워크 연결을 확인해 주세요.", fontSize = 24.sp)
    Text("지도 제공: 카카오 · 초록은 수집, 보라는 미수집", fontSize = 24.sp)
}
