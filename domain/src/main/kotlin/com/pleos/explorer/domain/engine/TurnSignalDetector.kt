package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.model.DetectionPolicy
import com.pleos.explorer.domain.model.Observation
import com.pleos.explorer.domain.model.TurnDirection
import com.pleos.explorer.domain.model.TurnSignal
import com.pleos.explorer.domain.model.TurnSignalInput
import com.pleos.explorer.domain.model.TurnSignalState
import com.pleos.explorer.domain.model.TurnSignalTurn
import com.pleos.explorer.domain.model.TurnSignalUpdate
import kotlin.math.abs

class TurnSignalDetector(private val positiveAngleDirection: TurnDirection) {
    fun process(state: TurnSignalState, input: TurnSignalInput): TurnSignalUpdate = when (input) {
        is TurnSignalInput.Signal -> onSignal(state, input.observation)
        is TurnSignalInput.Angle -> onAngle(state, input.observation)
    }

    private fun onSignal(state: TurnSignalState, observation: Observation<TurnSignal?>): TurnSignalUpdate {
        val at = observation.observedAtElapsedMs
        require(at >= 0)
        val previous = state.signal
        if (previous != null && at <= previous.observedAtElapsedMs) {
            return TurnSignalUpdate(if (observation == previous) state else state.copy(
                signal = previous.copy(value = null), coverageStartMs = null, lastLeftMs = null, lastRightMs = null))
        }
        val continuous = observation.value != null && previous?.value != null &&
            at - previous.observedAtElapsedMs <= DetectionPolicy.METRICS_SAMPLE_GAP_MS
        return TurnSignalUpdate(state.copy(
            signal = observation,
            coverageStartMs = if (observation.value == null) null else if (continuous) state.coverageStartMs else at,
            lastLeftMs = if (observation.value == TurnSignal.LEFT) at else state.lastLeftMs.takeIf { continuous },
            lastRightMs = if (observation.value == TurnSignal.RIGHT) at else state.lastRightMs.takeIf { continuous },
        ))
    }

    private fun onAngle(state: TurnSignalState, observation: Observation<Double?>): TurnSignalUpdate {
        val at = observation.observedAtElapsedMs
        require(at >= 0)
        val previous = state.angle
        if (previous != null && at <= previous.observedAtElapsedMs) {
            return TurnSignalUpdate(if (observation == previous) state else state.copy(
                angle = previous.copy(value = null), armed = false,
                usage = state.usage.copy(hasUnobservedSteering = true)))
        }
        val value = observation.value?.takeIf { it.isFinite() }
        val magnitude = value?.let(::abs)
        val continuous = previous?.value != null &&
            at - previous.observedAtElapsedMs <= DetectionPolicy.METRICS_SAMPLE_GAP_MS
        val detected = magnitude != null && magnitude >= DetectionPolicy.TURN_ANGLE_DEG && continuous && state.armed
        val missedSteering = value == null || previous != null && !continuous ||
            previous == null && requireNotNull(magnitude) >= DetectionPolicy.TURN_REARM_DEG
        val next = state.copy(
            angle = observation.copy(value = value),
            armed = !detected && magnitude != null && (magnitude < DetectionPolicy.TURN_REARM_DEG || continuous && state.armed),
            usage = state.usage.copy(hasUnobservedSteering = state.usage.hasUnobservedSteering || missedSteering),
        )
        if (!detected) return TurnSignalUpdate(next)
        val direction = if (requireNotNull(value) > 0) positiveAngleDirection else when (positiveAngleDirection) {
            TurnDirection.LEFT -> TurnDirection.RIGHT
            TurnDirection.RIGHT -> TurnDirection.LEFT
        }
        val signaled = signalUsed(state, direction, at)
        return TurnSignalUpdate(next.copy(usage = next.usage.record(signaled)), TurnSignalTurn(direction, signaled, at))
    }

    private fun signalUsed(state: TurnSignalState, direction: TurnDirection, at: Long): Boolean? {
        val latest = state.signal ?: return null
        if (latest.value == null || latest.observedAtElapsedMs > at ||
            at - latest.observedAtElapsedMs > DetectionPolicy.VEHICLE_OBSERVATION_MAX_AGE_MS) return null
        val matchedAt = when (direction) {
            TurnDirection.LEFT -> state.lastLeftMs
            TurnDirection.RIGHT -> state.lastRightMs
        }
        if (matchedAt != null && matchedAt <= at && at - matchedAt <= DetectionPolicy.TURN_SIGNAL_LOOKBACK_MS) return true
        return if (requireNotNull(state.coverageStartMs) <= at - DetectionPolicy.TURN_SIGNAL_LOOKBACK_MS) false else null
    }
}
