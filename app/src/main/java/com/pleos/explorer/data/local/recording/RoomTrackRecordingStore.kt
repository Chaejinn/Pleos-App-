package com.pleos.explorer.data.local.recording

import android.content.Context
import androidx.room.Room
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.pleos.explorer.domain.engine.LocationValidator
import com.pleos.explorer.domain.model.*
import com.pleos.explorer.domain.port.TrackRecordingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Callable

/** F-03 adapter. Snapshot, raw points and confirmed roads commit together. */
class RoomTrackRecordingStore(
    private val database: RecordingDatabase,
    private val cipher: RecordingCipher,
    landmarks: List<com.pleos.explorer.domain.model.legacy.Landmark> = emptyList(),
) : TrackRecordingStore {
    private val landmarkCollector = com.pleos.explorer.domain.engine.LandmarkCollector(landmarks)
    private val ghostDetector = com.pleos.explorer.domain.engine.GhostDetector(
        com.pleos.explorer.domain.port.IdGenerator { java.util.UUID.randomUUID().toString() })
    private val ghostAssembler = com.pleos.explorer.domain.engine.GhostResultAssembler()
    private val titleEvaluator = com.pleos.explorer.domain.engine.TitleEvaluator()
    private val historicalCache = mutableMapOf<String, List<HistoricalTrack>>()
    private val gson = GsonBuilder().registerTypeAdapter(ValidatedLocation::class.java,
        JsonDeserializer<ValidatedLocation> { json, _, context ->
            val sample = context.deserialize<LocationSample>(json, LocationSample::class.java)
            val result = LocationValidator().validate(sample, null)
            check(result is LocationValidation.Accepted) { "Invalid stored location" }
            result.location
        }).create()
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

    suspend fun findRoadsForSource(gridKeys: Set<String>, source: RecordingInputSource): Map<String, ExploredRoad> =
        withContext(Dispatchers.IO) { read(roadKey(source.name), Array<ExploredRoad>::class.java).orEmpty()
            .filter { it.gridKey in gridKeys }.associateBy { it.gridKey } }

    private fun roadKey(source: String?) = if (source == null || source == RecordingInputSource.UNKNOWN.name)
        "roads" else "roads:$source"

    override suspend fun commit(expectedRevision: Long?, update: TrackUpdate): Unit = withContext(Dispatchers.IO) {
        database.runInTransaction(Callable {
            val proposed = update.snapshot
            val next = if (proposed.status == TripStatus.DISCARDED) RecordingSnapshot(
                trip = Trip(proposed.trip.id, proposed.trip.startedAtMs, proposed.trip.endedAtMs),
                sessionId = proposed.sessionId, revision = proposed.revision,
                lastInputSequence = proposed.lastInputSequence, status = TripStatus.DISCARDED,
            ) else proposed
            val old = snapshot(next.trip.id)
            check(old?.revision == expectedRevision) { "Recording revision conflict" }
            check(next.revision == (expectedRevision?.plus(1) ?: 0L)) { "Invalid next revision" }
            val allPoints = if (next.status == TripStatus.DISCARDED) emptyList() else points(next.trip.id) + update.points
            check(allPoints.all { it.tripId == next.trip.id }) { "Point belongs to another trip" }
            check(allPoints.map { it.id }.distinct().size == allPoints.size) { "Duplicate point ID" }
            check(allPoints.zipWithNext().all { (a, b) -> a.sequence < b.sequence }) { "Point sequence conflict" }
            val source = read("source:${next.trip.id}", String::class.java)
            val roadsKey = roadKey(source)
            val roads = read(roadsKey, Array<ExploredRoad>::class.java).orEmpty().associateBy { it.gridKey } + update.roads
            write("points:${next.trip.id}", allPoints)
            if (update.roads.isNotEmpty()) write(roadsKey, roads.values.toList())
            write("snapshot:${next.trip.id}", next)
            val location = (update.validation as? LocationValidation.Accepted)?.location
            if (location != null) {
                if (source == RecordingInputSource.ANDROID_GPS.name || source == RecordingInputSource.REPLAY.name) {
                    val visits = visits()
                    val existing = visits.filter { it.inputSource == source }.map { it.landmarkId }.toSet()
                    val acquired = landmarkCollector.findNew(location, existing).map {
                        LandmarkVisit(it.id, location.timeMs, next.trip.id, source)
                    }
                    if (acquired.isNotEmpty()) write("landmark-visits", visits + acquired)
                }
            }
            if (next.status == TripStatus.DISCARDED) unlinkLandmarks(next.trip.id)
            if (source == RecordingInputSource.ANDROID_GPS.name || source == RecordingInputSource.REPLAY.name)
                updateExploration(old, update, source)
            if (next.status == TripStatus.DISCARDED) dao.delete("source:${next.trip.id}")
            if (next.status != TripStatus.ACTIVE) historicalCache.clear()
            Unit
        })
    }

    suspend fun deleteAll(): Unit = withContext(Dispatchers.IO) {
        database.runInTransaction { dao.clear(); historicalCache.clear() }
    }

    suspend fun landmarkVisits(): List<LandmarkVisit> = withContext(Dispatchers.IO) { visits() }

    suspend fun acknowledgeLandmark(key: String): Unit = withContext(Dispatchers.IO) {
        database.runInTransaction {
            write("landmark-visits", visits().map { if (it.key == key) it.copy(acknowledged = true) else it })
        }
    }

    private fun visits() = read("landmark-visits", Array<LandmarkVisit>::class.java)?.toList().orEmpty()

    suspend fun collectedTitles(): List<CollectedTitle> = withContext(Dispatchers.IO) { titles() }
    private fun titles() = read("collected-titles", Array<CollectedTitle>::class.java)?.toList().orEmpty()

    suspend fun acknowledgeTitle(key: String): Unit = withContext(Dispatchers.IO) {
        database.runInTransaction { write("collected-titles", titles().map {
            if (it.key == key) it.copy(acknowledged = true) else it
        }) }
    }

    suspend fun ghostResults(ids: List<String>): List<GhostResult> = withContext(Dispatchers.IO) {
        ids.flatMap { read("ghost-results:$it", Array<GhostResult>::class.java)?.toList().orEmpty() }
    }

    /** F03, F12, F13 and title results share this Room transaction and source namespace. */
    private fun updateExploration(old: RecordingSnapshot?, update: TrackUpdate, source: String) {
        val next = update.snapshot
        val id = next.trip.id
        if (old == null) return
        if (next.status == TripStatus.DISCARDED) {
            listOf("ghost:$id", "comparison:$id", "ghost-results:$id").forEach { dao.delete(it) }
            return
        }
        val history = historicalCache.getOrPut(source) {
            dao.snapshots().map { requireNotNull(read(it.id, RecordingSnapshot::class.java)) }
                .filter { it.status == TripStatus.FINISHED && read("source:${it.trip.id}", String::class.java) == source }
                .map { HistoricalTrack(it.trip.id, points(it.trip.id)) }
        }.filter { it.tripId != id }
        val beforeGhost = read("ghost:$id", GhostSnapshot::class.java)
            ?: GhostSnapshot(id, old.sessionId, revision = old.revision, lastInputSequence = old.lastInputSequence)
        val beforeComparison = read("comparison:$id", GhostComparisonSnapshot::class.java)
            ?: GhostComparisonSnapshot(id, old.sessionId, recordingRevision = old.revision,
                ghostRevision = beforeGhost.revision,
                newRoadM = old.pendingRoads.filterKeys { it in old.newGridKeys }.values.sumOf { it.firstExploredDistanceM })
        val accepted = (update.validation as? LocationValidation.Accepted)?.location
        val ghost = if (old.sessionId != next.sessionId) ghostDetector.resume(beforeGhost, next.sessionId) else {
            val input = when {
                next.status == TripStatus.FINISHED -> GhostInput.TripEnded
                accepted != null -> GhostInput.Location(accepted, update.connectedDistanceM, update.newSegment)
                else -> GhostInput.ContinuityLost
            }
            // Cached historical tracks include adjacent points, so forward rejoin matching is not truncated.
            val sequence = if (next.status == TripStatus.ACTIVE) next.lastInputSequence else beforeGhost.lastInputSequence + 1
            ghostDetector.process(beforeGhost, input, sequence, history)
        }
        val comparison = ghostAssembler.process(beforeComparison, GhostComparisonInput(update, ghost), history, next.sessionId)
        val results = read("ghost-results:$id", Array<GhostResult>::class.java).orEmpty().associateBy { it.id }.toMutableMap()
        comparison.results.forEach { results[it.id] = it }
        write("ghost:$id", ghost.snapshot)
        write("comparison:$id", comparison.snapshot)
        if (results.isNotEmpty()) write("ghost-results:$id", results.values.toList())
        if (next.status == TripStatus.FINISHED) {
            val newRoadM = read(roadKey(source), Array<ExploredRoad>::class.java).orEmpty().sumOf { it.firstExploredDistanceM }
            var collected = titles()
            val facts = listOfNotNull(AchievementFact.TripFinished.from(update)) + results.values.filter { it.finalized }.map {
                AchievementFact.GhostFinished(it, requireNotNull(next.trip.endedAtMs))
            }
            facts.forEach { fact ->
                val progress = AchievementProgress(acquiredTitleIds = collected.filter { it.inputSource == source }
                    .map { it.title.titleId }.toSet(), newRoadDistanceM = newRoadM)
                val qualified = titleEvaluator.evaluate(fact, progress)
                collected = collected + qualified.map {
                    CollectedTitle(AchievementTitle(it.titleId, it.conditionText, it.acquiredAtMs, it.tripId), source)
                }
            }
            if (collected.isNotEmpty()) write("collected-titles", collected)
        }
    }

    private fun unlinkLandmarks(tripId: String) {
        val values = visits()
        if (values.any { it.tripId == tripId }) write("landmark-visits", values.map {
            if (it.tripId == tripId) it.copy(tripId = null) else it
        })
    }

    suspend fun listSnapshots(): List<RecordingSnapshot> = withContext(Dispatchers.IO) {
        dao.snapshots().map { requireNotNull(read(it.id, RecordingSnapshot::class.java)) }
            .filter { it.status != TripStatus.DISCARDED }.sortedByDescending { it.trip.startedAtMs }
    }

    suspend fun setInputSource(id: String, source: RecordingInputSource): Unit = withContext(Dispatchers.IO) {
        database.runInTransaction(Callable {
            check(snapshot(id)?.status == TripStatus.ACTIVE) { "Recording is not active" }
            write("source:$id", source.name)
            Unit
        })
    }

    suspend fun inputSources(ids: List<String>): Map<String, RecordingInputSource> = withContext(Dispatchers.IO) {
        ids.associateWith { id ->
            val value = read("source:$id", String::class.java)
            RecordingInputSource.entries.firstOrNull { it.name == value } ?: RecordingInputSource.UNKNOWN
        }
    }

    suspend fun deleteTrip(id: String): Unit = withContext(Dispatchers.IO) {
        database.runInTransaction(Callable {
            check(snapshot(id)?.status != TripStatus.ACTIVE) { "Cannot delete active recording" }
            listOf("roads", "roads:ANDROID_GPS", "roads:REPLAY").forEach { key ->
                val roads = read(key, Array<ExploredRoad>::class.java).orEmpty().map {
                    if (it.firstTripId == id) it.copy(firstTripId = null) else it
                }
                if (roads.isNotEmpty()) write(key, roads)
            }
            unlinkLandmarks(id)
            write("collected-titles", titles().map {
                if (it.title.tripId == id) it.copy(title = it.title.copy(tripId = null)) else it
            })
            // Remove references to the deleted trip from other stored ghost comparisons.
            dao.snapshots().forEach { row ->
                val owner = requireNotNull(read(row.id, RecordingSnapshot::class.java)).trip.id
                val results = read("ghost-results:$owner", Array<GhostResult>::class.java).orEmpty()
                if (results.isNotEmpty()) write("ghost-results:$owner", results.map { result -> result.copy(
                    pastTripId = result.pastTripId.takeUnless { it == id },
                    passages = result.passages.map { passage -> passage.copy(visits = passage.visits.map { visit ->
                        visit.copy(pastTripId = visit.pastTripId.takeUnless { it == id })
                    }) }) })
            }
            listOf("ghost:$id", "comparison:$id", "ghost-results:$id").forEach { dao.delete(it) }
            historicalCache.clear()
            dao.delete("snapshot:$id")
            dao.delete("points:$id")
            dao.delete("source:$id")
            Unit
        })
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
                .build(), RecordingCipher.device(), com.pleos.explorer.data.catalog.LandmarkLoader.load(context)
        )
    }
}
