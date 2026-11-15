package com.pleos.explorer.data.local.recording

import androidx.room.Room
import com.pleos.explorer.di.ExplorerApplication
import com.pleos.explorer.domain.engine.TrackRecorder
import com.pleos.explorer.domain.model.*
import com.pleos.explorer.domain.port.IdGenerator
import com.pleos.explorer.domain.usecase.RecordTrackUseCase
import com.pleos.explorer.service.GhostRecordingSession
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
class RecordingAtomicityTest {
    private fun sample(i: Int) = LocationSample(1_800_000_000_000 + i * 1000L, i * 1000L, GeoPoint(36.0 + i * 0.0001,127.0),11.1,0.0,5.0)
    @Test fun failedDownstreamWriteRollsBackRecordingAndAllowsSameSequenceRetry() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RecordingDatabase::class.java).build()
        val store = RoomTrackRecordingStore(db, RecordingCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()))
        val engine = RecordTrackUseCase(store.gpsTracks, TrackRecorder(IdGenerator { java.util.UUID.randomUUID().toString() }))
        engine.start("gps", sample(0).timeMs, "s")
        store.setInputSource("gps", RecordingInputSource.ANDROID_GPS)
        val ghosts = GhostRecordingSession(store, emptyList())
        ghosts.start("gps", "s")
        try {
            store.atomic {
                ghosts.process(engine.process("gps", "s", 0, sample(0)))
                store.regionVisits.commit(null, RegionVisitUpdate(RegionVisitSnapshot("s", "gps")))
                error("Simulated downstream failure")
            }
            fail()
        } catch (_: IllegalStateException) { }
        assertEquals(0L, store.load("gps")?.revision)
        assertTrue(store.loadPoints("gps").isEmpty())
        assertNull(store.regionVisits.load())
        assertEquals(0L, store.ghosts.load("gps")?.revision)
        assertEquals(0L, store.ghostComparisons.load("gps")?.revision)
        store.atomic { ghosts.process(engine.process("gps", "s", 0, sample(0))) }
        assertEquals(1L, store.load("gps")?.revision)
        db.close()
    }
    @Test fun replayRoadsCannotConsumeGpsNewRoadCreditAndDeletionPreservesCredit() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RecordingDatabase::class.java).build()
        val store = RoomTrackRecordingStore(db, RecordingCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()))
        suspend fun record(id: String, gps: Boolean): TrackUpdate {
            val engine = RecordTrackUseCase(if (gps) store.gpsTracks else store, TrackRecorder(IdGenerator { java.util.UUID.randomUUID().toString() }))
            engine.start(id, sample(0).timeMs, id)
            store.setInputSource(id, if (gps) RecordingInputSource.ANDROID_GPS else RecordingInputSource.REPLAY)
            (0..30).forEach { engine.process(id,id,it.toLong(),sample(it)) }
            return engine.finish(id,sample(31).timeMs)
        }
        record("replay", false)
        assertEquals(0.0, store.titleStore.load().progress.newRoadDistanceM, 0.0)
        val first = record("gps", true)
        store.recordGpsTripAchievement(first)
        val credit = store.titleStore.load().progress.newRoadDistanceM
        assertTrue(credit > 200)
        store.recordGpsTripAchievement(first)
        assertEquals(credit, store.titleStore.load().progress.newRoadDistanceM, 0.001)
        store.deleteTrip("gps")
        assertEquals(credit, store.titleStore.load().progress.newRoadDistanceM, 0.001)
        store.recordGpsTripAchievement(record("gps-again", true))
        assertEquals(credit, store.titleStore.load().progress.newRoadDistanceM, 0.001)
        db.close()
    }
}
