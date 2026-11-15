package com.pleos.explorer.domain

import com.pleos.explorer.domain.engine.LocationValidator
import com.pleos.explorer.domain.engine.TrackRecorder
import com.pleos.explorer.domain.geo.GeoDistance
import com.pleos.explorer.domain.geo.GridProjector
import com.pleos.explorer.domain.model.GeoPoint
import com.pleos.explorer.domain.model.HistoricalTrack
import com.pleos.explorer.domain.model.LocationSample
import com.pleos.explorer.domain.model.LocationValidation
import com.pleos.explorer.domain.model.TrackPoint
import com.pleos.explorer.domain.port.IdGenerator

object GhostTestFixtures {
    const val NOW_MS = 10_000_000L

    fun point(northM: Double, eastM: Double = 0.0) =
        GeoPoint(Math.toDegrees(northM / GeoDistance.EARTH_RADIUS_M),
            Math.toDegrees(eastM / GeoDistance.EARTH_RADIUS_M))

    fun location(northM: Double, elapsedMs: Long, eastM: Double = 0.0, bearing: Double? = 0.0) =
        (LocationValidator().validate(LocationSample(NOW_MS + elapsedMs, elapsedMs,
            point(northM, eastM), null, bearing, 5.0), null) as LocationValidation.Accepted).location

    fun history(id: String = "past", atMs: Long = 1_000_000, bearing: Double? = 0.0,
        eastM: Double = 0.0, count: Int = 121): HistoricalTrack = HistoricalTrack(id,
        (0 until count).map { index ->
            val p = point(index * 25.0, eastM)
            TrackPoint("$id-$index", id, atMs + index * 1_000, p, null, bearing, 5.0,
                GridProjector().key(p), "segment", index.toLong())
        })

    fun koreanHistory(): HistoricalTrack = recordedHistory((0..80).map {
        RegionTestFixtures.point(0.0, it * 25.0) to 0.0
    })

    fun revisitedHistory(): HistoricalTrack {
        val points = mutableListOf(RegionTestFixtures.point(0.0, 0.0) to 0.0)
        var north = 0.0
        var east = 0.0
        fun leg(northStep: Double, eastStep: Double, count: Int, bearing: Double) {
            points[points.lastIndex] = points.last().first to bearing
            repeat(count) {
                north += northStep
                east += eastStep
                points.add(RegionTestFixtures.point(east, north) to bearing)
            }
        }
        leg(25.0, 0.0, 12, 0.0)
        leg(0.0, 25.0, 4, 90.0)
        leg(-25.0, 0.0, 12, 180.0)
        leg(0.0, -25.0, 4, 270.0)
        leg(25.0, 0.0, 40, 0.0)
        return recordedHistory(points)
    }

    private fun recordedHistory(points: List<Pair<GeoPoint, Double>>): HistoricalTrack {
        var id = 0
        val recorder = TrackRecorder(IdGenerator { "past-${++id}" })
        var track = recorder.start("past", 1_000_000, "old").snapshot
        val stored = points.flatMapIndexed { index, (point, bearing) ->
            val validation = LocationValidator().validate(LocationSample(1_000_000 + index * 1_000L,
                index * 1_000L, point, null, bearing, 5.0), track.previous)
            check(validation is LocationValidation.Accepted)
            val update = recorder.process(track, validation, index.toLong(), emptyMap())
            track = update.snapshot
            update.points
        }
        return HistoricalTrack("past", stored + recorder.finish(track, 1_000_000 + points.size * 1_000L, stored).points)
    }
}
