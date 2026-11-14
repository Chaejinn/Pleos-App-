package com.pleos.explorer.domain.engine

import com.pleos.explorer.domain.geo.GeoDistance
import com.pleos.explorer.domain.model.GeoPoint
import com.pleos.explorer.domain.model.LocationSample
import com.pleos.explorer.domain.model.LocationValidation
import com.pleos.explorer.domain.model.legacy.Landmark
import com.pleos.explorer.domain.model.legacy.LandmarkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LandmarkCollectorTest {
    private fun location(point: GeoPoint) = (LocationValidator().validate(
        LocationSample(1000, 1000, point, 10.0, 0.0, 5.0), null) as LocationValidation.Accepted).location

    @Test fun collectsAllOverlappingPlacesAndExcludesAlreadyCollected() {
        val places = listOf("a", "b", "c").map {
            Landmark(it, it, LandmarkType.LANDMARK, "서울", 37.5, 127.0, 150.0)
        }
        assertEquals(listOf("b", "c"), LandmarkCollector(places).findNew(location(GeoPoint(37.5, 127.0)), setOf("a")).map { it.id })
    }

    @Test fun radiusBoundaryIsInclusiveAndJustOutsideDoesNotCollect() {
        val center = GeoPoint(37.5, 127.0)
        val edge = GeoPoint(37.501, 127.0)
        val radius = GeoDistance.meters(center, edge)
        val place = Landmark("a", "명소", LandmarkType.NATURE, "서울", center.latitudeDeg, center.longitudeDeg, radius)
        assertEquals(1, LandmarkCollector(listOf(place)).findNew(location(edge), emptySet()).size)
        assertTrue(LandmarkCollector(listOf(place.copy(radiusM = radius - 0.01))).findNew(location(edge), emptySet()).isEmpty())
    }
}
