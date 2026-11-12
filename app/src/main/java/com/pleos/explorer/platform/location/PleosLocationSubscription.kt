package com.pleos.explorer.platform.location

import android.content.Context
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData

/** FusedLocation 구독 수명만 담당한다. 입력 검증·직렬화는 호출자가 담당한다. */
internal class PleosLocationSubscription(
    private val context: Context,
    private val onLocation: (LocationData?) -> Unit,
    private val onError: (Exception) -> Unit,
) {
    private var fused: FusedLocation? = null
    private val listener = object : FusedLocationListener {
        override fun onChanged(value: LocationData?) = onLocation(value)
        override fun onError(exception: Exception) = onError.invoke(exception)
    }

    fun start() {
        check(fused == null) { "이미 위치를 구독 중입니다" }
        val source = FusedLocation(context.applicationContext)
        fused = source
        check(source.isSupported()) { "Pleos 위치 서비스를 찾을 수 없습니다" }
        source.initialize()
        source.registerFusedLocationCallback(listener)
    }

    fun stop() {
        val source = fused ?: return
        fused = null
        runCatching { source.unregisterFusedLocationCallback(listener) }
        runCatching { source.release() }
    }
}
