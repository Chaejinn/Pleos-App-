package com.pleos.explorer.service

import androidx.room.Room
import com.pleos.explorer.data.local.recording.*
import com.pleos.explorer.di.ExplorerApplication
import com.pleos.explorer.domain.engine.TrackRecorder
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
class GhostRecordingSessionTest {
    private val base = System.currentTimeMillis() - 100_000
    private fun sample(i: Int, at: Long, reverse: Boolean = false) = LocationSample(at + i * 1000L, i * 1000L,
        GeoPoint(36.0 + (if (reverse) 60 - i else i) * 0.0001, 127.0), 11.1, if (reverse) 180.0 else 0.0, 5.0)
    @Test fun gpsHistoryProducesFinalComparisonAndDeletionBreaksPastReferences() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("ghost-test.db")
        val cipher = RecordingCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        var db = Room.databaseBuilder(context, RecordingDatabase::class.java, "ghost-test.db").build()
        var store = RoomTrackRecordingStore(db, cipher)
        fun engine() = RecordTrackUseCase(store.gpsTracks, TrackRecorder(IdGenerator { java.util.UUID.randomUUID().toString() }))
        val past = engine()
        val pastTime = base - 31L * 24 * 60 * 60 * 1000
        past.start("past", pastTime, "past-session")
        store.setInputSource("past", RecordingInputSource.ANDROID_GPS)
        (0..480).forEach { past.process("past", "past-session", it.toLong(), sample(it, pastTime)) }
        past.finish("past", pastTime + 481_000)
        val current = engine()
        val ghost = GhostRecordingSession(store, store.gpsHistory())
        current.start("current", base, "current-session")
        store.setInputSource("current", RecordingInputSource.ANDROID_GPS)
        ghost.start("current", "current-session")
        (0..480).forEach { ghost.process(current.process("current", "current-session", it.toLong(), sample(it, base))) }
        ghost.process(current.finish("current", base + 481_000))
        val result = store.ghostResults().single()
        assertTrue(result.finalized)
        assertTrue(result.matchedDistanceM >= 5000)
        assertEquals("ROAD_REVISITOR",store.achievementTitles().single().titleId)
        assertNull(result.metricsDiff)
        assertEquals("past", result.pastTripId)
        assertEquals(1, store.pendingRegionRewards().count { it.kind == RewardKind.GHOST_RESULT })
        db.close()
        db = Room.databaseBuilder(context, RecordingDatabase::class.java, "ghost-test.db").build()
        store = RoomTrackRecordingStore(db, cipher)
        assertEquals(result.id, store.ghostResults().single().id)
        store.deleteTrip("past")
        val retained = store.ghostResults().single()
        assertNull(retained.pastTripId)
        assertTrue(retained.passages.flatMap { it.visits }.all { it.pastTripId == null })
        assertEquals(result.pastPath, retained.pastPath)
        store.deleteTrip("current")
        assertTrue(store.ghostResults().isEmpty())
        assertTrue(store.pendingRegionRewards().none { it.kind == RewardKind.GHOST_RESULT })
        assertEquals("ROAD_REVISITOR",store.achievementTitles().single().titleId)
        assertNull(store.achievementTitles().single().tripId)
        db.close()
    }
    @Test fun syntheticReplayAndUnknownSourcesAreNotGhostHistory() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RecordingDatabase::class.java).build()
        val store = RoomTrackRecordingStore(db, RecordingCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()))
        for (source in listOf(RecordingInputSource.REPLAY, RecordingInputSource.UNKNOWN)) {
            val track = RecordTrackUseCase(store, TrackRecorder(IdGenerator { java.util.UUID.randomUUID().toString() }))
            track.start(source.name, base, source.name)
            store.setInputSource(source.name, source)
            (0..30).forEach { track.process(source.name, source.name, it.toLong(), sample(it, base)) }
            track.finish(source.name, base + 31_000)
        }
        assertTrue(store.gpsHistory().isEmpty())
        db.close()
    }
    @Test fun interruptedGhostResumesWithGapAndProducesOneFinalResult() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RecordingDatabase::class.java).build()
        val store = RoomTrackRecordingStore(db, RecordingCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()))
        fun engine() = RecordTrackUseCase(store.gpsTracks, TrackRecorder(IdGenerator { java.util.UUID.randomUUID().toString() }))
        val past = engine()
        past.start("past", base - 100_000, "past-session")
        store.setInputSource("past", RecordingInputSource.ANDROID_GPS)
        (0..60).forEach { past.process("past", "past-session", it.toLong(), sample(it,base - 100_000)) }
        past.finish("past",base - 39_000)
        val track = engine()
        val original = GhostRecordingSession(store, store.gpsHistory())
        track.start("current",base,"first")
        store.setInputSource("current",RecordingInputSource.ANDROID_GPS)
        original.start("current","first")
        (0..55).forEach { original.process(track.process("current","first",it.toLong(),sample(it,base))) }
        assertNotNull(store.ghosts.load("current")?.active)
        val recovered = GhostRecordingSession(store,store.gpsHistory())
        store.atomic {
            recovered.resume(engine().resume("current","recovered"))
            recovered.process(engine().finish("current",base + 56_000))
        }
        assertTrue(store.ghostResults().single().finalized)
        assertEquals(1,store.pendingRegionRewards().count { it.kind == RewardKind.GHOST_RESULT })
        db.close()
    }
}
