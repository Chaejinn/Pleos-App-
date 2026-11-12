package com.pleos.explorer.data.local

import android.content.Context
import com.pleos.explorer.domain.model.legacy.Cell
import com.pleos.explorer.domain.model.legacy.SegmentStats
import com.pleos.explorer.domain.model.legacy.Trip
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 지난 주행 기록. 최근 30개만 보관. filesDir/trips.json */
class TripStore(context: Context) {
    private val file = File(context.filesDir, "trips.json")

    fun all(): List<Trip> {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }

    fun add(trip: Trip) {
        val trips = (all() + trip).takeLast(30)
        write(trips)
    }

    fun delete(id: String) = write(all().filterNot { it.id == id })

    private fun write(trips: List<Trip>) {
        file.writeText(JSONArray(trips.map { toJson(it) }).toString())
    }

    private fun toJson(t: Trip) = JSONObject().apply {
        put("id", t.id)
        put("startedAtMs", t.startedAtMs)
        put("route", JSONArray(t.route.map { JSONArray(listOf(it.x, it.y)) }))
        put("segments", JSONArray(t.segments.map { s ->
            JSONObject().apply {
                put("index", s.index)
                put("harshBrakes", s.harshBrakes)
                put("harshSteers", s.harshSteers)
                put("turnsWithoutSignal", s.turnsWithoutSignal)
                s.avgEfficiency?.let { put("avgEfficiency", it.toDouble()) }
            }
        }))
    }

    private fun fromJson(o: JSONObject): Trip {
        val r = o.getJSONArray("route")
        val s = o.getJSONArray("segments")
        return Trip(
            id = o.getString("id"),
            startedAtMs = o.getLong("startedAtMs"),
            route = (0 until r.length()).map { val c = r.getJSONArray(it); Cell(c.getInt(0), c.getInt(1)) },
            segments = (0 until s.length()).map {
                val j = s.getJSONObject(it)
                SegmentStats(
                    index = j.getInt("index"),
                    harshBrakes = j.getInt("harshBrakes"),
                    harshSteers = j.getInt("harshSteers"),
                    turnsWithoutSignal = j.getInt("turnsWithoutSignal"),
                    avgEfficiency = if (j.has("avgEfficiency")) j.getDouble("avgEfficiency").toFloat() else null,
                )
            },
        )
    }
}
