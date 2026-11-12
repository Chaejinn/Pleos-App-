package com.pleos.explorer.domain.engine.legacy

import com.pleos.explorer.domain.geo.legacy.RouteFingerprint
import com.pleos.explorer.domain.model.legacy.SegmentStats
import com.pleos.explorer.domain.model.legacy.VehicleSignal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostAndRouteTest {

    /** 북쪽으로 일정 속도로 달리는 가짜 주행. 급제동 지점을 지정할 수 있다. */
    private fun drive(recorder: TripRecorder, harshBrakeAtKm: Set<Int> = emptySet()) {
        var t = 0L
        var lat = 37.50
        val stepDeg = 0.0005 // 약 55m
        repeat(80) { i ->
            val km = (i * 55) / 1000
            val brakeNow = km in harshBrakeAtKm && i % 18 == 5
            recorder.onSignal(VehicleSignal.Location(lat, 127.0, 50f / 3.6f, 0f, t))
            if (brakeNow) recorder.onSignal(VehicleSignal.Location(lat, 127.0, 20f / 3.6f, 0f, t + 1_000))
            recorder.onSignal(VehicleSignal.Efficiency(14f, t))
            lat += stepDeg
            t += 4_000
        }
    }

    @Test
    fun sameRouteMatches() {
        val a = TripRecorder("a").also { drive(it) }.finish()
        val b = TripRecorder("b").also { drive(it) }.finish()
        val sim = RouteMatcher.similarity(RouteFingerprint.of(a.route), RouteFingerprint.of(b.route))
        assertTrue(sim > 0.9)
        assertEquals("a", RouteMatcher.bestMatch(RouteFingerprint.of(b.route), listOf("a" to RouteFingerprint.of(a.route))))
    }

    @Test
    fun differentRouteDoesNotMatch() {
        val a = RouteFingerprint().apply { add(37.50, 127.00); add(37.51, 127.00) }
        val b = RouteFingerprint().apply { add(35.10, 129.00); add(35.11, 129.00) }
        assertNull(RouteMatcher.bestMatch(b, listOf("a" to a)))
    }

    @Test
    fun ghostCommentsWhenFewerHarshBrakes() {
        val yesterday = TripRecorder("y").also { drive(it, harshBrakeAtKm = setOf(1)) }.finish()
        assertTrue(yesterday.segments[1].harshBrakes > 0)

        val comments = mutableListOf<String>()
        val ghost = GhostComparator(yesterday)
        val today = TripRecorder("t") { seg -> ghost.commentFor(seg)?.let { comments += it } }
        drive(today)
        val trip = today.finish()

        assertTrue(comments.any { it.contains("급제동") })
        assertTrue(ghost.summary(trip).contains("줄었어요"))
    }

    @Test
    fun noCommentWhenBothClean() {
        val y = TripRecorder("y").also { drive(it) }.finish()
        val ghost = GhostComparator(y)
        assertNull(ghost.commentFor(SegmentStats(0, avgEfficiency = 14f)))
        assertNotNull(ghost.commentFor(SegmentStats(0, harshSteers = 2, avgEfficiency = 14f)))
    }
}
