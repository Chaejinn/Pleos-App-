package com.pleos.explorer.domain.engine.legacy

import com.pleos.explorer.domain.model.legacy.DrivingEvent
import com.pleos.explorer.domain.model.legacy.TurnState
import com.pleos.explorer.domain.model.legacy.VehicleSignal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingEventDetectorTest {

    private fun loc(speedKmh: Float, t: Long) =
        VehicleSignal.Location(37.5, 127.0, speedKmh / 3.6f, 0f, t)

    @Test
    fun harshBrakeDetectedWhenSpeedDropsFast() {
        val d = DrivingEventDetector()
        d.onSignal(loc(60f, 0))
        val e = d.onSignal(loc(40f, 1_000)) // 약 5.6 m/s² 감속
        assertTrue(e is DrivingEvent.HarshBrake)
    }

    @Test
    fun gentleBrakeIgnored() {
        val d = DrivingEventDetector()
        d.onSignal(loc(60f, 0))
        assertNull(d.onSignal(loc(55f, 1_000)))
    }

    @Test
    fun harshSteerOnlyAtSpeed() {
        val slow = DrivingEventDetector()
        slow.onSignal(loc(5f, 0))
        slow.onSignal(VehicleSignal.SteeringAngle(0f, 0))
        assertNull(slow.onSignal(VehicleSignal.SteeringAngle(60f, 200)))

        val fast = DrivingEventDetector()
        fast.onSignal(loc(60f, 0))
        fast.onSignal(VehicleSignal.SteeringAngle(0f, 0))
        assertTrue(fast.onSignal(VehicleSignal.SteeringAngle(60f, 200)) is DrivingEvent.HarshSteer)
    }

    @Test
    fun turnWithoutSignalReportedOncePerTurn() {
        val d = DrivingEventDetector()
        d.onSignal(loc(20f, 0))
        val events = listOf(30f, 60f, 95f, 120f, 95f).mapIndexedNotNull { i, deg ->
            d.onSignal(VehicleSignal.SteeringAngle(deg, 1_000L + i * 1_000))
        }.filterIsInstance<DrivingEvent.TurnWithoutSignal>()
        assertEquals(1, events.size)
    }

    @Test
    fun turnWithSignalNotReported() {
        val d = DrivingEventDetector()
        d.onSignal(loc(20f, 0))
        d.onSignal(VehicleSignal.TurnSignal(TurnState.RIGHT, 500))
        d.onSignal(VehicleSignal.TurnSignal(TurnState.OFF, 1_500))
        val e = d.onSignal(VehicleSignal.SteeringAngle(100f, 3_000))
        assertTrue(e !is DrivingEvent.TurnWithoutSignal)
    }
}
