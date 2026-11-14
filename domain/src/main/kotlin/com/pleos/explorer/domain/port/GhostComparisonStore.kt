package com.pleos.explorer.domain.port

import com.pleos.explorer.domain.model.GhostComparisonSnapshot
import com.pleos.explorer.domain.model.GhostComparisonUpdate

interface GhostComparisonStore {
    suspend fun load(tripId: String): GhostComparisonSnapshot?

    /** revision 비교 후 snapshot·결과 ID별 변경·신규 보상을 한 트랜잭션으로 반영한다. */
    suspend fun commit(expectedRevision: Long?, update: GhostComparisonUpdate)
}
