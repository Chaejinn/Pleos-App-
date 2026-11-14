package com.pleos.explorer.presentation.map

import com.pleos.explorer.domain.model.GeoPoint
import com.pleos.explorer.domain.model.PathSegment
import org.junit.Assert.*
import org.junit.Test

class KakaoRouteGeometryTest {
    @Test fun separateReceptionSegmentsStaySeparate() {
        val first = listOf(GeoPoint(36.0, 127.0), GeoPoint(36.001, 127.0))
        val second = listOf(GeoPoint(36.1, 127.0), GeoPoint(36.101, 127.0))
        assertEquals(listOf(first, second), drawableRouteSegments(listOf(PathSegment("a", first), PathSegment("b", second))))
    }
    @Test fun isolatedPointsAreNotInventedAsLines() {
        assertTrue(drawableRouteSegments(listOf(PathSegment("empty", emptyList()),
            PathSegment("one", listOf(GeoPoint(36.0, 127.0))))).isEmpty())
    }
}
