package com.pleos.explorer.data.local.recording

import androidx.room.Room
import com.pleos.explorer.di.ExplorerApplication
import com.pleos.explorer.domain.engine.TrackRecorder
import com.pleos.explorer.domain.model.GeoPoint
import com.pleos.explorer.domain.model.LocationSample
import com.pleos.explorer.domain.model.TripStatus
import com.pleos.explorer.domain.model.legacy.Landmark
import com.pleos.explorer.domain.model.legacy.LandmarkType
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
class ExplorationStorageTest {
    private val catalog = listOf("one", "two").map { Landmark(it, it, LandmarkType.LANDMARK, "서울", 37.5, 127.0, 200.0) }
    private fun crypto() = RecordingCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
    private fun engine(store: RoomTrackRecordingStore, source: RecordingInputSource) = RecordTrackUseCase(
        SourcedTrackStore(store, source), TrackRecorder(IdGenerator { java.util.UUID.randomUUID().toString() }))
    private fun sample(i: Int, offset: Long = 0, accuracy: Double = 5.0) = LocationSample(
        1_800_000_000_000 + offset + i * 1000L, i * 1000L, GeoPoint(37.5 + i * .0001, 127.0), 11.1, 0.0, accuracy)
    private suspend fun record(store: RoomTrackRecordingStore, id: String, source: RecordingInputSource, offset: Long = 0) {
        val useCase = engine(store, source)
        useCase.start(id, sample(0, offset).timeMs, id)
        store.setInputSource(id, source)
        for (i in 0..80) useCase.process(id, id, i.toLong(), sample(i, offset))
        useCase.finish(id, sample(81, offset).timeMs)
    }

    @Test fun visitsAreDurableAndAcknowledgedOnceWithoutMixingSimulation() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("landmark-reopen.db")
        val cipher = crypto()
        var db = Room.databaseBuilder(context, RecordingDatabase::class.java, "landmark-reopen.db").build()
        var store = RoomTrackRecordingStore(db, cipher, catalog)
        record(store, "demo", RecordingInputSource.REPLAY)
        assertEquals(2, store.landmarkVisits().size)
        assertTrue(store.landmarkVisits().all { it.isReplay })
        val key = store.landmarkVisits().first().key
        store.acknowledgeLandmark(key)
        db.close()
        db = Room.databaseBuilder(context, RecordingDatabase::class.java, "landmark-reopen.db").build()
        store = RoomTrackRecordingStore(db, cipher, catalog)
        assertTrue(store.landmarkVisits().first { it.key == key }.acknowledged)
        record(store, "gps", RecordingInputSource.ANDROID_GPS, 100_000)
        assertEquals(4, store.landmarkVisits().size)
        assertTrue(store.ghostResults(listOf("gps")).isEmpty())
        store.deleteTrip("demo")
        assertEquals(4, store.landmarkVisits().size)
        assertTrue(store.landmarkVisits().filter { it.isReplay }.all { it.tripId == null })
        store.deleteAll()
        assertTrue(store.landmarkVisits().isEmpty())
        assertTrue(store.collectedTitles().isEmpty())
        db.close()
    }

    @Test fun inaccurateFixDoesNotRewardAndShortTripKeepsBadgeWithoutDanglingTrip() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RecordingDatabase::class.java).build()
        val store = RoomTrackRecordingStore(db, crypto(), catalog)
        val useCase = engine(store, RecordingInputSource.ANDROID_GPS)
        useCase.start("trip", sample(0).timeMs, "session")
        store.setInputSource("trip", RecordingInputSource.ANDROID_GPS)
        useCase.process("trip", "session", 0, sample(0, accuracy = 51.0))
        assertTrue(store.landmarkVisits().isEmpty())
        useCase.process("trip", "session", 1, sample(1))
        assertEquals(2, store.landmarkVisits().size)
        useCase.process("trip", "session", 2, sample(2))
        assertEquals(2, store.landmarkVisits().size)
        useCase.finish("trip", sample(3).timeMs)
        assertEquals(TripStatus.DISCARDED, store.load("trip")?.status)
        assertTrue(store.landmarkVisits().all { it.tripId == null })
        db.close()
    }

    @Test fun landmarkSpeechIsDurableSourceScopedAndDoesNotAcknowledgeTheReward() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("landmark-speech.db")
        val cipher = crypto()
        var db = Room.databaseBuilder(context, RecordingDatabase::class.java, "landmark-speech.db").build()
        var store = RoomTrackRecordingStore(db, cipher, catalog)
        record(store, "demo", RecordingInputSource.REPLAY)
        assertNull(store.nextLandmarkForSpeech("demo"))
        assertFalse(store.claimLandmarkSpeech(store.landmarkVisits().first().key))
        record(store, "gps", RecordingInputSource.ANDROID_GPS, 100_000)
        assertNull(store.nextLandmarkForSpeech("other-trip"))
        val visit = requireNotNull(store.nextLandmarkForSpeech("gps"))
        assertTrue(store.claimLandmarkSpeech(visit.key))
        assertFalse(store.landmarkVisits().first { it.key == visit.key }.acknowledged)
        db.close()
        db = Room.databaseBuilder(context, RecordingDatabase::class.java, "landmark-speech.db").build()
        store = RoomTrackRecordingStore(db, cipher, catalog)
        assertFalse(store.claimLandmarkSpeech(visit.key))
        val other = requireNotNull(store.nextLandmarkForSpeech("gps"))
        assertNotEquals(visit.key, other.key)
        store.acknowledgeLandmark(other.key)
        assertFalse(store.claimLandmarkSpeech(other.key))
        assertNull(store.nextLandmarkForSpeech("gps"))
        store.deleteAll()
        record(store, "new-gps", RecordingInputSource.ANDROID_GPS, 200_000)
        assertTrue(store.claimLandmarkSpeech(requireNotNull(store.nextLandmarkForSpeech("new-gps")).key))
        db.close()
    }

    @Test fun secondPassUsesExistingGhostEngineAndFinalizesComparison() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RecordingDatabase::class.java).build()
        val store = RoomTrackRecordingStore(db, crypto(), catalog)
        record(store, "first", RecordingInputSource.REPLAY)
        record(store, "second", RecordingInputSource.REPLAY, 100_000)
        val results = store.ghostResults(listOf("second"))
        assertEquals(1, results.size)
        assertTrue(results.single().finalized)
        assertTrue(results.single().matchedDistanceM > 500)
        assertEquals("first", results.single().pastTripId)
        assertEquals(0.0, results.single().newRoadM, .001)
        assertEquals(2, store.landmarkVisits().size)
        store.deleteTrip("first")
        assertNull(store.ghostResults(listOf("second")).single().pastTripId)
        db.close()
    }
}
