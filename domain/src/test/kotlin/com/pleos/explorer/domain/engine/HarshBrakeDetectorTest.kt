package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.model.HarshBrakeState
import com.pleos.explorer.domain.model.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HarshBrakeDetectorTest {
    private val detector = HarshBrakeDetector()

    private fun state(speedMps: Double?, elapsedMs: Long = 0, active: Boolean = false) =
        HarshBrakeState(Observation(speedMps, elapsedMs), active)

    @Test
    fun `first speed only establishes a baseline`() {
        val update = detector.process(HarshBrakeState(), Observation(30.0, 0))
        assertEquals(state(30.0), update.state)
        assertNull(update.decelerationMps2)
        assertFalse(update.started)
    }

    @Test
    fun `threshold includes exactly three meters per second squared`() {
        listOf(2.999, 3.0, 3.001).forEach { deceleration ->
            val update = detector.process(state(30.0), Observation(30.0 - deceleration, 1_000))
            assertEquals(deceleration, requireNotNull(update.decelerationMps2), 1e-12)
            assertEquals(deceleration >= 3.0, update.started)
            assertEquals(deceleration >= 3.0, update.state.active)
        }
    }

    @Test
    fun `continuous braking emits once and a recovered signal rearms the detector`() {
        var current = state(30.0)
        val started = listOf(27.0, 24.0, 22.0, 19.0).mapIndexed { index, speed ->
            detector.process(current, Observation(speed, (index + 1) * 1_000L)).also {
                current = it.state
            }.started
        }
        assertEquals(listOf(true, false, false, true), started)
    }

    @Test
    fun `five seconds is allowed and an extra millisecond breaks continuity`() {
        assertTrue(detector.process(state(30.0), Observation(15.0, 5_000)).started)
        val gap = detector.process(state(30.0, active = true), Observation(15.0, 5_001))
        assertNull(gap.decelerationMps2)
        assertFalse(gap.started)
        assertFalse(gap.state.active)
        assertTrue(detector.process(gap.state, Observation(12.0, 6_001)).started)
    }

    @Test
    fun `missing and invalid speeds do not become zero or connect across missing observations`() {
        listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { speed ->
            val missing = detector.process(state(30.0, active = true), Observation(speed, 1_000))
            assertNull(missing.decelerationMps2)
            assertFalse(missing.started)
            assertEquals(state(null, 1_000), missing.state)
            val recovered = detector.process(missing.state, Observation(20.0, 2_000))
            assertNull(recovered.decelerationMps2)
            assertFalse(recovered.started)
            assertTrue(detector.process(recovered.state, Observation(17.0, 3_000)).started)
        }
    }

    @Test
    fun `acceleration and stationary zero are valid observations`() {
        val accelerating = detector.process(state(0.0), Observation(3.0, 1_000))
        assertEquals(-3.0, requireNotNull(accelerating.decelerationMps2), 0.0)
        assertFalse(accelerating.started)
        val stationary = detector.process(state(0.0, active = true), Observation(0.0, 1_000))
        assertEquals(0.0, requireNotNull(stationary.decelerationMps2), 0.0)
        assertFalse(stationary.state.active)
        assertTrue(detector.process(state(3.0), Observation(0.0, 1_000)).started)
    }

    @Test
    fun `same observation is idempotent while braking remains active`() {
        val sample = Observation<Double?>(27.0, 1_000)
        val started = detector.process(state(30.0), sample)
        val repeated = detector.process(started.state, sample)
        assertEquals(started.state, repeated.state)
        assertNull(repeated.decelerationMps2)
        assertFalse(repeated.started)
        assertFalse(detector.process(repeated.state, Observation(24.0, 2_000)).started)
    }

    @Test
    fun `older or conflicting same timestamp breaks continuity without moving the time watermark backward`() {
        listOf(999L, 1_000L).forEach { elapsed ->
            val broken = detector.process(state(27.0, 1_000, true), Observation(30.0, elapsed))
            assertEquals(state(null, 1_000), broken.state)
            assertNull(broken.decelerationMps2)
            assertFalse(broken.started)
            val stillOld = detector.process(broken.state, Observation(26.0, 999))
            assertEquals(broken.state, stillOld.state)
            val baseline = detector.process(stillOld.state, Observation(24.0, 2_000))
            assertNull(baseline.decelerationMps2)
            assertTrue(detector.process(baseline.state, Observation(21.0, 3_000)).started)
        }
    }

    @Test
    fun `subsecond intervals use milliseconds and overflowed derivatives remain unknown`() {
        assertTrue(detector.process(state(3.0), Observation(2.997, 1)).started)
        val overflow = detector.process(state(Double.MAX_VALUE), Observation(0.0, 1))
        assertNull(overflow.decelerationMps2)
        assertFalse(overflow.started)
        assertFalse(overflow.state.active)
    }

    @Test
    fun `different trips and restarted sessions do not share derivative state`() {
        detector.process(state(30.0), Observation(27.0, 1_000))
        val fresh = detector.process(HarshBrakeState(), Observation(0.0, 0))
        assertNull(fresh.decelerationMps2)
        assertFalse(fresh.started)
    }

    @Test
    fun `invalid timestamps and inconsistent caller state fail explicitly`() {
        assertThrows(IllegalArgumentException::class.java) {
            detector.process(HarshBrakeState(), Observation(1.0, -1))
        }
        assertThrows(IllegalArgumentException::class.java) { state(1.0, -1) }
        assertThrows(IllegalArgumentException::class.java) { state(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { state(-1.0) }
        assertThrows(IllegalArgumentException::class.java) { state(null, active = true) }
        assertThrows(IllegalArgumentException::class.java) { HarshBrakeState(active = true) }
    }
}
