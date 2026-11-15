package com.pleos.explorer.domain.port

import com.pleos.explorer.domain.model.GeoBounds
import com.pleos.explorer.domain.model.GeoPoint
import com.pleos.explorer.domain.model.RegionDefinition

interface RegionCatalog {
    fun findCandidates(point: GeoPoint): List<RegionDefinition>
    fun findCandidates(bounds: GeoBounds): List<RegionDefinition>
}
