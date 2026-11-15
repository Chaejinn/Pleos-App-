package com.pleos.explorer.platform.vehicle

import com.pleos.explorer.domain.engine.legacy.RoadDex
import com.pleos.explorer.domain.geo.legacy.Geo
import com.pleos.explorer.domain.model.legacy.DexEvent
import com.pleos.explorer.domain.port.legacy.InMemoryDexStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeoulDemoRouteTest {
    @Test fun replayCollectsBothLandmarksInOrderOnceAndCanBeRepeated() {
        repeat(2) {
            val dex = RoadDex(SeoulDemoRoute.landmarks, InMemoryDexStore())
            val collected = mutableListOf<String>()
            for (frame in 0..720) {
                val p = SeoulDemoRoute.atDistance(SeoulDemoRoute.lengthM * frame / 720)
                val event = dex.onLocation(p.lat, p.lon, frame * 400L)
                if (event is DexEvent.Collected) collected += event.landmark.id
            }
            assertEquals(listOf("demo_gwanghwamun", "demo_folk_museum"), collected)
        }
    }

    @Test fun routeStaysInJongnoAndMovesWithoutJumps() {
        assertTrue(SeoulDemoRoute.lengthM in 1150.0..1250.0)
        val samples = (0..720).map { SeoulDemoRoute.atDistance(SeoulDemoRoute.lengthM * it / 720) }
        samples.forEach {
            assertTrue(it.lat in 37.572..37.583)
            assertTrue(it.lon in 126.976..126.981)
            assertTrue(it.bearing >= 0 && it.bearing < 360)
        }
        samples.zipWithNext().forEach { (a, b) ->
            assertTrue(Geo.distanceM(a.lat, a.lon, b.lat, b.lon) < 2.0)
        }
        assertEquals(SeoulDemoRoute.points.first().lat, samples.first().lat, 0.0000001)
        assertEquals(SeoulDemoRoute.points.last().lon, samples.last().lon, 0.0000001)
    }

    @Test fun interpolationPreservesEveryRoadCorner() {
        SeoulDemoRoute.points.forEachIndexed { index, point ->
            val interpolated = SeoulDemoRoute.atDistance(SeoulDemoRoute.cumulativeM[index])
            assertEquals(point.lat, interpolated.lat, 0.0000001)
            assertEquals(point.lon, interpolated.lon, 0.0000001)
        }
    }
}
