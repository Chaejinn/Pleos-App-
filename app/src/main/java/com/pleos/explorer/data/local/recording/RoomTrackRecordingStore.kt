package com.pleos.explorer.data.local.recording

import android.content.Context
import androidx.room.Room
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.pleos.explorer.domain.engine.LocationValidator
import com.pleos.explorer.domain.model.*
import com.pleos.explorer.domain.port.TrackRecordingStore
import androidx.room.withTransaction

import java.util.concurrent.Callable

/** F-03 adapter. Snapshot, raw points and confirmed roads commit together. */
class RoomTrackRecordingStore(
    private val database: RecordingDatabase,
    private val cipher: RecordingCipher,
    landmarks: List<com.pleos.explorer.domain.model.legacy.Landmark> = emptyList(),
) : TrackRecordingStore {
    private val landmarkCollector = com.pleos.explorer.domain.engine.LandmarkCollector(landmarks)
    private val replayHistoricalCache = mutableMapOf<String,List<HistoricalTrack>>()
    private val replayGhostDetector = com.pleos.explorer.domain.engine.GhostDetector(com.pleos.explorer.domain.port.IdGenerator { java.util.UUID.randomUUID().toString() })
    private val replayGhostAssembler = com.pleos.explorer.domain.engine.GhostResultAssembler()
    private val replayTitleEvaluator = com.pleos.explorer.domain.engine.TitleEvaluator()
    private val gson = GsonBuilder().registerTypeAdapter(ValidatedLocation::class.java,
        JsonDeserializer<ValidatedLocation> { json, _, context ->
            val sample = context.deserialize<LocationSample>(json, LocationSample::class.java)
            val result = LocationValidator().validate(sample, null)
            check(result is LocationValidation.Accepted) { "Invalid stored location" }
            result.location
        }).create()
    private val dao get() = database.entries()
    private fun roadKey(source: String?) = when (source) {
        RecordingInputSource.ANDROID_GPS.name -> "gps:roads"
        RecordingInputSource.REPLAY.name -> "roads:REPLAY"
        else -> "roads"
    }
    suspend fun findRoadsForSource(gridKeys: Set<String>,source: RecordingInputSource): Map<String,ExploredRoad> = database.withTransaction {
        read(roadKey(source.name),Array<ExploredRoad>::class.java).orEmpty().filter { it.gridKey in gridKeys }.associateBy { it.gridKey }
    }
    private fun visits() = read("landmark-visits",Array<LandmarkVisit>::class.java).orEmpty().toList()
    suspend fun landmarkVisits(): List<LandmarkVisit> = database.withTransaction { visits() }
    suspend fun acknowledgeLandmark(key: String): Unit = database.withTransaction {
        check(visits().any { it.key == key })
        write("landmark-visits",visits().map { if(it.key==key) it.copy(acknowledged=true) else it })
    }
    private fun replayTitles() = read("collected-titles",Array<CollectedTitle>::class.java).orEmpty().toList()
    suspend fun collectedTitles(): List<CollectedTitle> = database.withTransaction {
        val rewards=read("region:rewards",Array<PendingReward>::class.java).orEmpty()
        replayTitles() + read("title:items",Array<AchievementTitle>::class.java).orEmpty().map { title ->
            CollectedTitle(title,RecordingInputSource.ANDROID_GPS.name,
                rewards.firstOrNull { it.kind==RewardKind.TITLE && it.subjectId==title.titleId }?.acknowledgedAtMs != null)
        }
    }
    suspend fun acknowledgeTitle(key: String): Unit = database.withTransaction {
        write("collected-titles",replayTitles().map { if(it.key==key) it.copy(acknowledged=true) else it })
    }
    suspend fun ghostResults(ids: List<String>): List<GhostResult> = database.withTransaction {
        read("ghost:results",Array<GhostResult>::class.java).orEmpty().filter { it.tripId in ids } +
            ids.flatMap { read("replay:ghost-results:$it",Array<GhostResult>::class.java).orEmpty().toList() }
    }
    suspend fun <T> atomic(action: suspend () -> T): T = database.withTransaction { action() }
    suspend fun lifecycle(sessionId: String): TripLifecycleSnapshot? = database.withTransaction {
        read("lifecycle:$sessionId", TripLifecycleSnapshot::class.java)
    }
    suspend fun commitLifecycle(expectedRevision: Long?, next: TripLifecycleSnapshot): Unit = database.withTransaction {
        val old = read("lifecycle:${next.sessionId}", TripLifecycleSnapshot::class.java)
        check(old?.revision == expectedRevision)
        check(next.revision == (expectedRevision?.plus(1) ?: 1L))
        write("lifecycle:${next.sessionId}", next)
    }
    suspend fun clearLifecycleTrip(tripId: String): Unit = database.withTransaction {
        clearLifecycleRows(tripId)
    }
    private fun clearLifecycleRows(tripId: String) {
        dao.lifecycles().forEach { row ->
            if (read(row.id,TripLifecycleSnapshot::class.java)?.tripId == tripId) dao.delete(row.id)
        }
    }

    val ghosts = object : com.pleos.explorer.domain.port.GhostStore {
        override suspend fun load(tripId: String): GhostSnapshot? = database.withTransaction {
            read("ghost:state:$tripId", GhostSnapshot::class.java)
        }
        override suspend fun commit(expectedRevision: Long?, update: GhostUpdate): Unit = database.withTransaction {
            database.runInTransaction(Callable {
                val state = update.snapshot
                val old = read("ghost:state:${state.tripId}", GhostSnapshot::class.java)
                check(old?.revision == expectedRevision && state.revision == (expectedRevision?.plus(1) ?: 0L))
                val segments = read("ghost:segments:${state.tripId}", Array<GhostSegment>::class.java).orEmpty().toList()
                val added = update.events.filterIsInstance<GhostEvent.Finished>().map { it.segment }
                check(added.all { it.tripId == state.tripId } && (segments + added).map { it.id }.distinct().size == segments.size + added.size)
                write("ghost:segments:${state.tripId}", segments + added)
                write("ghost:state:${state.tripId}", state)
                Unit
            })
        }
    }
    val ghostComparisons = object : com.pleos.explorer.domain.port.GhostComparisonStore {
        override suspend fun load(tripId: String): GhostComparisonSnapshot? = database.withTransaction {
            read("ghost:comparison:$tripId", GhostComparisonSnapshot::class.java)
        }
        override suspend fun commit(expectedRevision: Long?, update: GhostComparisonUpdate): Unit = database.withTransaction {
            database.runInTransaction(Callable {
                val state = update.snapshot
                val old = read("ghost:comparison:${state.tripId}", GhostComparisonSnapshot::class.java)
                check(old?.revision == expectedRevision && state.revision == (expectedRevision?.plus(1) ?: 0L))
                val results = read("ghost:results", Array<GhostResult>::class.java).orEmpty().associateBy { it.id }.toMutableMap()
                check(update.results.map { it.id }.distinct().size == update.results.size)
                update.results.forEach { result ->
                    check(result.tripId == state.tripId && result.pastTripId != result.tripId)
                    val previous = results[result.id]
                    check(previous == null || (previous.tripId == result.tripId && (!previous.finalized || previous == result)))
                    results[result.id] = result
                }
                write("ghost:results", results.values.toList())
                appendRewards(update.rewards)
                write("ghost:comparison:${state.tripId}", state)
                update.results.filter { it.finalized }.forEach { result ->
                    applyTitleFact(AchievementFact.GhostFinished(result, result.passages.lastOrNull()?.currentPassedAtMs ?: result.previousLastPassedAtMs))
                }
                Unit
            })
        }
    }
    suspend fun ghostResults(): List<GhostResult> = database.withTransaction {
        read("ghost:results", Array<GhostResult>::class.java).orEmpty().toList()
    }
    suspend fun gpsHistory(): List<HistoricalTrack> = database.withTransaction {
        val cutoff = System.currentTimeMillis() - 180L * 24 * 60 * 60 * 1000
        dao.snapshots().mapNotNull { row ->
            val state = requireNotNull(read(row.id, RecordingSnapshot::class.java))
            if (state.status != TripStatus.FINISHED || state.trip.startedAtMs < cutoff ||
                read("source:${state.trip.id}", String::class.java) != RecordingInputSource.ANDROID_GPS.name) null
            else HistoricalTrack(state.trip.id, points(state.trip.id))
        }
    }

    private fun titleSnapshot(): TitleSnapshot {
        val stored = read("title:state", TitleSnapshot::class.java) ?: TitleSnapshot()
        val roads = read("gps:roads", Array<ExploredRoad>::class.java).orEmpty()
        return stored.copy(progress = stored.progress.copy(
            regionBadgeCount = badges().count { it.type == BadgeType.REGION },
            newRoadDistanceM = roads.sumOf { it.firstExploredDistanceM }))
    }
    val titleStore = object : com.pleos.explorer.domain.port.TitleStore {
        override suspend fun load(): TitleSnapshot = database.withTransaction { titleSnapshot() }
        override suspend fun commit(expectedRevision: Long, update: TitleUpdate): Unit = database.withTransaction {
            database.runInTransaction(Callable { commitTitles(expectedRevision, update); Unit })
        }
    }
    private fun commitTitles(expectedRevision: Long, update: TitleUpdate) {
        val current = titleSnapshot()
        check(current.revision == expectedRevision && update.snapshot.revision == expectedRevision + 1)
        check(update.snapshot.progress.regionBadgeCount == current.progress.regionBadgeCount &&
            update.snapshot.progress.newRoadDistanceM == current.progress.newRoadDistanceM)
        val existing = read("title:items", Array<AchievementTitle>::class.java).orEmpty().toList()
        check(update.titles.map { it.titleId }.distinct().size == update.titles.size)
        check(update.titles.none { title -> existing.any { it.titleId == title.titleId } })
        check(update.snapshot.progress.acquiredTitleIds == current.progress.acquiredTitleIds + update.titles.map { it.titleId })
        write("title:items", existing + update.titles)
        appendRewards(update.rewards)
        write("title:state", update.snapshot)
    }
    private fun applyTitleFact(fact: AchievementFact) {
        val state = titleSnapshot()
        val qualified = com.pleos.explorer.domain.engine.TitleEvaluator().evaluate(fact, state.progress)
        if (qualified.isEmpty()) return
        val titles = qualified.map { AchievementTitle(it.titleId, it.conditionText, it.acquiredAtMs, it.tripId) }
        val rewards = titles.map { PendingReward(java.util.UUID.randomUUID().toString(), "TITLE:${it.titleId}",
            it.tripId, RewardKind.TITLE, it.titleId, it.acquiredAtMs) }
        commitTitles(state.revision, TitleUpdate(state.copy(revision = state.revision + 1,
            progress = state.progress.copy(acquiredTitleIds = state.progress.acquiredTitleIds + titles.map { it.titleId })), titles, rewards))
    }
    private fun appendRewards(added: List<PendingReward>) {
        val old = read("region:rewards", Array<PendingReward>::class.java).orEmpty().toList()
        val next = old + added.filter { reward -> old.none { it.deduplicationKey == reward.deduplicationKey } }
        check(next.map { it.id }.distinct().size == next.size)
        check(next.map { it.deduplicationKey }.distinct().size == next.size)
        write("region:rewards", next)
    }
    suspend fun recordGpsTripAchievement(update: TrackUpdate): Unit = database.withTransaction {
        val fact = AchievementFact.TripFinished.from(update) ?: return@withTransaction
        database.runInTransaction(Callable {
            check(read("source:${fact.tripId}", String::class.java) == RecordingInputSource.ANDROID_GPS.name)
            applyTitleFact(fact)
            Unit
        })
    }
    suspend fun achievementTitles(): List<AchievementTitle> = database.withTransaction {
        read("title:items", Array<AchievementTitle>::class.java).orEmpty().toList()
    }

    val regionVisits = object : com.pleos.explorer.domain.port.RegionVisitStore {
        override suspend fun load(): RegionVisitSnapshot? = database.withTransaction {
            read("region:state", RegionVisitSnapshot::class.java)
        }
        override suspend fun findBadges(regionCodes: Set<String>): Map<String, RegionBadge> =
            database.withTransaction { badges().filter { it.regionCode in regionCodes }.associateBy { it.badgeId } }
        override suspend fun commit(expectedRevision: Long?, update: RegionVisitUpdate): Unit = database.withTransaction {
            database.runInTransaction(Callable {
                val old = read("region:state", RegionVisitSnapshot::class.java)
                check(old?.revision == expectedRevision) { "Region revision conflict" }
                check(update.snapshot.revision == (expectedRevision?.plus(1) ?: 0L)) { "Invalid region revision" }
                check(update.badges.all { (id, badge) -> id == badge.badgeId && badge.visitCount > 0 })
                val badges = (badges().associateBy { it.badgeId } + update.badges).values.toList()
                val visits = read("region:visits", Array<TripRegionVisit>::class.java).orEmpty().toList() + update.visits
                check(visits.map { it.id }.distinct().size == visits.size) { "Duplicate region visit" }
                val rewards = read("region:rewards", Array<PendingReward>::class.java).orEmpty().toList()
                val keys = rewards.map { it.deduplicationKey }.toSet()
                val added = update.rewards.filter { it.deduplicationKey !in keys }
                check(added.map { it.deduplicationKey }.distinct().size == added.size)
                write("region:badges", badges)
                write("region:visits", visits)
                write("region:rewards", rewards + added)
                write("region:state", update.snapshot)
                update.rewards.filter { it.kind == RewardKind.BADGE }.forEach {
                    applyTitleFact(AchievementFact.BadgeGranted(it.subjectId, it.tripId, it.occurredAtMs))
                }
                Unit
            })
        }
    }

    private fun badges() = read("region:badges", Array<RegionBadge>::class.java).orEmpty().toList()
    suspend fun regionBadges(): List<RegionBadge> = database.withTransaction { badges() }
    suspend fun pendingRegionRewards(): List<PendingReward> = database.withTransaction {
        read("region:rewards", Array<PendingReward>::class.java).orEmpty()
            .filter { it.acknowledgedAtMs == null }.sortedBy { it.occurredAtMs }
    }
    suspend fun acknowledgeRegionReward(id: String, timeMs: Long): Unit = database.withTransaction {
        require(timeMs >= 0)
        database.runInTransaction(Callable {
            val rewards = read("region:rewards", Array<PendingReward>::class.java).orEmpty()
            check(rewards.any { it.id == id }) { "Unknown reward" }
            write("region:rewards", rewards.map {
                if (it.id == id && it.acknowledgedAtMs == null) it.copy(acknowledgedAtMs = timeMs) else it
            })
            Unit
        })
    }
    suspend fun claimRewardSpeech(id: String): Boolean = database.withTransaction {
        val reward = read("region:rewards", Array<PendingReward>::class.java).orEmpty().firstOrNull { it.id == id }
        if (reward == null || reward.acknowledgedAtMs != null) return@withTransaction false
        val claims = read("speech:claims", Array<String>::class.java).orEmpty().toSet()
        if (id in claims) return@withTransaction false
        write("speech:claims", claims + id)
        true
    }
    suspend fun nextRewardForSpeech(tripId: String): PendingReward? = database.withTransaction {
        val claims = read("speech:claims",Array<String>::class.java).orEmpty().toSet()
        read("region:rewards",Array<PendingReward>::class.java).orEmpty()
            .filter { it.tripId == tripId && it.acknowledgedAtMs == null && it.id !in claims && it.kind != RewardKind.GHOST_RESULT }
            .minByOrNull { it.occurredAtMs }
    }

    override suspend fun load(tripId: String): RecordingSnapshot? = database.withTransaction {
        snapshot(tripId)
    }

    override suspend fun loadPoints(tripId: String): List<TrackPoint> = database.withTransaction {
        points(tripId)
    }

    override suspend fun findRoads(gridKeys: Set<String>): Map<String, ExploredRoad> = database.withTransaction {
        // Version 1 keeps the road index encrypted as one value; no plaintext grid keys.
        read("roads", Array<ExploredRoad>::class.java).orEmpty().filter { it.gridKey in gridKeys }
            .associateBy { it.gridKey }
    }

    val gpsTracks = object : TrackRecordingStore {
        override suspend fun load(tripId: String) = this@RoomTrackRecordingStore.load(tripId)
        override suspend fun loadPoints(tripId: String) = this@RoomTrackRecordingStore.loadPoints(tripId)
        override suspend fun findRoads(gridKeys: Set<String>): Map<String, ExploredRoad> = database.withTransaction {
            read("gps:roads", Array<ExploredRoad>::class.java).orEmpty().filter { it.gridKey in gridKeys }.associateBy { it.gridKey }
        }
        override suspend fun commit(expectedRevision: Long?, update: TrackUpdate) = commitRecording(expectedRevision, update, "gps:roads")
    }
    override suspend fun commit(expectedRevision: Long?, update: TrackUpdate): Unit = database.withTransaction {
        commitRecording(expectedRevision,update,roadKey(read("source:${update.snapshot.trip.id}",String::class.java)))
    }
    private suspend fun commitRecording(expectedRevision: Long?, update: TrackUpdate, roadIndex: String): Unit = database.withTransaction {
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
            val roads = read(roadIndex, Array<ExploredRoad>::class.java).orEmpty().associateBy { it.gridKey } + update.roads
            write("points:${next.trip.id}", allPoints)
            if (update.roads.isNotEmpty()) write(roadIndex, roads.values.toList())
            write("snapshot:${next.trip.id}", next)
            val source=read("source:${next.trip.id}",String::class.java)
            val location=(update.validation as? LocationValidation.Accepted)?.location
            if(location!=null && source in setOf(RecordingInputSource.ANDROID_GPS.name,RecordingInputSource.REPLAY.name)) {
                val existing=visits()
                val collected=existing.filter { it.inputSource==source }.map { it.landmarkId }.toSet()
                val added=landmarkCollector.findNew(location,collected).map { LandmarkVisit(it.id,location.timeMs,next.trip.id,requireNotNull(source)) }
                if(added.isNotEmpty()) write("landmark-visits",existing+added)
            }
            if(source==RecordingInputSource.REPLAY.name) updateReplayExploration(old,update,source)
            if(next.status!=TripStatus.ACTIVE) replayHistoricalCache.clear()
            if (next.status == TripStatus.DISCARDED) {
                dao.delete("source:${next.trip.id}")
                detachRegionTrip(next.trip.id, clearLifecycle = false)
            }
            Unit
        })
    }

    suspend fun deleteAll(): Unit = database.withTransaction {
        database.runInTransaction { dao.clear(); replayHistoricalCache.clear() }
    }

    suspend fun listSnapshots(): List<RecordingSnapshot> = database.withTransaction {
        dao.snapshots().map { requireNotNull(read(it.id, RecordingSnapshot::class.java)) }
            .filter { it.status != TripStatus.DISCARDED }.sortedByDescending { it.trip.startedAtMs }
    }

    suspend fun setInputSource(id: String, source: RecordingInputSource): Unit = database.withTransaction {
        database.runInTransaction(Callable {
            check(snapshot(id)?.status == TripStatus.ACTIVE) { "Recording is not active" }
            write("source:$id", source.name)
            Unit
        })
    }

    suspend fun inputSources(ids: List<String>): Map<String, RecordingInputSource> = database.withTransaction {
        ids.associateWith { id ->
            val value = read("source:$id", String::class.java)
            RecordingInputSource.entries.firstOrNull { it.name == value } ?: RecordingInputSource.UNKNOWN
        }
    }

    suspend fun deleteTrip(id: String): Unit = database.withTransaction {
        database.runInTransaction(Callable {
            check(snapshot(id)?.status != TripStatus.ACTIVE) { "Cannot delete active recording" }
            listOf("roads", "gps:roads", "roads:REPLAY").forEach { index ->
                val roads = read(index, Array<ExploredRoad>::class.java).orEmpty().map {
                    if (it.firstTripId == id) it.copy(firstTripId = null) else it
                }
                if (roads.isNotEmpty()) write(index, roads)
            }
            detachRegionTrip(id)
            dao.delete("snapshot:$id")
            dao.delete("points:$id")
            dao.delete("source:$id")
            Unit
        })
    }

    private fun detachRegionTrip(id: String, clearLifecycle: Boolean = true) {
        write("landmark-visits",visits().map { if(it.tripId==id) it.copy(tripId=null) else it })
        write("collected-titles",replayTitles().map { if(it.title.tripId==id) it.copy(title=it.title.copy(tripId=null)) else it })
        listOf("replay:ghost:$id","replay:comparison:$id","replay:ghost-results:$id").forEach { dao.delete(it) }
        dao.snapshots().forEach { row ->
            val owner=read(row.id,RecordingSnapshot::class.java)?.trip?.id ?: return@forEach
            val results=read("replay:ghost-results:$owner",Array<GhostResult>::class.java).orEmpty()
            if(results.isNotEmpty()) write("replay:ghost-results:$owner",results.map { result -> result.copy(
                pastTripId=result.pastTripId.takeUnless { it==id },passages=result.passages.map { passage -> passage.copy(
                    visits=passage.visits.map { it.copy(pastTripId=it.pastTripId.takeUnless { past -> past==id }) }) }) })
        }
        replayHistoricalCache.clear()
        if (clearLifecycle) clearLifecycleRows(id)
        dao.delete("ghost:state:$id")
        dao.delete("ghost:segments:$id")
        dao.delete("ghost:comparison:$id")
        // No active drive can be deleted. Drop closed detector checkpoints that may embed deleted raw points.
        dao.snapshots().forEach { row ->
            val other = read(row.id, RecordingSnapshot::class.java)
            if (other != null && other.status != TripStatus.ACTIVE) {
                dao.delete("replay:ghost:${other.trip.id}")
                dao.delete("replay:comparison:${other.trip.id}")
                dao.delete("ghost:state:${other.trip.id}")
                dao.delete("ghost:segments:${other.trip.id}")
                dao.delete("ghost:comparison:${other.trip.id}")
            }
        }
        write("ghost:results", read("ghost:results", Array<GhostResult>::class.java).orEmpty()
            .filter { it.tripId != id }.map { result ->
                result.copy(pastTripId = if (result.pastTripId == id) null else result.pastTripId,
                    passages = result.passages.map { passage -> passage.copy(visits = passage.visits.map { visit ->
                        if (visit.pastTripId == id) visit.copy(pastTripId = null) else visit
                    }) })
            })
        write("title:items", read("title:items", Array<AchievementTitle>::class.java).orEmpty()
            .map { if (it.tripId == id) it.copy(tripId = null) else it })
        write("region:badges", badges().map { if (it.firstTripId == id) it.copy(firstTripId = null) else it })
        write("region:visits", read("region:visits", Array<TripRegionVisit>::class.java).orEmpty().filter { it.tripId != id })
        write("region:rewards", read("region:rewards", Array<PendingReward>::class.java).orEmpty()
            .filterNot { it.tripId == id && it.kind == RewardKind.GHOST_RESULT }
            .map { if (it.tripId == id) it.copy(tripId = null) else it })
    }
    /** F03, F12, F13 and title results share this Room transaction and source namespace. */
    private fun updateReplayExploration(old: RecordingSnapshot?, update: TrackUpdate, source: String) {
        val next = update.snapshot
        val id = next.trip.id
        if (old == null) return
        if (next.status == TripStatus.DISCARDED) {
            listOf("replay:ghost:$id", "replay:comparison:$id", "replay:ghost-results:$id").forEach { dao.delete(it) }
            return
        }
        val history = replayHistoricalCache.getOrPut(source) {
            dao.snapshots().map { requireNotNull(read(it.id, RecordingSnapshot::class.java)) }
                .filter { it.status == TripStatus.FINISHED && read("source:${it.trip.id}", String::class.java) == source }
                .map { HistoricalTrack(it.trip.id, points(it.trip.id)) }
        }.filter { it.tripId != id }
        val beforeGhost = read("replay:ghost:$id", GhostSnapshot::class.java)
            ?: GhostSnapshot(id, old.sessionId, revision = old.revision, lastInputSequence = old.lastInputSequence)
        val beforeComparison = read("replay:comparison:$id", GhostComparisonSnapshot::class.java)
            ?: GhostComparisonSnapshot(id, old.sessionId, recordingRevision = old.revision,
                ghostRevision = beforeGhost.revision,
                newRoadM = old.pendingRoads.filterKeys { it in old.newGridKeys }.values.sumOf { it.firstExploredDistanceM })
        val accepted = (update.validation as? LocationValidation.Accepted)?.location
        val ghost = if (old.sessionId != next.sessionId) replayGhostDetector.resume(beforeGhost, next.sessionId) else {
            val input = when {
                next.status == TripStatus.FINISHED -> GhostInput.TripEnded
                accepted != null -> GhostInput.Location(accepted, update.connectedDistanceM, update.newSegment)
                else -> GhostInput.ContinuityLost
            }
            // Cached historical tracks include adjacent points, so forward rejoin matching is not truncated.
            val sequence = if (next.status == TripStatus.ACTIVE) next.lastInputSequence else beforeGhost.lastInputSequence + 1
            replayGhostDetector.process(beforeGhost, input, sequence, history)
        }
        val comparison = replayGhostAssembler.process(beforeComparison, GhostComparisonInput(update, ghost), history, next.sessionId)
        val results = read("replay:ghost-results:$id", Array<GhostResult>::class.java).orEmpty().associateBy { it.id }.toMutableMap()
        comparison.results.forEach { results[it.id] = it }
        write("replay:ghost:$id", ghost.snapshot)
        write("replay:comparison:$id", comparison.snapshot)
        if (results.isNotEmpty()) write("replay:ghost-results:$id", results.values.toList())
        if (next.status == TripStatus.FINISHED) {
            val newRoadM = read(roadKey(source), Array<ExploredRoad>::class.java).orEmpty().sumOf { it.firstExploredDistanceM }
            var collected = replayTitles()
            val facts = listOfNotNull(AchievementFact.TripFinished.from(update)) + results.values.filter { it.finalized }.map {
                AchievementFact.GhostFinished(it, requireNotNull(next.trip.endedAtMs))
            }
            facts.forEach { fact ->
                val progress = AchievementProgress(acquiredTitleIds = collected.filter { it.inputSource == source }
                    .map { it.title.titleId }.toSet(), newRoadDistanceM = newRoadM)
                val qualified = replayTitleEvaluator.evaluate(fact, progress)
                collected = collected + qualified.map {
                    CollectedTitle(AchievementTitle(it.titleId, it.conditionText, it.acquiredAtMs, it.tripId), source)
                }
            }
            if (collected.isNotEmpty()) write("collected-titles", collected)
        }
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
