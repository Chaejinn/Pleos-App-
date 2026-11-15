package com.pleos.explorer.data.local.recording

import androidx.room.Room
import com.pleos.explorer.di.ExplorerApplication
import com.pleos.explorer.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ExplorerApplication::class)
class AchievementStorageTest {
    @Test fun thirtiethBadgeAtomicallyAwardsTitleOnceAndRetainsItAfterTripDeletion() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("title-test.db")
        val cipher = RecordingCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        var db = Room.databaseBuilder(context, RecordingDatabase::class.java, "title-test.db").build()
        var store = RoomTrackRecordingStore(db, cipher)
        val state = RegionVisitSnapshot("session", "trip")
        store.regionVisits.commit(null, RegionVisitUpdate(state))
        val badges = (1..30).associate { n -> "REGION:DEMO_$n" to RegionBadge("REGION:DEMO_$n", "DEMO_$n", BadgeType.REGION, "trip", 1000, 1) }
        val reward = PendingReward("badge-reward", "BADGE:REGION:DEMO_30", "trip", RewardKind.BADGE, "REGION:DEMO_30", 1000)
        store.regionVisits.commit(0, RegionVisitUpdate(state.copy(revision = 1), badges = badges, rewards = listOf(reward)))
        assertEquals("REGION_EXPLORER", store.achievementTitles().single().titleId)
        assertEquals(2, store.pendingRegionRewards().size)
        val speech = requireNotNull(store.nextRewardForSpeech("trip"))
        assertTrue(store.claimRewardSpeech(speech.id))
        assertFalse(store.claimRewardSpeech(speech.id))
        assertNotEquals(speech.id,store.nextRewardForSpeech("trip")?.id)
        assertEquals(2,store.pendingRegionRewards().size)
        store.regionVisits.commit(1, RegionVisitUpdate(state.copy(revision = 2), badges = mapOf("REGION:DEMO_30" to badges.getValue("REGION:DEMO_30").copy(visitCount = 2))))
        assertEquals(1, store.achievementTitles().size)
        db.close()
        db = Room.databaseBuilder(context, RecordingDatabase::class.java, "title-test.db").build()
        store = RoomTrackRecordingStore(db, cipher)
        assertFalse(store.claimRewardSpeech(speech.id))
        assertEquals(30, store.titleStore.load().progress.regionBadgeCount)
        assertEquals(1, store.pendingRegionRewards().count { it.kind == RewardKind.TITLE })
        store.deleteTrip("trip")
        assertNull(store.achievementTitles().single().tripId)
        assertEquals(30, store.regionBadges().size)
        assertTrue(store.pendingRegionRewards().all { it.tripId == null })
        store.deleteAll()
        assertTrue(store.achievementTitles().isEmpty())
        db.close()
    }
    @Test fun titleRevisionConflictDoesNotPersistPartialReward() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RecordingDatabase::class.java).build()
        val store = RoomTrackRecordingStore(db, RecordingCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()))
        try {
            store.titleStore.commit(1, TitleUpdate(TitleSnapshot(2)))
            fail()
        } catch (_: IllegalStateException) { }
        assertTrue(store.achievementTitles().isEmpty())
        assertTrue(store.pendingRegionRewards().isEmpty())
        assertEquals(0L, store.titleStore.load().revision)
        db.close()
    }
}
