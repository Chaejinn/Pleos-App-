package com.pleos.explorer.platform.speech

import com.pleos.explorer.domain.model.legacy.VehicleSignal
import kotlin.math.abs

/** Missing/stale safety observations suppress speech; they never imply a calm road. */
internal class RewardSpeechPolicy {
    private var brake: VehicleSignal.BrakePedal? = null
    private var steering: VehicleSignal.SteeringAngle? = null
    private var guidance: VehicleSignal.TurnGuidance? = null
    private var lastBigSteer: Long? = null
    private var lastAttempt: Long? = null
    fun observe(signal: VehicleSignal) {
        when (signal) {
            is VehicleSignal.BrakePedal -> if (signal.timeMs >= (brake?.timeMs ?: -1)) brake = signal
            is VehicleSignal.TurnGuidance -> if (signal.timeMs >= (guidance?.timeMs ?: -1)) guidance = signal
            is VehicleSignal.SteeringAngle -> if (signal.degrees.isFinite() && signal.timeMs >= (steering?.timeMs ?: -1)) {
                if (abs(signal.degrees) >= 45f || steering?.let { abs(it.degrees - signal.degrees) >= 45f } == true)
                    lastBigSteer = signal.timeMs
                steering = signal
            }
            else -> Unit
        }
    }
    fun canAnnounce(now: Long, enabled: Boolean, consent: Boolean, driving: Boolean): Boolean {
        if (!enabled || !consent || !driving) return false
        val b = brake ?: return false
        val s = steering ?: return false
        val g = guidance ?: return false
        if (listOf(b.timeMs,s.timeMs,g.timeMs).any { now - it !in 0..5_000 }) return false
        if (b.pressed || g.distanceM?.let { it < 300 } == true) return false
        if (lastBigSteer?.let { now - it < 3_000 } == true) return false
        if (lastAttempt?.let { now - it < 60_000 } == true) return false
        return true
    }
    fun attempted(now: Long) { lastAttempt = now }
}
