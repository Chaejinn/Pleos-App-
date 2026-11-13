package com.pleos.explorer.data.local.recording

import androidx.room.Room
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
class RoomTrackRecordingStoreTest {
    private fun cipher() = RecordingCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
    private fun engine(store: RoomTrackRecordingStore): RecordTrackUseCase {
        return RecordTrackUseCase(store, TrackRecorder(IdGenerator { java.util.UUID.randomUUID().toString() }))
    }
    private fun location(i: Int) = LocationSample(1_700_000_000_000 + i * 1000L, i * 1000L,
        GeoPoint(36.0 + i * 0.0001, 127.0), 11.0, 0.0, 5.0)

    @Test fun encryptedTripSurvivesDatabaseReopenAndResumeWithoutConnectingGap() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("recording-test.db")
        val crypto = cipher()
        var db = Room.databaseBuilder(context, RecordingDatabase::class.java, "recording-test.db").build()
        var store = RoomTrackRecordingStore(db, crypto)
        val useCase = engine(store)
        useCase.start("trip", location(0).timeMs, "first")
        (0..25).forEach { useCase.process("trip", "first", it.toLong(), location(it)) }
        val before = requireNotNull(store.load("trip"))
        assertTrue(before.trip.distanceM > 200)
        db.close()
        db = Room.databaseBuilder(context, RecordingDatabase::class.java, "recording-test.db").build()
        store = RoomTrackRecordingStore(db, crypto)
        val resumed = engine(store)
        resumed.resume("trip", "second")
        val after = resumed.process("trip", "second", 0, location(100))
        assertEquals(before.trip.distanceM, after.snapshot.trip.distanceM, 0.001)
        val finished = resumed.finish("trip", location(101).timeMs)
        assertEquals(TripStatus.FINISHED, finished.snapshot.status)
        assertEquals(2, finished.snapshot.trip.simplifiedPath.size)
        assertTrue(store.findRoads(before.pendingRoads.keys).isNotEmpty())
        db.close()
    }

    @Test fun revisionConflictLeavesOriginalSnapshotAndPointsUnchanged() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RecordingDatabase::class.java).build()
        val store = RoomTrackRecordingStore(db, cipher())
        val useCase = engine(store)
        useCase.start("trip", location(0).timeMs, "session")
        useCase.process("trip", "session", 0, location(0))
        val before = requireNotNull(store.load("trip"))
        val points = store.loadPoints("trip")
        try {
            store.commit(0, TrackUpdate(before.copy(revision = 2)))
            fail("Stale revision accepted")
        } catch (_: IllegalStateException) { }
        assertEquals(before.revision, store.load("trip")?.revision)
        assertEquals(points, store.loadPoints("trip"))
        db.close()
    }

    @Test fun shortTripDiscardsPointsAndDoesNotPublishRoads() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RecordingDatabase::class.java).build()
        val store = RoomTrackRecordingStore(db, cipher())
        val useCase = engine(store)
        useCase.start("trip", location(0).timeMs, "session")
        useCase.process("trip", "session", 0, location(0))
        val keys = requireNotNull(store.load("trip")).pendingRoads.keys
        useCase.finish("trip", location(1).timeMs)
        assertTrue(store.loadPoints("trip").isEmpty())
        assertTrue(store.findRoads(keys).isEmpty())
        assertEquals(TripStatus.DISCARDED, store.load("trip")?.status)
        store.deleteAll()
        assertNull(store.load("trip"))
        db.close()
    }

    @Test fun databaseWriteFailureRollsBackPointsBeforeSnapshotAndAllowsRetry() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RecordingDatabase::class.java)
            .allowMainThreadQueries().build()
        val store = RoomTrackRecordingStore(db, cipher())
        val useCase = engine(store)
        useCase.start("trip", location(0).timeMs, "session")
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_snapshot BEFORE INSERT ON recording_entries
            WHEN NEW.id = 'snapshot:trip' BEGIN SELECT RAISE(ABORT, 'disk write rejected'); END
        """.trimIndent())
        try {
            useCase.process("trip", "session", 0, location(0))
            fail("Failed transaction accepted")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(0L, store.load("trip")?.revision)
        assertTrue(store.loadPoints("trip").isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_snapshot")
        useCase.process("trip", "session", 0, location(0))
        assertEquals(1L, store.load("trip")?.revision)
        assertEquals(1, store.loadPoints("trip").size)
        db.close()
    }

    @Test fun ciphertextHidesCoordinatesAndRejectsTamperingOrMovingRows() {
        val crypto = cipher()
        val plaintext = "{latitude:36.123456,longitude:127.123456}".toByteArray()
        val encrypted = crypto.encrypt("snapshot:one", plaintext)
        assertFalse(String(encrypted).contains("36.123456"))
        assertArrayEquals(plaintext, crypto.decrypt("snapshot:one", encrypted))
        assertFalse(encrypted.contentEquals(crypto.encrypt("snapshot:one", plaintext)))
        try { crypto.decrypt("snapshot:two", encrypted); fail("Row substitution accepted") }
        catch (_: java.security.GeneralSecurityException) { }
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        try { crypto.decrypt("snapshot:one", encrypted); fail("Tampered payload accepted") }
        catch (_: java.security.GeneralSecurityException) { }
    }
}
