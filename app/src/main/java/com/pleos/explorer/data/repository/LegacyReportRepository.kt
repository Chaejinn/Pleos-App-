package com.pleos.explorer.data.repository

import com.pleos.explorer.data.catalog.LandmarkLoader

import com.pleos.explorer.data.local.TripStore

import com.pleos.explorer.data.local.FileDexStore

import android.content.Context
import com.pleos.explorer.domain.engine.legacy.RoadDex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.pleos.explorer.data.local.recording.RoomTrackRecordingStore
import com.pleos.explorer.domain.model.RecordingSnapshot

data class LegacyReport(val collection: String = "", val trip: String = "아직 주행 기록이 없어요",
    val trips: List<com.pleos.explorer.domain.model.legacy.Trip> = emptyList(),
    val recordings: List<RecordingSnapshot> = emptyList(),
    val inputSources: Map<String, com.pleos.explorer.data.local.recording.RecordingInputSource> = emptyMap(),
    val regionBadges: List<com.pleos.explorer.domain.model.RegionBadge> = emptyList(),
    val pendingRewards: List<com.pleos.explorer.domain.model.PendingReward> = emptyList(),
    val regionNames: Map<String, String> = emptyMap(),
    val regionCatalogMessage: String = "지역 경계 데이터를 준비하고 있어요",
    val titles: List<com.pleos.explorer.domain.model.AchievementTitle> = emptyList(),
    val ghostResults: List<com.pleos.explorer.domain.model.GhostResult> = emptyList(),
    val regionCards: List<com.pleos.explorer.data.catalog.RegionCard> = emptyList(),
    val catalogAttributions: List<String> = emptyList(),
    val landmarks: List<com.pleos.explorer.domain.model.legacy.Landmark> = emptyList(),
    val landmarkVisits: List<com.pleos.explorer.domain.model.LandmarkVisit> = emptyList(),
    val replayTitles: List<com.pleos.explorer.domain.model.CollectedTitle> = emptyList())

/** 기존 데모 기록은 신규 Room 계약으로 옮기기 전까지 별도 읽기 모델로 유지한다. */
class LegacyReportRepository(context: Context, private val clearMapCache: suspend () -> Unit = {},
    private val recordingStore: (() -> RoomTrackRecordingStore)? = null) {
    private val applicationContext = context.applicationContext
    private val operations = Mutex()
    private val regionCatalog by lazy { com.pleos.explorer.data.catalog.RegionCatalogLoader.load(applicationContext) }

    suspend fun acknowledgeReward(id: String) = operations.withLock {
        requireNotNull(recordingStore).invoke().acknowledgeRegionReward(id, System.currentTimeMillis())
    }
    suspend fun acknowledgeLandmark(key: String) = operations.withLock {
        requireNotNull(recordingStore).invoke().acknowledgeLandmark(key)
    }
    suspend fun acknowledgeTitle(key: String) = operations.withLock {
        requireNotNull(recordingStore).invoke().acknowledgeTitle(key)
    }

    suspend fun deleteTrip(id: String) = operations.withLock { withContext(Dispatchers.IO) {
        TripStore(applicationContext).delete(id)
    } }

    suspend fun deleteAll() = operations.withLock { withContext(Dispatchers.IO) {
        clearMapCache()
        recordingStore?.invoke()?.deleteAll()
        listOf("trips.json", "dex.json").forEach { name ->
            val file = applicationContext.filesDir.resolve(name)
            check(!file.exists() || file.delete()) { "기록을 삭제하지 못했습니다" }
        }
    } }

    suspend fun deleteRecording(id: String) = operations.withLock {
        requireNotNull(recordingStore).invoke().deleteTrip(id)
    }

    suspend fun finishInterrupted(id: String) = operations.withLock {
        val store = requireNotNull(recordingStore).invoke()
        val gps = store.inputSources(listOf(id))[id] == com.pleos.explorer.data.local.recording.RecordingInputSource.ANDROID_GPS
        val engine = com.pleos.explorer.domain.usecase.RecordTrackUseCase(if (gps) store.gpsTracks else store,
            com.pleos.explorer.domain.engine.TrackRecorder(com.pleos.explorer.domain.port.IdGenerator {
                java.util.UUID.randomUUID().toString()
            }))
        store.atomic {
            val ghosts = if (gps && store.ghosts.load(id) != null && store.ghostComparisons.load(id) != null)
                com.pleos.explorer.service.GhostRecordingSession(store, store.gpsHistory()) else null
            val resumed = engine.resume(id, java.util.UUID.randomUUID().toString())
            ghosts?.resume(resumed)
            val finished = engine.finish(id, System.currentTimeMillis())
            if (gps) store.recordGpsTripAchievement(finished)
            if (finished.snapshot.status != com.pleos.explorer.domain.model.TripStatus.DISCARDED) ghosts?.process(finished)
            store.clearLifecycleTrip(id)
        }
    }

    suspend fun load(): LegacyReport = operations.withLock { withContext(Dispatchers.IO) {
        val landmarks = LandmarkLoader.load(applicationContext)
        val store = FileDexStore(applicationContext)
        val dex = RoadDex(landmarks, store)
        val collected = landmarks.filter { store.isCollected(it.id) }
        val collection = buildString {
            append("기존 명소 기록 ${collected.size} / ${landmarks.size}\n")
            landmarks.map { it.region }.distinct().forEach { append("$it ${dex.completion(it)}%\n") }
        }
        val trips = TripStore(applicationContext).all().asReversed()
        val trip = trips.firstOrNull()
        val recordingRepository = recordingStore?.invoke()
        val recordings = recordingRepository?.listSnapshots().orEmpty()
        val inputSources = recordingRepository?.inputSources(recordings.map { it.trip.id }).orEmpty()
        LegacyReport(collection, trip?.let { last -> buildString {
            append("최근 주행 ${last.segments.size}구간\n")
            last.segments.forEach { segment ->
                append("${segment.index + 1}구간 · 급제동 ${segment.harshBrakes} · 급조향 ${segment.harshSteers} · 깜빡이 누락 ${segment.turnsWithoutSignal}\n")
            }
        } } ?: "아직 주행 기록이 없어요", trips, recordings, inputSources,
            recordingRepository?.regionBadges().orEmpty(), recordingRepository?.pendingRegionRewards().orEmpty(),
            (regionCatalog as? com.pleos.explorer.data.catalog.RegionCatalogState.Ready)?.regions.orEmpty().associate { it.code to it.name },
            when (regionCatalog) {
                com.pleos.explorer.data.catalog.RegionCatalogState.Missing -> "지역 경계 데이터를 준비하고 있어요"
                com.pleos.explorer.data.catalog.RegionCatalogState.Invalid -> "지역 데이터를 확인하지 못했어요. 방문 판정을 중단했어요"
                is com.pleos.explorer.data.catalog.RegionCatalogState.Ready -> "지역 안에서 1km 이동 또는 3분 체류하면 방문으로 기록해요"
            }, recordingRepository?.achievementTitles().orEmpty(), recordingRepository?.ghostResults(recordings.map { it.trip.id }).orEmpty(),
            (regionCatalog as? com.pleos.explorer.data.catalog.RegionCatalogState.Ready)?.cards.orEmpty(),
            (regionCatalog as? com.pleos.explorer.data.catalog.RegionCatalogState.Ready)?.attributions.orEmpty(),
            landmarks,recordingRepository?.landmarkVisits().orEmpty(),recordingRepository?.collectedTitles().orEmpty().filter { it.isReplay })
    } }
}
