package com.pleos.explorer.domain.port

import com.pleos.explorer.domain.model.GhostCursor
import com.pleos.explorer.domain.model.HistoricalTrack

interface HistoryRepository {
    suspend fun findNearby(gridKeys: Set<String>, excludedTripId: String): List<HistoricalTrack>
    suspend fun findForward(cursor: GhostCursor, maxDistanceM: Double): HistoricalTrack
}
