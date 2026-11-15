package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.TitleTestFixtures.NOW_MS
import com.pleos.explorer.domain.TitleTestFixtures.THIRTY_DAYS_MS
import com.pleos.explorer.domain.TitleTestFixtures.result
import com.pleos.explorer.domain.model.AchievementFact
import com.pleos.explorer.domain.model.AchievementProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleEvaluatorTest {
    private val evaluator = TitleEvaluator()
    private val badge = AchievementFact.BadgeGranted("REGION:A", "trip", NOW_MS)
    private fun ghost(distanceM: Double = 5_000.0, ageMs: Long = THIRTY_DAYS_MS) =
        AchievementFact.GhostFinished(result(distanceM, ageMs), NOW_MS + 200_000)

    @Test
    fun `region title includes thirty and excludes twenty nine`() {
        listOf(29, 30, 31).forEach { count ->
            val qualified = evaluator.evaluate(badge, AchievementProgress(count))
            assertEquals(if (count >= 30) listOf("REGION_EXPLORER") else emptyList<String>(),
                qualified.map { it.titleId })
        }
        val title = evaluator.evaluate(badge, AchievementProgress(30)).single()
        assertEquals("trip", title.tripId)
        assertEquals(NOW_MS, title.acquiredAtMs)
        assertEquals("서로 다른 시·군·구 지역 뱃지 30개 획득", title.conditionText)
    }

    @Test
    fun `university cannot trigger region title and parked badge can have no trip`() {
        assertTrue(evaluator.evaluate(badge.copy(badgeId = "UNIVERSITY:U"), AchievementProgress(30)).isEmpty())
        assertEquals(null, evaluator.evaluate(badge.copy(tripId = null), AchievementProgress(30)).single().tripId)
    }

    @Test
    fun `already acquired titles are never proposed again`() {
        assertTrue(evaluator.evaluate(badge, AchievementProgress(31, setOf("REGION_EXPLORER"))).isEmpty())
        assertTrue(evaluator.evaluate(ghost(), AchievementProgress(0, setOf("ROAD_REVISITOR"))).isEmpty())
    }

    @Test
    fun `revisit distance includes five kilometers and excludes one millimeter below`() {
        listOf(4_999.999, 5_000.0, 5_000.001).forEach { distance ->
            val qualified = evaluator.evaluate(ghost(distance), AchievementProgress())
            assertEquals(if (distance >= 5_000) listOf("ROAD_REVISITOR") else emptyList<String>(),
                qualified.map { it.titleId })
        }
    }

    @Test
    fun `thirty days includes exact boundary and excludes one millisecond below`() {
        listOf(-1L, 0L, 1L).forEach { delta ->
            assertEquals(if (delta < 0) 0 else 1,
                evaluator.evaluate(ghost(ageMs = THIRTY_DAYS_MS + delta), AchievementProgress()).size)
        }
    }

    @Test
    fun `recent partial visit removes only its distance from the qualifying total`() {
        val original = result(6_000.0)
        val old = original.passages.single()
        fun input(recentM: Double): AchievementFact.GhostFinished {
            val cut = recentM / 6_000
            val recent = old.copy(fromFraction = 0.0, toFraction = cut, distanceM = recentM,
                visits = old.visits.map { it.copy(pastFromAtMs = NOW_MS - 1_000, pastToAtMs = NOW_MS + 199_000) })
            val rest = old.copy(fromFraction = cut, toFraction = 1.0, distanceM = 6_000 - recentM)
            return AchievementFact.GhostFinished(original.copy(passages = listOf(recent, rest)), NOW_MS + 200_000)
        }
        assertEquals(1, evaluator.evaluate(input(1_000.0), AchievementProgress()).size)
        assertTrue(evaluator.evaluate(input(1_000.001), AchievementProgress()).isEmpty())
    }

    @Test
    fun `separate short ghost results cannot combine into five kilometers`() {
        val state = AchievementProgress()
        repeat(2) { assertTrue(evaluator.evaluate(ghost(3_000.0), state).isEmpty()) }
    }

    @Test
    fun `finished ghost qualifies before discovery distance finalization`() {
        val fact = ghost()
        val title = evaluator.evaluate(fact, AchievementProgress()).single()
        assertEquals("ROAD_REVISITOR", title.titleId)
        assertEquals(fact.atMs, title.acquiredAtMs)
        assertEquals("trip", title.tripId)
        assertEquals(1, evaluator.evaluate(fact.copy(result = fact.result.copy(newRoadM = 9_999.0, finalized = true)),
            AchievementProgress()).size)
    }

    @Test
    fun `display summaries and missing evidence cannot manufacture revisit distance`() {
        val fact = ghost()
        val source = fact.result
        val missing = source.copy(passages = source.passages.map { it.copy(visits = emptyList()) })
        assertTrue(evaluator.evaluate(fact.copy(result = missing), AchievementProgress()).isEmpty())
        assertTrue(evaluator.evaluate(fact.copy(result = source.copy(passages = emptyList())), AchievementProgress()).isEmpty())
        val misleading = result(ageMs = 1_000).copy(firstPassedAtMs = 0, previousLastPassedAtMs = 0)
        assertTrue(evaluator.evaluate(fact.copy(result = misleading), AchievementProgress()).isEmpty())
    }

    @Test
    fun `removed past trip references and reversed wall clock preserve paired evidence`() {
        val source = result()
        val reversed = source.copy(pastTripId = null, passages = source.passages.map { passage ->
            passage.copy(visits = passage.visits.map { it.copy(pastTripId = null,
                currentFromAtMs = it.currentToAtMs, currentToAtMs = it.currentFromAtMs,
                pastFromAtMs = it.pastToAtMs, pastToAtMs = it.pastFromAtMs) })
        })
        assertEquals(1, evaluator.evaluate(AchievementFact.GhostFinished(reversed, NOW_MS), AchievementProgress()).size)
    }

    @Test
    fun `malformed facts and persisted progress fail before any qualification`() {
        listOf(badge.copy(badgeId = "REGION:"), badge.copy(badgeId = "other"),
            badge.copy(tripId = " "), badge.copy(atMs = -1)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { evaluator.evaluate(invalid, AchievementProgress(30)) }
        }
        assertThrows(IllegalArgumentException::class.java) { evaluator.evaluate(badge, AchievementProgress(-1)) }
        assertThrows(IllegalArgumentException::class.java) {
            evaluator.evaluate(badge, AchievementProgress(30, setOf(" ")))
        }
        val source = result()
        val bad = listOf(source.copy(matchedDistanceM = Double.NaN), source.copy(id = ""),
            source.copy(pastTripId = source.tripId), source.copy(passages = source.passages + source.passages),
            source.copy(passages = source.passages.map { it.copy(fromFraction = 0.1) }),
            source.copy(passages = source.passages.map { it.copy(distanceM = 6_000.0) }),
            source.copy(passages = source.passages.map { it.copy(visits = it.visits.map { visit ->
                visit.copy(pastFromAtMs = -1) }) }))
        bad.forEach { invalid -> assertThrows(IllegalArgumentException::class.java) {
            evaluator.evaluate(AchievementFact.GhostFinished(invalid, NOW_MS), AchievementProgress())
        } }
        assertThrows(IllegalArgumentException::class.java) {
            evaluator.evaluate(ghost().copy(atMs = -1), AchievementProgress())
        }
    }
}
