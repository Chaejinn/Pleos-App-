package com.pleos.explorer.service

import com.pleos.explorer.domain.engine.LocationValidator
import com.pleos.explorer.domain.engine.TrackRecorder
import com.pleos.explorer.domain.model.GeoPoint
import com.pleos.explorer.domain.model.LocationSample
import com.pleos.explorer.domain.port.IdGenerator
import org.junit.Assert.*
import org.junit.Test

class LivePathTest {
    @Test fun livePreviewIsBoundedAndClearsWithoutTouchingDomainState() {
        var id = 0
        val recorder = TrackRecorder(IdGenerator { "id-${id++}" })
        val validator = LocationValidator()
        val drive = DriveStatus()
        var state = recorder.start("trip", 1_000_000, "session").snapshot
        repeat(550) { i ->
            val sample = LocationSample(1_000_000 + i * 1000L, i * 1000L,
                GeoPoint(36 + i * 0.00001, 127.0), null, null, 5.0)
            val update = recorder.process(state, validator.validate(sample, state.previous), i.toLong(), emptyMap())
            state = update.snapshot
            drive.updateTrack(update)
        }
        assertEquals(500, drive.state.value.livePath.sumOf { it.points.size })
        assertEquals(36.0005, drive.state.value.livePath.first().points.first().latitudeDeg, 0.0000001)
        drive.clearTrack()
        assertNull(drive.state.value.currentPoint)
        assertTrue(drive.state.value.livePath.isEmpty())
        assertTrue(state.trip.distanceM > 0)
    }
    @Test fun livePreviewDoesNotBridgeReceptionGaps() {
        var id = 0
        val recorder = TrackRecorder(IdGenerator { "id-${id++}" })
        val validator = LocationValidator()
        val drive = DriveStatus()
        var state = recorder.start("trip", 1_000_000, "session").snapshot
        listOf(0L, 1000L, 12000L).forEachIndexed { i, elapsed ->
            val sample = LocationSample(1_000_000 + elapsed, elapsed, GeoPoint(36 + i * 0.0001, 127.0), null, null, 5.0)
            val update = recorder.process(state, validator.validate(sample, state.previous), i.toLong(), emptyMap())
            state = update.snapshot
            drive.updateTrack(update)
        }
        assertEquals(2, drive.state.value.livePath.size)
    }
}
