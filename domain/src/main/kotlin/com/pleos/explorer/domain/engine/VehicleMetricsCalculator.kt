package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.model.DetectionPolicy
import com.pleos.explorer.domain.model.Efficiency
import com.pleos.explorer.domain.model.EfficiencySample
import com.pleos.explorer.domain.model.Observation

class VehicleMetricsCalculator {
    fun averageEfficiency(samples: List<EfficiencySample>): Efficiency? {
        samples.forEach { require(it.distanceM.observedAtElapsedMs >= 0 && it.consumption.observedAtElapsedMs >= 0) }
        if (samples.size < 2) return null
        val unit = samples.first().unit
        if (samples.any { it.unit != unit || it.distanceM.observedAtElapsedMs != it.consumption.observedAtElapsedMs }) {
            return null
        }
        val distanceM = counterIncrease(samples.map { it.distanceM }) ?: return null
        val consumption = counterIncrease(samples.map { it.consumption }) ?: return null
        if (consumption <= 0) return null
        val ratio = distanceM / consumption
        val value = if (ratio.isFinite()) ratio / 1_000.0 else (distanceM / 1_000.0) / consumption
        if (!value.isFinite() || distanceM > 0 && value == 0.0) return null
        return Efficiency(value, unit)
    }

    fun odometerDistanceM(readings: List<Observation<Double?>>): Double? {
        readings.forEach { require(it.observedAtElapsedMs >= 0) }
        val distanceKm = counterIncrease(readings) ?: return null
        return (distanceKm * 1_000.0).takeIf { it.isFinite() }
    }

    private fun counterIncrease(readings: List<Observation<Double?>>): Double? {
        if (readings.size < 2) return null
        val first = readings.first().value?.takeIf { it.isFinite() && it >= 0 } ?: return null
        var previous = readings.first()
        var previousValue = first
        var hasInterval = false
        for (index in 1 until readings.size) {
            val current = readings[index]
            val value = current.value?.takeIf { it.isFinite() && it >= 0 } ?: return null
            val intervalMs = current.observedAtElapsedMs - previous.observedAtElapsedMs
            if (intervalMs < 0 || intervalMs > DetectionPolicy.METRICS_SAMPLE_GAP_MS || value < previousValue ||
                intervalMs == 0L && value != previousValue) return null
            hasInterval = hasInterval || intervalMs > 0
            previous = current
            previousValue = value
        }
        return if (hasInterval) previousValue - first else null
    }
}
