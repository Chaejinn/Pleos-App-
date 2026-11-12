package com.pleos.explorer.domain.engine.legacy

import com.pleos.explorer.domain.geo.legacy.Geo
import com.pleos.explorer.domain.geo.legacy.RouteFingerprint
import com.pleos.explorer.domain.model.legacy.DrivingEvent
import com.pleos.explorer.domain.model.legacy.SegmentStats
import com.pleos.explorer.domain.model.legacy.Trip
import com.pleos.explorer.domain.model.legacy.VehicleSignal

/**
 * 주행 하나를 기록한다. 이동 거리로 구간을 나누고, 구간마다 사건 수와 평균 효율을 모은다.
 * 구간이 끝날 때마다 [onSegmentClosed]를 불러 고스트 중계를 할 수 있게 한다.
 */
class TripRecorder(
    private val id: String,
    private val segmentLengthM: Double = 1_000.0,
    private val onSegmentClosed: (SegmentStats) -> Unit = {},
) {
    private val detector = DrivingEventDetector()
    private val fingerprint = RouteFingerprint()
    private val closed = mutableListOf<SegmentStats>()
    private var current = SegmentStats(0)
    private var effSum = 0f
    private var effCount = 0
    private var distanceInSegment = 0.0
    private var lastLoc: VehicleSignal.Location? = null
    private var startedAt: Long? = null

    val fingerprintSoFar: RouteFingerprint get() = fingerprint

    fun onSignal(signal: VehicleSignal): DrivingEvent? {
        if (startedAt == null) startedAt = signal.timeMs
        if (signal is VehicleSignal.Efficiency) {
            effSum += signal.value; effCount++
        }
        if (signal is VehicleSignal.Location) onLocation(signal)

        val event = detector.onSignal(signal)
        current = when (event) {
            is DrivingEvent.HarshBrake -> current.copy(harshBrakes = current.harshBrakes + 1)
            is DrivingEvent.HarshSteer -> current.copy(harshSteers = current.harshSteers + 1)
            is DrivingEvent.TurnWithoutSignal -> current.copy(turnsWithoutSignal = current.turnsWithoutSignal + 1)
            null -> current
        }
        return event
    }

    private fun onLocation(loc: VehicleSignal.Location) {
        fingerprint.add(loc.lat, loc.lon)
        lastLoc?.let { distanceInSegment += Geo.distanceM(it.lat, it.lon, loc.lat, loc.lon) }
        lastLoc = loc
        if (distanceInSegment >= segmentLengthM) closeSegment()
    }

    private fun closeSegment() {
        val done = current.copy(avgEfficiency = if (effCount > 0) effSum / effCount else null)
        closed += done
        onSegmentClosed(done)
        current = SegmentStats(done.index + 1)
        effSum = 0f; effCount = 0; distanceInSegment = 0.0
    }

    /** 주행 종료(P단 등). 남은 구간을 닫고 기록을 돌려준다. */
    fun finish(): Trip {
        if (distanceInSegment > segmentLengthM / 3) closeSegment()
        return Trip(id, startedAt ?: 0L, fingerprint.cells.toList(), closed.toList())
    }
}
