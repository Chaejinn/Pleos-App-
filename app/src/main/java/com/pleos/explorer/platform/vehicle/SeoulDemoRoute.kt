package com.pleos.explorer.platform.vehicle

import com.pleos.explorer.domain.geo.legacy.Geo
import com.pleos.explorer.domain.model.legacy.Landmark
import com.pleos.explorer.domain.model.legacy.LandmarkType
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class DemoPoint(val lat: Double, val lon: Double, val bearing: Float = 0f)

/** OSM/OSRM road geometry, retrieved 2026-11-15. See docs/DEMO_ROUTE.md. */
object SeoulDemoRoute {
    val points = listOf(
        DemoPoint(37.572586, 126.97728),
        DemoPoint(37.572722, 126.977274),
        DemoPoint(37.573125, 126.977258),
        DemoPoint(37.574121, 126.977218),
        DemoPoint(37.574474, 126.977205),
        DemoPoint(37.574934, 126.977185),
        DemoPoint(37.575064, 126.97718),
        DemoPoint(37.575114, 126.977178),
        DemoPoint(37.57519, 126.977177),
        DemoPoint(37.575215, 126.977257),
        DemoPoint(37.575231, 126.977293),
        DemoPoint(37.575349, 126.977514),
        DemoPoint(37.575437, 126.977651),
        DemoPoint(37.57549, 126.977747),
        DemoPoint(37.575524, 126.977837),
        DemoPoint(37.57556, 126.977952),
        DemoPoint(37.575581, 126.978049),
        DemoPoint(37.575629, 126.978366),
        DemoPoint(37.575653, 126.97856),
        DemoPoint(37.575724, 126.97916),
        DemoPoint(37.575735, 126.979253),
        DemoPoint(37.575743, 126.979395),
        DemoPoint(37.575757, 126.979526),
        DemoPoint(37.575902, 126.97954),
        DemoPoint(37.57601, 126.979561),
        DemoPoint(37.576068, 126.979573),
        DemoPoint(37.576137, 126.979578),
        DemoPoint(37.5764, 126.979581),
        DemoPoint(37.577062, 126.979546),
        DemoPoint(37.577477, 126.979546),
        DemoPoint(37.577836, 126.979543),
        DemoPoint(37.578148, 126.979561),
        DemoPoint(37.578276, 126.97957),
        DemoPoint(37.578413, 126.979578),
        DemoPoint(37.578922, 126.979611),
        DemoPoint(37.5792, 126.979632),
        DemoPoint(37.579566, 126.979746),
        DemoPoint(37.579953, 126.979887),
        DemoPoint(37.580083, 126.979927),
        DemoPoint(37.580251, 126.979988),
        DemoPoint(37.580889, 126.980228),
        DemoPoint(37.580926, 126.980242),
        DemoPoint(37.58101, 126.980263),
        DemoPoint(37.581255, 126.980266),
        DemoPoint(37.581925, 126.980272),
    )
    val landmarks = listOf(
        Landmark("demo_gwanghwamun", "광화문", LandmarkType.LANDMARK, "서울 종로",
            37.57596, 126.97688, 140.0, "서울의 시간을 여는 문"),
        Landmark("demo_folk_museum", "국립민속박물관", LandmarkType.LANDMARK, "서울 종로",
            37.58165, 126.97896, 145.0, "일상의 이야기를 만나는 곳"),
    )
    val cumulativeM: List<Double> = points.zipWithNext().runningFold(0.0) { total, (a, b) ->
        total + Geo.distanceM(a.lat, a.lon, b.lat, b.lon)
    }
    val lengthM = cumulativeM.last()

    fun atDistance(distanceM: Double): DemoPoint {
        val distance = distanceM.coerceIn(0.0, lengthM)
        val index = cumulativeM.indexOfFirst { it >= distance }.coerceAtLeast(1)
        val a = points[index - 1]
        val b = points[index]
        val fraction = (distance - cumulativeM[index - 1]) / (cumulativeM[index] - cumulativeM[index - 1])
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val bearing = (Math.toDegrees(atan2(sin(dLon) * cos(lat2),
            cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon))) + 360) % 360
        return DemoPoint(a.lat + (b.lat - a.lat) * fraction, a.lon + (b.lon - a.lon) * fraction, bearing.toFloat())
    }
}
