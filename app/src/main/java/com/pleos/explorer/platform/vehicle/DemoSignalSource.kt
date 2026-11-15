package com.pleos.explorer.platform.vehicle

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.pleos.explorer.domain.model.legacy.GearState
import com.pleos.explorer.domain.model.legacy.VehicleSignal

/** Seoul road replay: 72 seconds of playback, with a 4x logical signal clock. */
class DemoSignalSource(
    private val rough: Boolean,
    private val stepMs: Long = 100,
) : VehicleSignalSource {
    private val handler = Handler(Looper.getMainLooper())
    private var frame = 0
    private var running = false
    var paused: Boolean = false
    init { require(stepMs in 1..72_000) }
    private val frames = (72_000 / stepMs).toInt()

    override fun start(onSignal: (VehicleSignal) -> Unit) {
        stop()
        frame = 0
        paused = false
        running = true
        onSignal(VehicleSignal.Gear(GearState.DRIVE, 0))
        var nextTickMs = SystemClock.uptimeMillis()
        handler.post(object : Runnable {
            override fun run() {
                if (!running) return
                if (!paused) {
                    val timeMs = frame * stepMs * 4
                    if (frame > frames) {
                        running = false
                        onSignal(VehicleSignal.Gear(GearState.PARK, timeMs))
                        return
                    }
                    val distance = SeoulDemoRoute.lengthM * frame / frames
                    val point = SeoulDemoRoute.atDistance(distance)
                    val speed = (SeoulDemoRoute.lengthM / 288.0).toFloat()
                    onSignal(VehicleSignal.Location(point.lat, point.lon, speed, point.bearing, timeMs))
                    onSignal(VehicleSignal.Efficiency(if (rough) 11f else 13f, timeMs))
                    frame++
                } else {
                    nextTickMs = SystemClock.uptimeMillis()
                }
                // Keep callback work from accumulating into playback drift.
                nextTickMs += stepMs
                if (running) handler.postAtTime(this, nextTickMs)
            }
        })
    }

    override fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }
}
