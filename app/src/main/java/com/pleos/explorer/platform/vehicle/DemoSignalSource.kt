package com.pleos.explorer.platform.vehicle

import android.os.Handler
import android.os.Looper
import com.pleos.explorer.domain.model.legacy.GearState
import com.pleos.explorer.domain.model.legacy.TurnState
import com.pleos.explorer.domain.model.legacy.VehicleSignal

/**
 * 광안대교를 지나는 짧은 가상 경로를 재생한다.
 * 에뮬레이터 Virtual Console로 핸들·브레이크를 직접 조작하기 전에 흐름을 확인하는 용도.
 * rough=true면 중간에 급제동·깜빡이 없는 회전을 섞는다(첫 주행 = 고스트 만들기용).
 */
class DemoSignalSource(
    private val rough: Boolean,
    private val stepMs: Long = 500,
) : VehicleSignalSource {

    private val handler = Handler(Looper.getMainLooper())
    private var t = 0L
    private var i = 0
    private var running = false

    private val startLat = 35.1400
    private val lon = 129.1300
    private val steps = 60

    override fun start(onSignal: (VehicleSignal) -> Unit) {
        running = true
        onSignal(VehicleSignal.Gear(GearState.DRIVE, t))
        val tick = object : Runnable {
            override fun run() {
                if (!running) return
                if (i >= steps) {
                    onSignal(VehicleSignal.Gear(GearState.PARK, t))
                    running = false
                    return
                }
                val lat = startLat + i * 0.0003
                val slowDown = rough && i == 20
                val speedKmh = if (slowDown) 15f else 50f
                onSignal(VehicleSignal.Location(lat, lon, speedKmh / 3.6f, 0f, t))
                onSignal(VehicleSignal.Efficiency(if (rough) 11f else 13f, t))
                onSignal(VehicleSignal.TurnGuidance(if (i in 38..42) 150 else 900, t))
                if (i == 40) {
                    if (!rough) onSignal(VehicleSignal.TurnSignal(TurnState.RIGHT, t))
                    onSignal(VehicleSignal.SteeringAngle(100f, t))
                } else {
                    onSignal(VehicleSignal.SteeringAngle(0f, t))
                    onSignal(VehicleSignal.TurnSignal(TurnState.OFF, t))
                }
                i++
                t += 2_000
                handler.postDelayed(this, stepMs)
            }
        }
        handler.post(tick)
    }

    override fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }
}
