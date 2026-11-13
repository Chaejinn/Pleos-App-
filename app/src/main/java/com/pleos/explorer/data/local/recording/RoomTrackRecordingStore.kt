package com.pleos.explorer.data.local.recording

import android.content.Context
import androidx.room.Room
import com.google.gson.Gson
import com.pleos.explorer.domain.model.*
import com.pleos.explorer.domain.port.TrackRecordingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Callable

/** F-03 adapter. Snapshot, raw points and confirmed roads commit together. */
class RoomTrackRecordingStore(
    private val database: RecordingDatabase,
    private val cipher: RecordingCipher,
) : TrackRecordingStore {
    private val gson = Gson()
    private val dao get() = database.entries()

    override suspend fun load(tripId: String): RecordingSnapshot? = withContext(Dispatchers.IO) {
        snapshot(tripId)
    }

    override suspend fun loadPoints(tripId: String): List<TrackPoint> = withContext(Dispatchers.IO) {
        points(tripId)
    }

    override suspend fun findRoads(gridKeys: Set<String>): Map<String, ExploredRoad> = withContext(Dispatchers.IO) {
        // Version 1 keeps the road index encrypted as one value; no plaintext grid keys.
        read("roads", Array<ExploredRoad>::class.java).orEmpty().filter { it.gridKey in gridKeys }
            .associateBy { it.gridKey }
    }

    override suspend fun commit(expectedRevision: Long?, update: TrackUpdate): Unit = withContext(Dispatchers.IO) {
        database.runInTransaction(Callable {
            val next = update.snapshot
            val old = snapshot(next.trip.id)
            check(old?.revision == expectedRevision) { "Recording revision conflict" }
            check(next.revision == (expectedRevision?.plus(1) ?: 0L)) { "Invalid next revision" }
            val allPoints = if (next.status == TripStatus.DISCARDED) emptyList() else points(next.trip.id) + update.points
            check(allPoints.all { it.tripId == next.trip.id }) { "Point belongs to another trip" }
            check(allPoints.map { it.id }.distinct().size == allPoints.size) { "Duplicate point ID" }
            check(allPoints.zipWithNext().all { (a, b) -> a.sequence < b.sequence }) { "Point sequence conflict" }
            val roads = read("roads", Array<ExploredRoad>::class.java).orEmpty().associateBy { it.gridKey } + update.roads
            write("points:${next.trip.id}", allPoints)
            if (update.roads.isNotEmpty()) write("roads", roads.values.toList())
            write("snapshot:${next.trip.id}", next)
            Unit
        })
    }

    suspend fun deleteAll(): Unit = withContext(Dispatchers.IO) {
        database.runInTransaction { dao.clear() }
    }

    private fun snapshot(id: String) = read("snapshot:$id", RecordingSnapshot::class.java)?.also {
        check(it.trip.id == id) { "Snapshot identity mismatch" }
    }
    private fun points(id: String) = read("points:$id", Array<TrackPoint>::class.java)?.toList().orEmpty()
    private fun write(id: String, value: Any) {
        dao.put(RecordingEntry(id, cipher.encrypt(id, gson.toJson(value).toByteArray(Charsets.UTF_8))))
    }
    private fun <T> read(id: String, type: Class<T>): T? = dao.get(id)?.let {
        gson.fromJson(String(cipher.decrypt(id, it.payload), Charsets.UTF_8), type)
            ?: error("Empty recording payload")
    }

    companion object {
        fun create(context: Context): RoomTrackRecordingStore = RoomTrackRecordingStore(
            Room.databaseBuilder(context.applicationContext, RecordingDatabase::class.java, "recording-v1.db")
                .build(), RecordingCipher.device()
        )
    }
}
