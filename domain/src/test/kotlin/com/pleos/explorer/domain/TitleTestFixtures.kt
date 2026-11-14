package com.pleos.explorer.domain

import com.pleos.explorer.domain.model.GhostPassage
import com.pleos.explorer.domain.model.GhostPassageVisit
import com.pleos.explorer.domain.model.GhostResult

object TitleTestFixtures {
    const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1_000
    const val NOW_MS = 1_700_000_000_000L

    fun result(distanceM: Double = 5_000.0, ageMs: Long = THIRTY_DAYS_MS): GhostResult {
        val visit = GhostPassageVisit("past", "segment", NOW_MS, NOW_MS + 200_000,
            NOW_MS - ageMs, NOW_MS + 200_000 - ageMs)
        val passage = GhostPassage(0, 0.0, 1.0, distanceM, NOW_MS, NOW_MS - ageMs,
            NOW_MS + 200_000 - ageMs, listOf(visit))
        return GhostResult("ghost", "trip", "past", distanceM, passage.firstPassedAtMs,
            passage.previousLastPassedAtMs, 0.0, null, emptyList(), emptyList(), listOf(passage))
    }
}
