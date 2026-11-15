package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.model.EfficiencySample
import com.pleos.explorer.domain.model.EfficiencyUnit
import com.pleos.explorer.domain.model.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class VehicleMetricsCalculatorTest {
    private val calculator = VehicleMetricsCalculator()

    private fun sample(
        at: Long,
        distanceM: Double?,
        consumption: Double?,
        unit: EfficiencyUnit = EfficiencyUnit.KM_PER_L,
    ) = EfficiencySample(Observation(distanceM, at), Observation(consumption, at), unit)

    private fun validSamples() = listOf(sample(0, 0.0, 0.0), sample(5_000, 100.0, 0.01))

    @Test
    fun `empty or single observation cannot establish an interval`() {
        assertNull(calculator.averageEfficiency(emptyList()))
        assertNull(calculator.averageEfficiency(listOf(sample(0, 0.0, 0.0))))
        assertNull(calculator.odometerDistanceM(emptyList()))
        assertNull(calculator.odometerDistanceM(listOf(Observation(10.0, 0))))
    }

    @Test
    fun `fuel and electric efficiency reuse explicit units and cumulative baselines`() {
        EfficiencyUnit.entries.forEach { unit ->
            val result = requireNotNull(calculator.averageEfficiency(listOf(
                sample(0, 1_000.0, 10.0, unit), sample(5_000, 1_100.0, 10.02, unit))))
            assertEquals(unit, result.unit)
            assertEquals(5.0, result.value, 1e-9)
        }
    }

    @Test
    fun `average uses total distance over total consumption rather than mean of interval efficiencies`() {
        val result = requireNotNull(calculator.averageEfficiency(listOf(
            sample(0, 0.0, 0.0), sample(5_000, 100.0, 0.01), sample(10_000, 300.0, 0.05))))
        assertEquals(6.0, result.value, 1e-12)
    }

    @Test
    fun `zero consumption intervals retain distance while aggregate zero consumption is unknown`() {
        val result = requireNotNull(calculator.averageEfficiency(listOf(
            sample(0, 0.0, 0.0), sample(5_000, 100.0, 0.0), sample(10_000, 200.0, 0.02))))
        assertEquals(10.0, result.value, 1e-12)
        assertNull(calculator.averageEfficiency(listOf(sample(0, 0.0, 1.0), sample(5_000, 100.0, 1.0))))
        val idling = requireNotNull(calculator.averageEfficiency(listOf(
            sample(0, 100.0, 0.0), sample(5_000, 100.0, 0.01))))
        assertEquals(0.0, idling.value, 0.0)
    }

    @Test
    fun `missing and invalid values invalidate the whole interval instead of producing partial statistics`() {
        listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { invalid ->
            for (index in 0..1) {
                val distance = validSamples().toMutableList()
                distance[index] = distance[index].copy(distanceM = Observation(invalid, index * 5_000L))
                assertNull(calculator.averageEfficiency(distance))
                val consumption = validSamples().toMutableList()
                consumption[index] = consumption[index].copy(consumption = Observation(invalid, index * 5_000L))
                assertNull(calculator.averageEfficiency(consumption))
            }
            assertNull(calculator.odometerDistanceM(listOf(Observation(0.0, 0), Observation(invalid, 5_000))))
            assertNull(calculator.odometerDistanceM(listOf(Observation(invalid, 0), Observation(1.0, 5_000))))
        }
    }

    @Test
    fun `intermediate counter resets are rejected even if final value exceeds the first baseline`() {
        assertNull(calculator.averageEfficiency(listOf(sample(0, 0.0, 0.0), sample(1_000, 100.0, 0.01),
            sample(2_000, 50.0, 0.02), sample(3_000, 200.0, 0.03))))
        assertNull(calculator.averageEfficiency(listOf(sample(0, 0.0, 0.0), sample(1_000, 100.0, 0.01),
            sample(2_000, 150.0, 0.005), sample(3_000, 200.0, 0.03))))
        assertNull(calculator.odometerDistanceM(listOf(Observation(10.0, 0), Observation(10.1, 1_000),
            Observation(10.05, 2_000), Observation(10.2, 3_000))))
    }

    @Test
    fun `fuel and energy readings are never mixed`() {
        val mixed = validSamples().toMutableList()
        mixed[1] = mixed[1].copy(unit = EfficiencyUnit.KM_PER_KWH)
        assertNull(calculator.averageEfficiency(mixed))
    }

    @Test
    fun `distance and consumption must describe the same original timestamps`() {
        listOf(0, 1).forEach { index ->
            val misaligned = validSamples().toMutableList()
            val observed = misaligned[index].consumption
            misaligned[index] = misaligned[index].copy(consumption = observed.copy(
                observedAtElapsedMs = observed.observedAtElapsedMs + 1))
            assertNull(calculator.averageEfficiency(misaligned))
        }
    }

    @Test
    fun `five seconds is allowed but a longer gap or reversed time makes data unknown`() {
        assertEquals(10.0, requireNotNull(calculator.averageEfficiency(validSamples())).value, 1e-12)
        listOf(5_001L, 0L).forEach { end ->
            assertNull(calculator.averageEfficiency(listOf(sample(0, 0.0, 0.0), sample(end, 100.0, 0.01))))
            assertNull(calculator.odometerDistanceM(listOf(Observation(1.0, 0), Observation(1.1, end))))
        }
        assertNull(calculator.averageEfficiency(validSamples().reversed()))
        assertNull(calculator.odometerDistanceM(listOf(Observation(1.0, 5_000), Observation(1.1, 4_999))))
    }

    @Test
    fun `identical duplicates do not add distance and conflicting duplicates invalidate statistics`() {
        val samples = validSamples()
        assertEquals(calculator.averageEfficiency(samples),
            calculator.averageEfficiency(listOf(samples[0], samples[0], samples[1], samples[1])))
        assertNull(calculator.averageEfficiency(listOf(samples[0], samples[0])))
        val counter: List<Observation<Double?>> = listOf(Observation(10.0, 0), Observation(10.1, 5_000))
        assertEquals(calculator.odometerDistanceM(counter),
            calculator.odometerDistanceM(listOf(counter[0], counter[0], counter[1], counter[1])))
        assertNull(calculator.odometerDistanceM(listOf(counter[0], counter[0])))
        assertNull(calculator.averageEfficiency(listOf(samples[0], sample(0, 1.0, 0.0), samples[1])))
        assertNull(calculator.averageEfficiency(listOf(samples[0], sample(0, 0.0, 0.001), samples[1])))
        assertNull(calculator.odometerDistanceM(listOf(counter[0], Observation(10.01, 0), counter[1])))
    }

    @Test
    fun `odometer kilometers convert to meters while stationary advanced observations remain zero`() {
        assertEquals(2.0, requireNotNull(calculator.odometerDistanceM(listOf(
            Observation(10.123, 0), Observation(10.125, 5_000)))), 1e-8)
        assertEquals(0.0, requireNotNull(calculator.odometerDistanceM(listOf(
            Observation(10.0, 0), Observation(10.0, 5_000)))), 0.0)
    }

    @Test
    fun `invalid odometer never prevents independent GPS based efficiency calculation`() {
        assertNull(calculator.odometerDistanceM(listOf(Observation(100.0, 0), Observation(0.0, 5_000))))
        assertEquals(10.0, requireNotNull(calculator.averageEfficiency(validSamples())).value, 1e-12)
    }

    @Test
    fun `unrepresentable results remain unknown and large monotonic clock values remain valid`() {
        assertNull(calculator.averageEfficiency(listOf(sample(0, 0.0, 0.0),
            sample(5_000, Double.MAX_VALUE, Double.MIN_VALUE))))
        assertNull(calculator.averageEfficiency(listOf(sample(0, 0.0, 0.0), sample(5_000, Double.MIN_VALUE, 1.0))))
        assertNull(calculator.odometerDistanceM(listOf(Observation(0.0, 0), Observation(Double.MAX_VALUE, 5_000))))
        val result = calculator.averageEfficiency(listOf(sample(Long.MAX_VALUE - 5_000, 0.0, 0.0),
            sample(Long.MAX_VALUE, 100.0, 0.01)))
        assertEquals(10.0, requireNotNull(result).value, 1e-12)
    }

    @Test
    fun `representable efficiency survives intermediate underflow and overflow`() {
        val tiny = calculator.averageEfficiency(listOf(sample(0, 0.0, 0.0),
            sample(5_000, Double.MIN_VALUE, Double.MIN_VALUE)))
        assertEquals(0.001, requireNotNull(tiny).value, 0.0)
        val large = calculator.averageEfficiency(listOf(sample(0, 0.0, 0.0),
            sample(5_000, Double.MAX_VALUE, 0.001)))
        assertEquals(1.0, requireNotNull(large).value / Double.MAX_VALUE, 1e-12)
    }

    @Test
    fun `calculation does not mutate inputs or retain values between requests`() {
        val samples = validSamples().toMutableList()
        val before = samples.toList()
        val first = calculator.averageEfficiency(samples)
        assertEquals(before, samples)
        calculator.averageEfficiency(listOf(sample(0, null, null)))
        assertEquals(first, calculator.averageEfficiency(samples))
        samples.add(sample(10_000, 200.0, 0.04))
        assertEquals(5.0, requireNotNull(calculator.averageEfficiency(samples)).value, 1e-12)
    }

    @Test
    fun `negative source clock is an explicit caller error`() {
        assertThrows(IllegalArgumentException::class.java) {
            calculator.averageEfficiency(listOf(sample(-1, 0.0, 0.0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            val value = sample(0, 0.0, 0.0).copy(consumption = Observation(0.0, -1))
            calculator.averageEfficiency(listOf(value))
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculator.odometerDistanceM(listOf(Observation(0.0, -1)))
        }
    }
}
