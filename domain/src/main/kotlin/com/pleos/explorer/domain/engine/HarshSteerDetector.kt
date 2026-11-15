package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.model.DetectionPolicy
import com.pleos.explorer.domain.model.HarshSteerState
import com.pleos.explorer.domain.model.HarshSteerUpdate
import com.pleos.explorer.domain.model.Observation
import kotlin.math.abs

class HarshSteerDetector {
    fun process(state: HarshSteerState, angle: Observation<Double?>, speed: Observation<Double?>?): HarshSteerUpdate {
        require(angle.observedAtElapsedMs >= 0)
        require(speed == null || speed.observedAtElapsedMs >= 0)
        val previous = state.previous
        if (previous != null && angle.observedAtElapsedMs <= previous.observedAtElapsedMs) {
            val next = if (angle == previous) state else HarshSteerState(previous.copy(value = null))
            return HarshSteerUpdate(next)
        }
        val speedValue = speed?.takeIf {
            it.observedAtElapsedMs <= angle.observedAtElapsedMs &&
                angle.observedAtElapsedMs - it.observedAtElapsedMs <= DetectionPolicy.VEHICLE_OBSERVATION_MAX_AGE_MS
        }?.value?.takeIf { it.isFinite() && it >= 0 }
        val value = angle.value?.takeIf { it.isFinite() && speedValue != null }
        val next = HarshSteerState(angle.copy(value = value))
        val previousValue = previous?.value
        if (value == null || previousValue == null) return HarshSteerUpdate(next)
        val intervalMs = angle.observedAtElapsedMs - previous.observedAtElapsedMs
        if (intervalMs > DetectionPolicy.METRICS_SAMPLE_GAP_MS) return HarshSteerUpdate(next)
        val angularVelocity = (value - previousValue) / (intervalMs / 1_000.0)
        if (!angularVelocity.isFinite()) return HarshSteerUpdate(next)
        val active = requireNotNull(speedValue) >= DetectionPolicy.HARSH_STEER_MIN_SPEED_MPS &&
            abs(angularVelocity) >= DetectionPolicy.HARSH_STEER_DEG_PER_SEC
        return HarshSteerUpdate(next.copy(active = active), angularVelocity, active && !state.active)
    }
}
