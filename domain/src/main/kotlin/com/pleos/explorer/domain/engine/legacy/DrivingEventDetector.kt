package com.pleos.explorer.domain.engine.legacy

import com.pleos.explorer.domain.model.legacy.DrivingEvent
import com.pleos.explorer.domain.model.legacy.TurnState
import com.pleos.explorer.domain.model.legacy.VehicleSignal

import kotlin.math.abs

/**
 * 신호 흐름을 보고 급제동·급조향·깜빡이 없는 회전을 감지한다.
 * 임계값은 초기 추정치이며 실차·에뮬레이터 데이터로 조정해야 한다.
 */
class DrivingEventDetector(
    private val harshBrakeMps2: Float = 3.0f,
    private val harshSteerDegPerSec: Float = 180f,
    private val minSpeedForSteerMps: Float = 20f / 3.6f,
    private val turnAngleDeg: Float = 90f,
    private val minSpeedForTurnMps: Float = 10f / 3.6f,
) {
    private var lastLocation: VehicleSignal.Location? = null
    private var lastSteer: VehicleSignal.SteeringAngle? = null
    private var turnSignal = TurnState.OFF
    private var lastSignalOnMs: Long? = null
    private var speedMps = 0f
    private var turnReported = false

    /** 신호 하나를 넣고, 새로 감지된 사건이 있으면 반환한다. */
    fun onSignal(signal: VehicleSignal): DrivingEvent? = when (signal) {
        is VehicleSignal.Location -> onLocation(signal)
        is VehicleSignal.SteeringAngle -> onSteer(signal)
        is VehicleSignal.TurnSignal -> {
            turnSignal = signal.state
            if (signal.state == TurnState.LEFT || signal.state == TurnState.RIGHT) lastSignalOnMs = signal.timeMs
            null
        }
        else -> null
    }

    private fun onLocation(loc: VehicleSignal.Location): DrivingEvent? {
        val prev = lastLocation
        lastLocation = loc
        speedMps = loc.speedMps
        if (prev == null) return null
        val dt = (loc.timeMs - prev.timeMs) / 1000f
        if (dt <= 0f || dt > 3f) return null
        val decel = (prev.speedMps - loc.speedMps) / dt
        return if (decel >= harshBrakeMps2) DrivingEvent.HarshBrake(decel, loc.timeMs) else null
    }

    private fun onSteer(s: VehicleSignal.SteeringAngle): DrivingEvent? {
        val prev = lastSteer
        lastSteer = s

        // 깜빡이 없는 회전: 크게 꺾었는데 최근 5초 안에 방향지시등을 켠 적이 없음
        if (abs(s.degrees) < turnAngleDeg / 2) turnReported = false
        if (!turnReported && abs(s.degrees) >= turnAngleDeg && speedMps >= minSpeedForTurnMps) {
            turnReported = true
            val signalRecently = lastSignalOnMs?.let { s.timeMs - it <= 5_000 } == true ||
                turnSignal == TurnState.LEFT || turnSignal == TurnState.RIGHT
            if (!signalRecently) return DrivingEvent.TurnWithoutSignal(s.timeMs)
        }

        if (prev == null) return null
        val dt = (s.timeMs - prev.timeMs) / 1000f
        if (dt <= 0f || dt > 1f) return null
        val rate = abs(s.degrees - prev.degrees) / dt
        return if (rate >= harshSteerDegPerSec && speedMps >= minSpeedForSteerMps) {
            DrivingEvent.HarshSteer(rate, s.timeMs)
        } else null
    }
}
