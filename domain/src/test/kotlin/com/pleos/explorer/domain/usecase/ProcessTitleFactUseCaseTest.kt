package com.pleos.explorer.domain.usecase

import com.pleos.explorer.domain.TitleTestFixtures.NOW_MS
import com.pleos.explorer.domain.TitleTestFixtures.result
import com.pleos.explorer.domain.model.AchievementFact
import com.pleos.explorer.domain.model.AchievementProgress
import com.pleos.explorer.domain.model.AchievementTitle
import com.pleos.explorer.domain.model.PendingReward
import com.pleos.explorer.domain.model.RewardKind
import com.pleos.explorer.domain.model.TitleSnapshot
import com.pleos.explorer.domain.model.TitleUpdate
import com.pleos.explorer.domain.port.IdGenerator
import com.pleos.explorer.domain.port.TitleStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessTitleFactUseCaseTest {
    private class Store : TitleStore {
        var state = TitleSnapshot(progress = AchievementProgress(30))
        val titles = linkedMapOf<String, AchievementTitle>()
        val rewards = linkedMapOf<String, PendingReward>()
        var failLoad = false
        var failCommit = false
        var cancel = false
        var loseAck = false
        var conflict = false
        var commits = 0
        override suspend fun load(): TitleSnapshot {
            if (failLoad) throw IOException("read failed")
            return state
        }
        override suspend fun commit(expectedRevision: Long, update: TitleUpdate) {
            if (failCommit) { failCommit = false; throw IOException("write failed") }
            if (cancel) { cancel = false; throw CancellationException("cancelled") }
            if (conflict) { conflict = false; state = state.copy(revision = state.revision + 1) }
            check(expectedRevision == state.revision)
            update.titles.forEach { check(it.titleId !in titles) }
            update.rewards.forEach { check(it.deduplicationKey !in rewards) }
            titles.putAll(update.titles.associateBy { it.titleId })
            rewards.putAll(update.rewards.associateBy { it.deduplicationKey })
            state = update.snapshot
            commits++
            if (loseAck) { loseAck = false; throw IOException("response lost") }
        }
    }

    private val badge = AchievementFact.BadgeGranted("REGION:A", "trip", NOW_MS)
    private fun useCase(store: Store): ProcessTitleFactUseCase {
        var id = 0
        return ProcessTitleFactUseCase(store, IdGenerator { "reward-${++id}" })
    }

    @Test
    fun `first grant stores title and reward together and repeated facts are empty`() = runBlocking {
        val store = Store()
        val useCase = useCase(store)
        val update = useCase.process(badge)
        val title = update.titles.single()
        val reward = update.rewards.single()
        assertEquals("REGION_EXPLORER", title.titleId)
        assertEquals("TITLE:REGION_EXPLORER", reward.deduplicationKey)
        assertEquals(RewardKind.TITLE, reward.kind)
        assertEquals(title.titleId, reward.subjectId)
        assertEquals(title.acquiredAtMs, reward.occurredAtMs)
        assertEquals(title.tripId, reward.tripId)
        assertEquals(null, reward.acknowledgedAtMs)
        assertEquals(1L, store.state.revision)
        assertTrue(useCase.process(badge).titles.isEmpty())
        assertTrue(useCase.process(badge.copy(atMs = NOW_MS - 1)).rewards.isEmpty())
        assertTrue(useCase(store).process(badge).titles.isEmpty())
        assertEquals(1, store.commits)
    }

    @Test
    fun `counts come from committed distinct regions rather than event increments`() = runBlocking {
        val store = Store()
        store.state = store.state.copy(progress = AchievementProgress(29))
        val useCase = useCase(store)
        repeat(40) { assertTrue(useCase.process(badge).titles.isEmpty()) }
        assertEquals(29, store.state.progress.regionBadgeCount)
        assertEquals(0, store.commits)
        store.state = store.state.copy(progress = AchievementProgress(30))
        assertEquals(1, useCase.process(badge).titles.size)
    }

    @Test
    fun `both titles coexist without replaying acknowledged rewards`() = runBlocking {
        val store = Store()
        val useCase = useCase(store)
        useCase.process(badge)
        val key = "TITLE:REGION_EXPLORER"
        store.rewards[key] = store.rewards.getValue(key).copy(acknowledgedAtMs = NOW_MS + 1)
        useCase.process(AchievementFact.GhostFinished(result(), NOW_MS + 200_000))
        assertEquals(setOf("REGION_EXPLORER", "ROAD_REVISITOR"), store.titles.keys)
        assertEquals(NOW_MS + 1, store.rewards.getValue(key).acknowledgedAtMs)
        assertEquals(2, store.rewards.size)
        assertTrue(useCase.process(badge).rewards.isEmpty())
    }

    @Test
    fun `read and write failures leave no partial title and can retry`() = runBlocking {
        val store = Store()
        val useCase = useCase(store)
        store.failLoad = true
        assertThrows(IOException::class.java) { runBlocking { useCase.process(badge) } }
        store.failLoad = false
        store.failCommit = true
        assertThrows(IOException::class.java) { runBlocking { useCase.process(badge) } }
        assertEquals(0L, store.state.revision)
        assertTrue(store.titles.isEmpty() && store.rewards.isEmpty())
        assertEquals(1, useCase.process(badge).titles.size)
    }

    @Test
    fun `cancellation propagates and persisted state remains unchanged`() = runBlocking {
        val store = Store()
        val useCase = useCase(store)
        store.cancel = true
        assertThrows(CancellationException::class.java) { runBlocking { useCase.process(badge) } }
        assertTrue(store.titles.isEmpty() && store.rewards.isEmpty())
        assertEquals(1, useCase.process(badge).titles.size)
    }

    @Test
    fun `lost commit response and restart never grant a second reward`() = runBlocking {
        val store = Store()
        store.loseAck = true
        assertThrows(IOException::class.java) { runBlocking { useCase(store).process(badge) } }
        assertEquals(1, store.titles.size)
        assertEquals(1, store.rewards.size)
        assertTrue(useCase(store).process(badge).rewards.isEmpty())
        assertEquals(1, store.commits)
    }

    @Test
    fun `stale snapshot is rejected and can be reloaded on retry`() = runBlocking {
        val store = Store()
        val useCase = useCase(store)
        store.conflict = true
        assertThrows(IllegalStateException::class.java) { runBlocking { useCase.process(badge) } }
        assertTrue(store.titles.isEmpty() && store.rewards.isEmpty())
        useCase.process(badge)
        assertEquals(2L, store.state.revision)
        assertEquals(1, store.titles.size)
    }

    @Test
    fun `invalid reward id or revision fails before commit`() = runBlocking {
        val store = Store()
        val broken = ProcessTitleFactUseCase(store, IdGenerator { " " })
        assertThrows(IllegalStateException::class.java) { runBlocking { broken.process(badge) } }
        store.state = store.state.copy(revision = -1)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { useCase(store).process(badge) } }
        assertEquals(0, store.commits)
    }
}
