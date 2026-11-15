package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.GhostTestFixtures.location
import com.pleos.explorer.domain.GhostTestFixtures.point
import com.pleos.explorer.domain.model.Coast
import com.pleos.explorer.domain.model.CoastPoint
import com.pleos.explorer.domain.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoastVisitDetectorTest {
    private val east = CoastPoint("east", Coast.EAST, point(0.0))
    private val west = CoastPoint("west", Coast.WEST, point(20_000.0))

    @Test
    fun `approved observation at and inside two kilometres produces original visit evidence`() {
        val detector = CoastVisitDetector(listOf(east, west))
        for (distance in listOf(0.0, 1_999.999, 2_000.0)) {
            val source = location(distance, 10_000)
            val visit = detector.detect(source, "trip").single()
            assertEquals("east", visit.coastId)
            assertEquals(Coast.EAST, visit.coast)
            assertEquals(source.timeMs, visit.atMs)
            assertEquals("trip", visit.tripId)
        }
        assertTrue(detector.detect(location(2_000.001, 11_000)).isEmpty())
    }

    @Test
    fun `coast classification comes only from supplied designated points`() {
        val detector = CoastVisitDetector(listOf(east, west))
        assertEquals(Coast.WEST, detector.detect(location(20_000.0, 0)).single().coast)
        assertTrue(CoastVisitDetector(emptyList()).detect(location(0.0, 0)).isEmpty())
        assertEquals(2, CoastVisitDetector(listOf(east, east.copy(id = "east-2"))).detect(location(0.0, 0)).size)
    }

    @Test
    fun `invalid catalog and trip identifiers fail explicitly`() {
        assertThrows(IllegalArgumentException::class.java) { CoastVisitDetector(listOf(east, east)) }
        assertThrows(IllegalArgumentException::class.java) { east.copy(id = " ") }
        assertThrows(IllegalArgumentException::class.java) { east.copy(point = GeoPoint(Double.NaN, 0.0)) }
        assertThrows(IllegalArgumentException::class.java) { east.copy(point = GeoPoint(91.0, 0.0)) }
        assertThrows(IllegalArgumentException::class.java) {
            CoastVisitDetector(listOf(east)).detect(location(0.0, 0), "")
        }
    }
}
