package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.TitleTestFixtures.NOW_MS
import com.pleos.explorer.domain.TitleTestFixtures.result
import com.pleos.explorer.domain.model.AchievementFact
import com.pleos.explorer.domain.model.AchievementProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NewRoadTitleTest {
    private val evaluator = TitleEvaluator()
    private val fact = AchievementFact.TripFinished("trip", NOW_MS, 1_000.0)

    @Test
    fun `one hundred kilometers is inclusive without rounding a millimeter below`() {
        listOf(99_999.999, 100_000.0, 100_000.001).forEach { distance ->
            val progress = AchievementProgress(newRoadDistanceM = distance)
            assertEquals(if (distance >= 100_000) listOf("NEW_ROAD_DISCOVERER") else emptyList<String>(),
                evaluator.evaluate(fact, progress).map { it.titleId })
        }
    }

    @Test
    fun `trip distance is not added a second time to committed progress`() {
        assertTrue(evaluator.evaluate(fact.copy(newRoadM = 2_000.0),
            AchievementProgress(newRoadDistanceM = 99_000.0)).isEmpty())
        val title = evaluator.evaluate(fact, AchievementProgress(newRoadDistanceM = 100_000.0)).single()
        assertEquals("trip", title.tripId)
        assertEquals(NOW_MS, title.acquiredAtMs)
        assertEquals("처음 기록된 도로 누적 100km 탐험", title.conditionText)
    }

    @Test
    fun `only trip completion evaluates new road title while existing titles remain independent`() {
        val progress = AchievementProgress(newRoadDistanceM = 100_000.0)
        assertTrue(evaluator.evaluate(AchievementFact.BadgeGranted("REGION:A", null, NOW_MS), progress).isEmpty())
        assertEquals(listOf("ROAD_REVISITOR"), evaluator.evaluate(AchievementFact.GhostFinished(result(), NOW_MS),
            progress).map { it.titleId })
        assertTrue(evaluator.evaluate(fact, progress.copy(acquiredTitleIds = setOf("NEW_ROAD_DISCOVERER"))).isEmpty())
    }

    @Test
    fun `zero new distance can use preserved historical cumulative progress without increasing it`() {
        assertEquals(1, evaluator.evaluate(fact.copy(newRoadM = 0.0),
            AchievementProgress(newRoadDistanceM = 100_000.0)).size)
        assertEquals(0.0, AchievementProgress(30, setOf("REGION_EXPLORER")).newRoadDistanceM, 0.0)
    }

    @Test
    fun `invalid source distance and incomplete committed cumulative fail before granting`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                evaluator.evaluate(fact, AchievementProgress(newRoadDistanceM = invalid))
            }
            assertThrows(IllegalArgumentException::class.java) {
                evaluator.evaluate(fact.copy(newRoadM = invalid), AchievementProgress(newRoadDistanceM = 100_000.0))
            }
        }
        listOf(fact.copy(tripId = " "), fact.copy(atMs = -1), fact.copy(newRoadM = 100_001.0)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                evaluator.evaluate(invalid, AchievementProgress(newRoadDistanceM = 100_000.0))
            }
        }
    }
}
