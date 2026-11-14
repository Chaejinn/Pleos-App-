package com.pleos.explorer.domain.port

import com.pleos.explorer.domain.model.GhostSnapshot
import com.pleos.explorer.domain.model.GhostUpdate

interface GhostStore {
    suspend fun load(tripId: String): GhostSnapshot?

    /** revision 비교 후 snapshot과 종료 구간을 한 트랜잭션으로 반영한다. */
    suspend fun commit(expectedRevision: Long?, update: GhostUpdate)
}
