package com.pleos.explorer.service

import com.pleos.explorer.domain.model.legacy.GearState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DepartureGateTest {
    @Test fun initialParkDoesNotFinishTrip() {
        val gate = DepartureGate()
        repeat(3) { assertFalse(gate.onGear(GearState.PARK)) }
        assertFalse(gate.hasDeparted)
    }
    @Test fun parkAfterDrivingFinishesTrip() {
        val gate = DepartureGate()
        assertFalse(gate.onGear(GearState.DRIVE))
        assertTrue(gate.onGear(GearState.PARK))
    }
    @Test fun neutralAndUnknownDoNotCountAsDeparture() {
        val gate = DepartureGate()
        assertFalse(gate.onGear(GearState.NEUTRAL))
        assertFalse(gate.onGear(GearState.UNKNOWN))
        assertFalse(gate.onGear(GearState.PARK))
    }
    @Test fun reverseBeforeParkCountsAsTrip() {
        val gate = DepartureGate()
        assertFalse(gate.onGear(GearState.REVERSE))
        assertFalse(gate.onGear(GearState.NEUTRAL))
        assertTrue(gate.onGear(GearState.PARK))
    }
}
