package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.model.DetectionPolicy
import com.pleos.explorer.domain.model.HarshBrakeState
import com.pleos.explorer.domain.model.HarshBrakeUpdate
import com.pleos.explorer.domain.model.Observation

class HarshBrakeDetector {
    fun process(state: HarshBrakeState, speed: Observation<Double?>): HarshBrakeUpdate {
        require(speed.observedAtElapsedMs >= 0)
        val previous = state.previous
        if (previous != null && speed.observedAtElapsedMs <= previous.observedAtElapsedMs) {
            val next = if (speed == previous) state else HarshBrakeState(previous.copy(value = null))
            return HarshBrakeUpdate(next)
        }
        val value = speed.value?.takeIf { it.isFinite() && it >= 0 }
        val next = HarshBrakeState(speed.copy(value = value))
        val previousValue = previous?.value
        if (value == null || previousValue == null) return HarshBrakeUpdate(next)
        val intervalMs = speed.observedAtElapsedMs - previous.observedAtElapsedMs
        if (intervalMs > DetectionPolicy.METRICS_SAMPLE_GAP_MS) return HarshBrakeUpdate(next)
        val deceleration = (previousValue - value) / (intervalMs / 1_000.0)
        if (!deceleration.isFinite()) return HarshBrakeUpdate(next)
        val active = deceleration >= DetectionPolicy.HARSH_BRAKE_MPS2
        return HarshBrakeUpdate(next.copy(active = active), deceleration, active && !state.active)
    }
}
