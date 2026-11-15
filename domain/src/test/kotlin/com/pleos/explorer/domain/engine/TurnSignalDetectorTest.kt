package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.model.Observation
import com.pleos.explorer.domain.model.TurnDirection
import com.pleos.explorer.domain.model.TurnSignal
import com.pleos.explorer.domain.model.TurnSignalInput
import com.pleos.explorer.domain.model.TurnSignalState
import com.pleos.explorer.domain.model.TurnSignalUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnSignalDetectorTest {
    private val detector = TurnSignalDetector(positiveAngleDirection = TurnDirection.LEFT)

    private fun signal(state: TurnSignalState, at: Long, value: TurnSignal?) =
        detector.process(state, TurnSignalInput.Signal(Observation(value, at))).state

    private fun angle(state: TurnSignalState, at: Long, value: Double?) =
        detector.process(state, TurnSignalInput.Angle(Observation(value, at)))

    private fun ready(value: TurnSignal = TurnSignal.OFF): TurnSignalState {
        var state = signal(TurnSignalState(), 0, value)
        state = angle(state, 0, 0.0).state
        state = angle(state, 4_000, 0.0).state
        return signal(state, 5_000, value)
    }

    @Test
    fun `turn threshold includes ninety degrees in both directions`() {
        listOf(-90.001, -90.0, -89.999, 89.999, 90.0, 90.001).forEach { degrees ->
            val update = angle(ready(), 5_000, degrees)
            val qualifies = kotlin.math.abs(degrees) >= 90.0
            assertEquals(qualifies, update.turn != null)
            assertEquals(if (qualifies) 1 else 0, update.state.usage.turnCount)
            if (qualifies) assertEquals(false, update.turn?.signaled)
        }
    }

    @Test
    fun `turn stays latched until angle is strictly below thirty degrees`() {
        var state = angle(ready(), 5_000, 90.0).state
        listOf(180.0, -90.0, 30.0, 90.0).forEachIndexed { index, degrees ->
            val update = angle(state, 6_000L + index * 500, degrees)
            assertNull(update.turn)
            state = update.state
        }
        state = angle(state, 8_000, 29.999).state
        assertTrue(state.armed)
        assertEquals(2, angle(state, 9_000, 90.0).state.usage.turnCount)
    }

    @Test
    fun `positive angle direction is explicit and reverses left right mapping`() {
        TurnDirection.entries.forEach { positive ->
            val configured = TurnSignalDetector(positive)
            listOf(-90.0, 90.0).forEach { degrees ->
                val update = configured.process(ready(), TurnSignalInput.Angle(Observation(degrees, 5_000)))
                val expected = if (degrees > 0) positive else when (positive) {
                    TurnDirection.LEFT -> TurnDirection.RIGHT
                    TurnDirection.RIGHT -> TurnDirection.LEFT
                }
                assertEquals(expected, update.turn?.direction)
                assertEquals(5_000L, update.turn?.elapsedMs)
            }
        }
    }

    @Test
    fun `signal usage requires matching direction and hazard alone does not count`() {
        assertEquals(true, angle(ready(TurnSignal.LEFT), 5_000, 90.0).turn?.signaled)
        assertEquals(false, angle(ready(TurnSignal.RIGHT), 5_000, 90.0).turn?.signaled)
        assertEquals(true, angle(ready(TurnSignal.RIGHT), 5_000, -90.0).turn?.signaled)
        listOf(-90.0, 90.0).forEach { degrees ->
            assertEquals(false, angle(ready(TurnSignal.HAZARD), 5_000, degrees).turn?.signaled)
        }
    }

    @Test
    fun `a matching signal remains evidence after off hazard or an opposite signal`() {
        listOf(TurnSignal.OFF, TurnSignal.HAZARD, TurnSignal.RIGHT).forEach { last ->
            var state = angle(TurnSignalState(), 0, 0.0).state
            state = signal(state, 0, TurnSignal.OFF)
            state = signal(state, 1_000, TurnSignal.LEFT)
            state = angle(state, 4_000, 0.0).state
            state = signal(state, 5_000, last)
            assertEquals(true, angle(state, 5_000, 90.0).turn?.signaled)
        }
    }

    @Test
    fun `matching signal lookback includes exactly five seconds but not an extra millisecond`() {
        listOf(5_000L, 5_001L).forEach { now ->
            var state = signal(TurnSignalState(), 0, TurnSignal.LEFT)
            state = angle(state, 0, 0.0).state
            state = signal(state, 2_500, TurnSignal.OFF)
            state = angle(state, 4_000, 0.0).state
            state = signal(state, now, TurnSignal.OFF)
            assertEquals(now == 5_000L, angle(state, now, 90.0).turn?.signaled)
        }
    }

    @Test
    fun `missing signal and incomplete lookback are unknown rather than unused`() {
        var state = angle(TurnSignalState(), 0, 0.0).state
        val unsupported = angle(state, 1_000, 90.0)
        assertEquals(1, unsupported.state.usage.turnCount)
        assertEquals(1, unsupported.state.usage.unknownTurnCount)
        assertNull(requireNotNull(unsupported.turn).signaled)
        assertNull(unsupported.state.usage.rate)
        state = signal(state, 0, TurnSignal.OFF)
        assertNull(requireNotNull(angle(state, 1_000, 90.0).turn).signaled)
        state = signal(state, 0, TurnSignal.LEFT)
        assertNull(requireNotNull(angle(state, 1_000, 90.0).turn).signaled)
        val knownOn = signal(angle(TurnSignalState(), 0, 0.0).state, 0, TurnSignal.LEFT)
        assertEquals(true, angle(knownOn, 1_000, 90.0).turn?.signaled)
    }

    @Test
    fun `null signal and a signal gap break negative evidence coverage`() {
        listOf(5_000L, 5_001L).forEach { interval ->
            var state = signal(TurnSignalState(), 0, TurnSignal.OFF)
            state = angle(state, 0, 0.0).state
            state = angle(state, 4_000, 0.0).state
            state = signal(state, interval, TurnSignal.OFF)
            val turn = requireNotNull(angle(state, interval, 90.0).turn)
            assertEquals(if (interval == 5_000L) false else null, turn.signaled)
        }
        val broken = signal(ready(TurnSignal.LEFT), 5_001, null)
        assertNull(broken.coverageStartMs)
        assertNull(broken.lastLeftMs)
        assertNull(requireNotNull(angle(broken, 5_001, 90.0).turn).signaled)
    }

    @Test
    fun `stale and future latest signals do not justify a turn verdict`() {
        val future = signal(ready(), 6_000, TurnSignal.LEFT)
        assertNull(requireNotNull(angle(future, 5_000, 90.0).turn).signaled)
        var stale = signal(TurnSignalState(), 0, TurnSignal.LEFT)
        stale = angle(stale, 0, 0.0).state
        stale = angle(stale, 4_000, 0.0).state
        assertNull(requireNotNull(angle(stale, 5_001, 90.0).turn).signaled)
    }

    @Test
    fun `first high angle and lost steering continuity wait for a neutral observation`() {
        assertNull(angle(TurnSignalState(), 0, 90.0).turn)
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { value ->
            val broken = angle(ready(), 5_000, value)
            assertFalse(broken.state.armed)
            val high = angle(broken.state, 6_000, 90.0)
            assertNull(high.turn)
            val neutral = angle(high.state, 7_000, -29.999)
            assertTrue(neutral.state.armed)
            assertEquals(1, angle(neutral.state, 8_000, -90.0).state.usage.turnCount)
        }
        val base = angle(TurnSignalState(), 0, 0.0).state
        assertEquals(1, angle(base, 5_000, 90.0).state.usage.turnCount)
        assertNull(angle(base, 5_001, 90.0).turn)
    }

    @Test
    fun `duplicate angle and later signal never count or revise an existing turn twice`() {
        val first = angle(ready(), 5_000, 90.0)
        val updated = signal(first.state, 5_001, TurnSignal.LEFT)
        val duplicate = angle(updated, 5_000, 90.0)
        assertNull(duplicate.turn)
        assertEquals(updated, duplicate.state)
        assertEquals(0.0, requireNotNull(duplicate.state.usage.rate), 0.0)
    }

    @Test
    fun `older and conflicting angles preserve watermark while losing their armed baseline`() {
        listOf(3_999L, 4_000L).forEach { at ->
            val broken = angle(ready(), at, 90.0)
            assertEquals(4_000L, broken.state.angle?.observedAtElapsedMs)
            assertNull(broken.state.angle?.value)
            assertFalse(broken.state.armed)
            assertNull(angle(broken.state, 5_000, 90.0).turn)
        }
    }

    @Test
    fun `duplicate signals do not refresh time and signal conflicts invalidate history`() {
        val state = ready(TurnSignal.LEFT)
        assertEquals(state, signal(state, 5_000, TurnSignal.LEFT))
        listOf(4_999L, 5_000L).forEach { at ->
            val broken = signal(state, at, TurnSignal.RIGHT)
            assertEquals(5_000L, broken.signal?.observedAtElapsedMs)
            assertNull(broken.signal?.value)
            assertNull(broken.lastLeftMs)
            assertNull(requireNotNull(angle(broken, 5_000, 90.0).turn).signaled)
        }
    }

    @Test
    fun `usage divides counts and remains unknown if any detected turn was unobserved`() {
        var state = angle(ready(TurnSignal.LEFT), 5_000, 90.0).state
        assertEquals(1.0, requireNotNull(state.usage.rate), 0.0)
        state = angle(state, 6_000, 0.0).state
        state = signal(state, 7_000, TurnSignal.OFF)
        state = angle(state, 7_000, -90.0).state
        assertEquals(TurnSignalUsage(2, 1, 0), state.usage)
        assertEquals(0.5, requireNotNull(state.usage.rate), 0.0)
        state = angle(state, 8_000, 0.0).state
        state = signal(state, 8_000, null)
        state = angle(state, 9_000, 90.0).state
        assertEquals(TurnSignalUsage(3, 1, 1), state.usage)
        assertNull(state.usage.rate)
        assertNull(TurnSignalUsage().rate)
        assertEquals(1.0 / 3, requireNotNull(TurnSignalUsage(2, 1).record(false).rate), 0.0)
    }

    @Test
    fun `a new trip or process session does not inherit counters or an armed turn`() {
        angle(ready(TurnSignal.LEFT), 5_000, 90.0)
        val fresh = angle(TurnSignalState(), 0, 0.0)
        assertEquals(TurnSignalUsage(), fresh.state.usage)
        assertNull(fresh.turn)
    }

    @Test
    fun `lost steering coverage makes usage unknown without inventing unobserved turn counts`() {
        val known = angle(ready(TurnSignal.LEFT), 5_000, 90.0).state
        listOf(6_000L to null, 11_001L to 0.0, 4_999L to 0.0, 5_000L to 0.0).forEach { (at, degrees) ->
            val broken = angle(known, at, degrees).state
            assertEquals(1, broken.usage.turnCount)
            assertEquals(0, broken.usage.unknownTurnCount)
            assertNull(broken.usage.rate)
            assertNull(broken.usage.record(true).rate)
        }
    }

    @Test
    fun `latest signal and direction history must agree in both directions`() {
        listOf(TurnSignal.LEFT, TurnSignal.RIGHT).forEach { latest ->
            listOf(null, 4_999L).forEach { history ->
                assertThrows(IllegalArgumentException::class.java) {
                    TurnSignalState(signal = Observation(latest, 5_000), coverageStartMs = 0,
                        lastLeftMs = history.takeIf { latest == TurnSignal.LEFT },
                        lastRightMs = history.takeIf { latest == TurnSignal.RIGHT })
                }
            }
        }
        listOf(TurnSignal.OFF, TurnSignal.HAZARD).forEach { latest ->
            listOf(TurnDirection.LEFT, TurnDirection.RIGHT).forEach { direction ->
                assertThrows(IllegalArgumentException::class.java) {
                    TurnSignalState(signal = Observation(latest, 5_000), coverageStartMs = 0,
                        lastLeftMs = 5_000L.takeIf { direction == TurnDirection.LEFT },
                        lastRightMs = 5_000L.takeIf { direction == TurnDirection.RIGHT })
                }
            }
        }
    }

    @Test
    fun `invalid clock caller state counts and counter overflow fail explicitly`() {
        assertThrows(IllegalArgumentException::class.java) { angle(TurnSignalState(), -1, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { signal(TurnSignalState(), -1, TurnSignal.OFF) }
        assertThrows(IllegalArgumentException::class.java) { TurnSignalState(angle = Observation(Double.NaN, 0)) }
        assertThrows(IllegalArgumentException::class.java) { TurnSignalState(armed = true) }
        assertThrows(IllegalArgumentException::class.java) { TurnSignalState(coverageStartMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { TurnSignalUsage(-1) }
        assertThrows(IllegalArgumentException::class.java) { TurnSignalUsage(1, 1, 1) }
        assertThrows(ArithmeticException::class.java) { TurnSignalUsage(Int.MAX_VALUE).record(false) }
    }
}
