package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.model.HarshSteerState
import com.pleos.explorer.domain.model.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HarshSteerDetectorTest {
    private val detector = HarshSteerDetector()
    private val minimumSpeedMps = 20.0 / 3.6

    private fun state(angleDeg: Double?, elapsedMs: Long = 0, active: Boolean = false) =
        HarshSteerState(Observation(angleDeg, elapsedMs), active)

    private fun speed(value: Double? = 10.0, elapsedMs: Long = 1_000) = Observation(value, elapsedMs)

    @Test
    fun `first angle establishes a baseline and stationary steering remains measurable`() {
        val first = detector.process(HarshSteerState(), Observation(0.0, 0), speed(elapsedMs = 0))
        assertEquals(state(0.0), first.state)
        assertNull(first.angularVelocityDegPerSec)
        assertFalse(first.started)
        val stationary = detector.process(first.state, Observation(0.0, 1_000), speed(0.0))
        assertEquals(0.0, requireNotNull(stationary.angularVelocityDegPerSec), 0.0)
        assertFalse(stationary.started)
    }

    @Test
    fun `speed and angular velocity thresholds include exact boundaries in both directions`() {
        listOf(minimumSpeedMps - 0.001, minimumSpeedMps, minimumSpeedMps + 0.001).forEach { velocity ->
            listOf(-180.001, -180.0, -179.999, 179.999, 180.0, 180.001).forEach { rate ->
                val update = detector.process(state(0.0), Observation(rate, 1_000), speed(velocity))
                assertEquals(rate, requireNotNull(update.angularVelocityDegPerSec), 0.0)
                val qualifies = velocity >= minimumSpeedMps && kotlin.math.abs(rate) >= 180.0
                assertEquals(qualifies, update.started)
                assertEquals(qualifies, update.state.active)
            }
        }
    }

    @Test
    fun `continuous rapid steering emits once even when direction changes and rearms after recovery`() {
        var current = state(0.0)
        val started = listOf(180.0, 360.0, 180.0, 200.0, 400.0).mapIndexed { index, angle ->
            val elapsed = (index + 1) * 1_000L
            detector.process(current, Observation(angle, elapsed), speed(elapsedMs = elapsed)).also {
                current = it.state
            }.started
        }
        assertEquals(listOf(true, false, false, false, true), started)
    }

    @Test
    fun `valid low speed clears the episode and a new qualifying interval can start another`() {
        val lowSpeed = detector.process(state(180.0, active = true), Observation(360.0, 1_000), speed(0.0))
        assertFalse(lowSpeed.state.active)
        assertFalse(lowSpeed.started)
        assertTrue(detector.process(lowSpeed.state, Observation(540.0, 2_000), speed(elapsedMs = 2_000)).started)
    }

    @Test
    fun `five second steering interval is allowed but a longer gap is not differentiated`() {
        assertTrue(detector.process(state(0.0), Observation(900.0, 5_000), speed(elapsedMs = 5_000)).started)
        val gap = detector.process(state(0.0, active = true), Observation(900.0, 5_001), speed(elapsedMs = 5_001))
        assertNull(gap.angularVelocityDegPerSec)
        assertFalse(gap.state.active)
        assertFalse(gap.started)
        assertTrue(detector.process(gap.state, Observation(1_080.0, 6_001), speed(elapsedMs = 6_001)).started)
    }

    @Test
    fun `speed age includes five seconds and excludes stale or future observations`() {
        val angle = Observation<Double?>(180.0, 6_000)
        assertTrue(detector.process(state(0.0, 5_000), angle, speed(elapsedMs = 1_000)).started)
        listOf(999L, 6_001L).forEach { observed ->
            val unknown = detector.process(state(0.0, 5_000, true), angle, speed(elapsedMs = observed))
            assertNull(unknown.angularVelocityDegPerSec)
            assertFalse(unknown.started)
            assertEquals(state(null, 6_000), unknown.state)
        }
    }

    @Test
    fun `missing or invalid speed remains unknown and does not bridge unobserved intervals`() {
        val inputs = listOf(null, speed(null), speed(-1.0), speed(Double.NaN),
            speed(Double.POSITIVE_INFINITY), speed(Double.NEGATIVE_INFINITY))
        inputs.forEach { observed ->
            val unknown = detector.process(state(0.0, active = true), Observation(180.0, 1_000), observed)
            assertNull(unknown.angularVelocityDegPerSec)
            assertFalse(unknown.started)
            assertEquals(state(null, 1_000), unknown.state)
            val recovered = detector.process(unknown.state, Observation(360.0, 2_000), speed(elapsedMs = 2_000))
            assertNull(recovered.angularVelocityDegPerSec)
            assertFalse(recovered.started)
            assertTrue(detector.process(recovered.state, Observation(540.0, 3_000), speed(elapsedMs = 3_000)).started)
        }
    }

    @Test
    fun `missing or invalid steering angle breaks continuity without inventing a zero angle`() {
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { angle ->
            val unknown = detector.process(state(0.0, active = true), Observation(angle, 1_000), speed())
            assertNull(unknown.angularVelocityDegPerSec)
            assertFalse(unknown.started)
            assertEquals(state(null, 1_000), unknown.state)
            val recovered = detector.process(unknown.state, Observation(180.0, 2_000), speed(elapsedMs = 2_000))
            assertNull(recovered.angularVelocityDegPerSec)
            assertTrue(detector.process(recovered.state, Observation(360.0, 3_000), speed(elapsedMs = 3_000)).started)
        }
    }

    @Test
    fun `replayed steering angle never reemits an episode or reevaluates changed speed context`() {
        val angle = Observation<Double?>(180.0, 1_000)
        val started = detector.process(state(0.0), angle, speed())
        val duplicate = detector.process(started.state, angle, null)
        assertEquals(started.state, duplicate.state)
        assertNull(duplicate.angularVelocityDegPerSec)
        assertFalse(duplicate.started)
        assertFalse(detector.process(duplicate.state, Observation(360.0, 2_000), speed(elapsedMs = 2_000)).started)
        val lowSpeed = detector.process(state(0.0), angle, speed(0.0))
        assertEquals(lowSpeed.state, detector.process(lowSpeed.state, angle, speed()).state)
    }

    @Test
    fun `out of order and conflicting same timestamp angles preserve the watermark and invalidate the baseline`() {
        listOf(999L, 1_000L).forEach { elapsed ->
            val broken = detector.process(state(180.0, 1_000, true), Observation(0.0, elapsed), speed())
            assertEquals(state(null, 1_000), broken.state)
            assertNull(broken.angularVelocityDegPerSec)
            assertFalse(broken.started)
            val older = detector.process(broken.state, Observation(90.0, 999), speed())
            assertEquals(broken.state, older.state)
            val baseline = detector.process(older.state, Observation(360.0, 2_000), speed(elapsedMs = 2_000))
            assertNull(baseline.angularVelocityDegPerSec)
            assertTrue(detector.process(baseline.state, Observation(540.0, 3_000), speed(elapsedMs = 3_000)).started)
        }
    }

    @Test
    fun `wheel angle is signed and continuous rather than a modulo 360 GPS bearing`() {
        val crossing = detector.process(state(350.0), Observation(370.0, 1_000), speed())
        assertEquals(20.0, requireNotNull(crossing.angularVelocityDegPerSec), 0.0)
        assertFalse(crossing.started)
        val reversing = detector.process(state(170.0), Observation(-170.0, 1_000), speed())
        assertEquals(-340.0, requireNotNull(reversing.angularVelocityDegPerSec), 0.0)
        assertTrue(reversing.started)
    }

    @Test
    fun `subsecond and large clock values stay valid while numerical overflow remains unknown`() {
        assertTrue(detector.process(state(0.0), Observation(0.181, 1), speed(elapsedMs = 1)).started)
        assertTrue(detector.process(state(0.0, Long.MAX_VALUE - 1_000), Observation(180.0, Long.MAX_VALUE),
            speed(elapsedMs = Long.MAX_VALUE)).started)
        val overflow = detector.process(state(Double.MAX_VALUE), Observation(-Double.MAX_VALUE, 1),
            speed(elapsedMs = 1))
        assertNull(overflow.angularVelocityDegPerSec)
        assertFalse(overflow.state.active)
        assertFalse(overflow.started)
    }

    @Test
    fun `fresh trip or restarted session has no previous derivative or active episode`() {
        detector.process(state(0.0), Observation(180.0, 1_000), speed())
        val fresh = detector.process(HarshSteerState(), Observation(1_000.0, 0), speed(elapsedMs = 0))
        assertEquals(state(1_000.0), fresh.state)
        assertNull(fresh.angularVelocityDegPerSec)
        assertFalse(fresh.started)
    }

    @Test
    fun `negative timestamps and inconsistent caller state fail explicitly`() {
        assertThrows(IllegalArgumentException::class.java) {
            detector.process(HarshSteerState(), Observation(0.0, -1), speed())
        }
        assertThrows(IllegalArgumentException::class.java) {
            detector.process(HarshSteerState(), Observation(0.0, 0), speed(elapsedMs = -1))
        }
        assertThrows(IllegalArgumentException::class.java) { state(0.0, -1) }
        assertThrows(IllegalArgumentException::class.java) { state(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { state(null, active = true) }
        assertThrows(IllegalArgumentException::class.java) { HarshSteerState(active = true) }
    }
}
