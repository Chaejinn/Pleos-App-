package com.pleos.explorer.domain.geo

import com.pleos.explorer.domain.GhostTestFixtures.history
import com.pleos.explorer.domain.GhostTestFixtures.location
import com.pleos.explorer.domain.model.HistoricalTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostMatcherTest {
    private val matcher = GhostMatcher()

    @Test
    fun `distance and circular direction thresholds reject nearby wrong routes`() {
        assertFalse(matcher.matches(location(125.0, 0, bearing = 45.0), listOf(history())).isEmpty())
        assertTrue(matcher.matches(location(125.0, 0, bearing = 45.01), listOf(history())).isEmpty())
        assertFalse(matcher.matches(location(125.0, 0, bearing = 359.0), listOf(history(bearing = 1.0))).isEmpty())
        assertTrue(matcher.matches(location(125.0, 0, bearing = 180.0), listOf(history())).isEmpty())
        assertFalse(matcher.matches(location(125.0, 0, eastM = 29.99), listOf(history())).isEmpty())
        assertTrue(matcher.matches(location(125.0, 0, eastM = 30.01), listOf(history())).isEmpty())
        assertTrue(matcher.matches(location(125.0, 0, bearing = null), listOf(history())).isEmpty())
        assertTrue(matcher.matches(location(125.0, 0), listOf(history(bearing = null))).isEmpty())
    }

    @Test
    fun `matching interpolates sparse points and preserves forward position`() {
        val past = history()
        val a = matcher.matches(location(12.5, 0), listOf(past)).minBy { it.distanceM }
        val b = matcher.matches(location(37.5, 1_000), listOf(past)).minBy { it.distanceM }
        assertEquals(25.0, requireNotNull(matcher.advance(a.cursor, b.cursor, listOf(past))), 0.001)
        assertNull(matcher.advance(b.cursor, a.cursor, listOf(past)))
        assertEquals(0.0, requireNotNull(matcher.advance(a.cursor, a.cursor, listOf(past))), 0.0)
    }

    @Test
    fun `missing sequence segments and historical gaps cannot be bridged`() {
        val base = history()
        val separated = listOf(
            HistoricalTrack(base.tripId, base.points.filter { it.sequence != 2L }),
            base.copy(points = base.points.map { if (it.sequence >= 2) it.copy(pathSegmentId = "next") else it }),
        )
        separated.forEach { past ->
            val a = matcher.matches(location(12.5, 0), listOf(past)).minBy { it.distanceM }
            val b = matcher.matches(location(87.5, 1_000), listOf(past)).minBy { it.distanceM }
            assertNull(matcher.advance(a.cursor, b.cursor, listOf(past)))
        }
    }

    @Test
    fun `wall clock correction does not break F03 sequence and segment continuity`() {
        listOf(-60_000L, 60_000L).forEach { offset ->
            val base = history()
            val past = base.copy(points = base.points.map {
                if (it.sequence >= 2) it.copy(timeMs = it.timeMs + offset) else it
            })
            val a = matcher.matches(location(12.5, 0), listOf(past)).minBy { it.distanceM }
            val b = matcher.matches(location(87.5, 1_000), listOf(past)).minBy { it.distanceM }
            assertEquals(75.0, requireNotNull(matcher.advance(a.cursor, b.cursor, listOf(past))), 0.001)
        }
    }

    @Test
    fun `whole straight segment stays covered inside the thirty meter corridor`() {
        val past = history()
        val a = location(12.5, 0, 29.99)
        val b = location(112.5, 5_000, 29.99)
        val from = matcher.matches(a, listOf(past)).minBy { it.distanceM }.cursor
        val to = matcher.matches(b, listOf(past)).minBy { it.distanceM }.cursor
        assertTrue(matcher.covers(a, b, from, to, listOf(past)))
        assertFalse(matcher.covers(a, location(112.5, 5_000, 30.01), from, to, listOf(past)))
        assertTrue(matcher.covers(a, a, from, from, listOf(past)))
    }

    @Test
    fun `invalid history fails instead of silently looking unmatched`() {
        val past = history()
        val invalid = listOf(past.copy(points = past.points.reversed()),
            past.copy(points = past.points.map { it.copy(tripId = "wrong") }),
            past.copy(points = past.points.map { it.copy(accuracyM = Double.NaN) }))
        invalid.forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) { matcher.matches(location(0.0, 0), listOf(candidate)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            matcher.matches(location(0.0, 0), listOf(past, past))
        }
    }
}
