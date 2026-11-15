package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.geo.GeoDistance
import com.pleos.explorer.domain.model.CoastPoint
import com.pleos.explorer.domain.model.CoastVisit
import com.pleos.explorer.domain.model.DetectionPolicy
import com.pleos.explorer.domain.model.ValidatedLocation

class CoastVisitDetector(points: List<CoastPoint>) {
    private val points = points.toList()

    init {
        require(this.points.map { it.id }.distinct().size == this.points.size)
    }

    fun detect(location: ValidatedLocation, tripId: String? = null): List<CoastVisit> {
        require(tripId == null || tripId.isNotBlank())
        return points.filter { GeoDistance.meters(location.point, it.point) <= DetectionPolicy.COAST_RADIUS_M }
            .map { CoastVisit(it.id, it.coast, location.timeMs, tripId) }
    }
}
