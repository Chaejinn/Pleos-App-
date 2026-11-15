package com.pleos.explorer.domain.port

import com.pleos.explorer.domain.model.RegionBadge
import com.pleos.explorer.domain.model.RegionVisitSnapshot
import com.pleos.explorer.domain.model.RegionVisitUpdate

interface RegionVisitStore {
    suspend fun load(): RegionVisitSnapshot?
    suspend fun findBadges(regionCodes: Set<String>): Map<String, RegionBadge>

    /** revision 비교, 방문·뱃지·보상과 snapshot을 한 트랜잭션으로 반영한다. */
    suspend fun commit(expectedRevision: Long?, update: RegionVisitUpdate)
}
