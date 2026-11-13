package com.pleos.explorer.domain.geo

import com.pleos.explorer.domain.model.ExploredSection
import kotlin.math.abs
import kotlin.math.sqrt

internal class SectionCoverage(private val grid: GridProjector) {
    fun uncoveredFraction(section: GridSection, previous: List<ExploredSection>): Double {
        val from = grid.project(section.from)
        val to = grid.project(section.to)
        val dx = to.x - from.x
        val dy = to.y - from.y
        val squaredLength = dx * dx + dy * dy
        if (squaredLength == 0.0) return 0.0
        val length = sqrt(squaredLength)
        val covered = previous.mapNotNull { old ->
            val a = grid.project(old.from)
            val b = grid.project(old.to)
            if (abs(dx * (a.y - from.y) - dy * (a.x - from.x)) / length > 1e-6 ||
                abs(dx * (b.y - from.y) - dy * (b.x - from.x)) / length > 1e-6) return@mapNotNull null
            val first = ((a.x - from.x) * dx + (a.y - from.y) * dy) / squaredLength
            val last = ((b.x - from.x) * dx + (b.y - from.y) * dy) / squaredLength
            val start = minOf(first, last).coerceIn(0.0, 1.0)
            val end = maxOf(first, last).coerceIn(0.0, 1.0)
            if (end > start) start to end else null
        }.sortedBy { it.first }
        var cursor = 0.0
        var total = 0.0
        for ((start, end) in covered) {
            if (start > cursor) total += start - cursor
            cursor = maxOf(cursor, end)
        }
        return (total + 1.0 - cursor).coerceIn(0.0, 1.0)
    }
}
