package com.pleos.explorer.service

import com.pleos.explorer.domain.model.legacy.GearState

/** 연결 직후 수신하는 P단을 도착으로 오인하지 않는다. */
class DepartureGate {
    var hasDeparted = false
        private set

    fun onGear(gear: GearState): Boolean {
        if (gear == GearState.DRIVE || gear == GearState.REVERSE) hasDeparted = true
        return hasDeparted && gear == GearState.PARK
    }
}
