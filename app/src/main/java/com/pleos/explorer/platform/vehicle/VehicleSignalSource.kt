package com.pleos.explorer.platform.vehicle

import com.pleos.explorer.domain.model.legacy.VehicleSignal

/** 차량·내비·위치 신호 공급자. 실차/에뮬레이터는 Pleos, 시연 보조용은 Demo 구현을 쓴다. */
interface VehicleSignalSource {
    fun start(onSignal: (VehicleSignal) -> Unit)
    fun stop()
}
