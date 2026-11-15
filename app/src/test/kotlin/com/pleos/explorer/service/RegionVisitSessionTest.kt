package com.pleos.explorer.service

import androidx.room.Room
import com.pleos.explorer.data.catalog.RegionCatalogState
import com.pleos.explorer.data.local.recording.*
import com.pleos.explorer.di.ExplorerApplication
import com.pleos.explorer.domain.engine.TrackRecorder
import com.pleos.explorer.domain.geo.IndexedRegionCatalog
import com.pleos.explorer.domain.model.*
import com.pleos.explorer.domain.port.IdGenerator
import com.pleos.explorer.domain.usecase.RecordTrackUseCase
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
class RegionVisitSessionTest {
    private fun catalog(): RegionCatalogState.Ready {
        val ring = listOf(GeoPoint(35.9,126.9), GeoPoint(35.9,127.1), GeoPoint(36.1,127.1), GeoPoint(36.1,126.9), GeoPoint(35.9,126.9))
        val regions = listOf(RegionDefinition("DEMO_A", "합성 테스트 지역", "DEMO", GeoPoint(36.0,127.0), RegionGeometry(listOf(RegionPolygon(ring)))))
        return RegionCatalogState.Ready(regions, IndexedRegionCatalog(regions))
    }
    @Test fun dwellRewardSurvivesReopenAndDiscardAndAcknowledgesOnce() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("region-test.db")
        val crypto = RecordingCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        var db = Room.databaseBuilder(context, RecordingDatabase::class.java, "region-test.db").build()
        var store = RoomTrackRecordingStore(db, crypto)
        val track = RecordTrackUseCase(store, TrackRecorder(IdGenerator { java.util.UUID.randomUUID().toString() }))
        val visit = RegionVisitSession(store, catalog())
        track.start("trip", 1_700_000_000_000, "session")
        visit.start("session", "trip")
        (0..181).forEach { i -> visit.process(track.process("trip", "session", i.toLong(), LocationSample(
            1_700_000_000_000 + i * 1000L, i * 1000L, GeoPoint(36.0,127.0), 0.0, null, 5.0))) }
        assertEquals(1, store.regionBadges().single().visitCount)
        val reward = store.pendingRegionRewards().single()
        val result = track.finish("trip", 1_700_000_182_000)
        assertEquals(TripStatus.DISCARDED, result.snapshot.status)
        visit.finish(true, "trip")
        assertNull(store.regionBadges().single().firstTripId)
        assertNull(store.pendingRegionRewards().single().tripId)
        db.close()
        db = Room.databaseBuilder(context, RecordingDatabase::class.java, "region-test.db").build()
        store = RoomTrackRecordingStore(db, crypto)
        assertEquals(reward.id, store.pendingRegionRewards().single().id)
        val before = requireNotNull(store.regionVisits.load())
        try { store.regionVisits.commit(before.revision - 1, RegionVisitUpdate(before.copy(revision = before.revision + 1))); fail() }
        catch (_: IllegalStateException) { }
        assertEquals(before.revision, store.regionVisits.load()?.revision)
        store.acknowledgeRegionReward(reward.id, 1_700_000_183_000)
        store.acknowledgeRegionReward(reward.id, 1_700_000_184_000)
        assertTrue(store.pendingRegionRewards().isEmpty())
        assertEquals(1, store.regionBadges().size)
        store.deleteAll()
        assertTrue(store.regionBadges().isEmpty())
        assertNull(store.regionVisits.load())
        db.close()
    }
    @Test fun missingCatalogDoesNotCreateVisitState() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RecordingDatabase::class.java).build()
        val store = RoomTrackRecordingStore(db, RecordingCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()))
        RegionVisitSession(store, RegionCatalogState.Missing).start("session", "trip")
        assertNull(store.regionVisits.load())
        db.close()
    }
}
