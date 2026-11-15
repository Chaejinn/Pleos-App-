package com.pleos.explorer.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GhostPassageTest {
    private fun passage(vararg visits: GhostPassageVisit) = GhostPassage(0, 0.0, 1.0, 100.0,
        0, 0, Long.MAX_VALUE, visits.toList())

    @Test
    fun `only the portion past the minimum age is credited for increasing or reversing clocks`() {
        val forward = GhostPassageVisit("old", "s", 100_000, 110_000, 90_000, 90_000)
        val reversed = forward.copy(currentFromAtMs = 110_000, currentToAtMs = 100_000)
        assertEquals(50.0, passage(forward).revisitedDistanceM(15_000), 0.0001)
        assertEquals(50.0, passage(reversed).revisitedDistanceM(15_000), 0.0001)
    }

    @Test
    fun `all visits must be old enough at the same location when latest visits cross`() {
        val a = GhostPassageVisit("a", "s", 100_000, 110_000, 90_000, 90_000)
        val b = GhostPassageVisit("b", "s", 100_000, 110_000, 80_000, 100_000)
        assertEquals(60.0, passage(a, b).revisitedDistanceM(12_000), 0.0001)
        assertEquals(0.0, passage(a, b).revisitedDistanceM(16_000), 0.0)
    }

    @Test
    fun `summary extrema cannot override paired timing evidence`() {
        val old = GhostPassageVisit("old", "s", 100_000, 110_000, 50_000, 60_000)
        val recent = old.copy(pastTripId = "recent", pastFromAtMs = 99_000, pastToAtMs = 109_000)
        assertEquals(100.0, passage(old).revisitedDistanceM(50_000), 0.0)
        assertEquals(0.0, passage(old, recent).revisitedDistanceM(50_000), 0.0)
        assertEquals(100.0, passage(old.copy(pastTripId = null)).revisitedDistanceM(50_000), 0.0)
    }

    @Test
    fun `unverifiable older data and future observations cannot qualify`() {
        assertEquals(0.0, passage().revisitedDistanceM(0), 0.0)
        assertEquals(0.0, passage(GhostPassageVisit("future", "s", 100_000, 110_000, 100_001, 110_001))
            .revisitedDistanceM(0), 0.0)
    }

    @Test
    fun `invalid timing or distance fails instead of awarding a title`() {
        val visit = GhostPassageVisit("old", "s", 100_000, 110_000, 90_000, 100_000)
        assertThrows(IllegalArgumentException::class.java) { passage(visit).revisitedDistanceM(-1) }
        assertThrows(IllegalArgumentException::class.java) { passage(visit).copy(distanceM = Double.NaN).revisitedDistanceM(0) }
        assertThrows(IllegalArgumentException::class.java) { passage(visit).copy(distanceM = -1.0).revisitedDistanceM(0) }
        assertThrows(IllegalArgumentException::class.java) { passage(visit.copy(currentFromAtMs = -1)).revisitedDistanceM(0) }
        assertThrows(IllegalArgumentException::class.java) { passage(visit.copy(currentToAtMs = -1)).revisitedDistanceM(0) }
        assertThrows(IllegalArgumentException::class.java) { passage(visit.copy(pastFromAtMs = -1)).revisitedDistanceM(0) }
        assertThrows(IllegalArgumentException::class.java) { passage(visit.copy(pastToAtMs = -1)).revisitedDistanceM(0) }
        assertThrows(IllegalArgumentException::class.java) { passage(visit, visit.copy(currentFromAtMs = 100_001))
            .revisitedDistanceM(0) }
        assertThrows(IllegalArgumentException::class.java) { passage(visit, visit.copy(currentToAtMs = 110_001))
            .revisitedDistanceM(0) }
        val recent = visit.copy(pastFromAtMs = 100_000, pastToAtMs = 110_000)
        assertThrows(IllegalArgumentException::class.java) { passage(recent, visit.copy(pastFromAtMs = -1))
            .revisitedDistanceM(30_000) }
        assertThrows(IllegalArgumentException::class.java) { passage(recent, visit.copy(currentToAtMs = 110_001))
            .revisitedDistanceM(30_000) }
    }
}
