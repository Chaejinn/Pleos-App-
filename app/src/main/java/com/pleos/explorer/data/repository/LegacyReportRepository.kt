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

data class LegacyReport(val collection: String = "", val trip: String = "아직 주행 기록이 없어요",
    val trips: List<com.pleos.explorer.domain.model.legacy.Trip> = emptyList())

/** 기존 데모 기록은 신규 Room 계약으로 옮기기 전까지 별도 읽기 모델로 유지한다. */
class LegacyReportRepository(context: Context) {
    private val applicationContext = context.applicationContext
    private val operations = Mutex()

    suspend fun deleteTrip(id: String) = operations.withLock { withContext(Dispatchers.IO) {
        TripStore(applicationContext).delete(id)
    } }

    suspend fun deleteAll() = operations.withLock { withContext(Dispatchers.IO) {
        listOf("trips.json", "dex.json").forEach { name ->
            val file = applicationContext.filesDir.resolve(name)
            check(!file.exists() || file.delete()) { "기록을 삭제하지 못했습니다" }
        }
    } }

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
        LegacyReport(collection, trip?.let { last -> buildString {
            append("최근 주행 ${last.segments.size}구간\n")
            last.segments.forEach { segment ->
                append("${segment.index + 1}구간 · 급제동 ${segment.harshBrakes} · 급조향 ${segment.harshSteers} · 깜빡이 누락 ${segment.turnsWithoutSignal}\n")
            }
        } } ?: "아직 주행 기록이 없어요", trips)
    } }
}
