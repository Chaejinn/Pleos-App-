package com.pleos.explorer.platform.speech

import com.pleos.explorer.domain.model.legacy.VehicleSignal
import org.junit.Assert.*
import org.junit.Test

class RewardSpeechPolicyTest {
    private fun calm(policy: RewardSpeechPolicy, time: Long, distance: Int? = 1000) {
        policy.observe(VehicleSignal.BrakePedal(false,time))
        policy.observe(VehicleSignal.SteeringAngle(0f,time))
        policy.observe(VehicleSignal.TurnGuidance(distance,time))
    }
    @Test fun missingStaleOrDisabledObservationsNeverAuthorizeSpeech() {
        val policy = RewardSpeechPolicy()
        assertFalse(policy.canAnnounce(0,true,true,true))
        calm(policy,1000)
        assertTrue(policy.canAnnounce(1000,true,true,true))
        assertFalse(policy.canAnnounce(6001,true,true,true))
        assertFalse(policy.canAnnounce(1000,false,true,true))
        assertFalse(policy.canAnnounce(1000,true,false,true))
        assertFalse(policy.canAnnounce(1000,true,true,false))
    }
    @Test fun brakeTurnAndLargeSteerDelaySpeech() {
        val policy = RewardSpeechPolicy()
        calm(policy,1000,100)
        assertFalse(policy.canAnnounce(1000,true,true,true))
        calm(policy,2000)
        policy.observe(VehicleSignal.BrakePedal(true,2000))
        assertFalse(policy.canAnnounce(2000,true,true,true))
        calm(policy,3000)
        policy.observe(VehicleSignal.SteeringAngle(50f,3000))
        assertFalse(policy.canAnnounce(3000,true,true,true))
        calm(policy,4000)
        assertFalse(policy.canAnnounce(6000,true,true,true))
        calm(policy,7000)
        assertTrue(policy.canAnnounce(7000,true,true,true))
    }
    @Test fun attemptsKeepSixtySecondIntervalAndClockReversalIsDenied() {
        val policy = RewardSpeechPolicy()
        calm(policy,1000)
        policy.attempted(1000)
        calm(policy,60_999)
        assertFalse(policy.canAnnounce(60_999,true,true,true))
        calm(policy,61_000)
        assertTrue(policy.canAnnounce(61_000,true,true,true))
        assertFalse(policy.canAnnounce(500,true,true,true))
    }
}
