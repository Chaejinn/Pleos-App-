package com.pleos.explorer.domain.port

import com.pleos.explorer.domain.model.ExploredRoad
import com.pleos.explorer.domain.model.RecordingSnapshot
import com.pleos.explorer.domain.model.TrackPoint
import com.pleos.explorer.domain.model.TrackUpdate

fun interface IdGenerator {
    fun newId(): String
}

interface TrackRecordingStore {
    suspend fun load(tripId: String): RecordingSnapshot?
    suspend fun loadPoints(tripId: String): List<TrackPoint>
    suspend fun findRoads(gridKeys: Set<String>): Map<String, ExploredRoad>

    /** 한 트랜잭션에서 revision 확인, 포인트 추가/폐기, 확정 격자와 snapshot 저장을 수행한다. */
    suspend fun commit(expectedRevision: Long?, update: TrackUpdate)
}
