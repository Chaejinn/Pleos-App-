package com.pleos.explorer.data.catalog

import android.content.Context
import com.pleos.explorer.domain.model.legacy.Landmark
import com.pleos.explorer.domain.model.legacy.LandmarkType
import org.json.JSONArray

/** assets/catalog/legacy/landmarks.json에서 도감 명소 목록을 읽는다. */
object LandmarkLoader {
    fun load(context: Context): List<Landmark> {
        val arr = JSONArray(context.assets.open("catalog/legacy/landmarks.json").bufferedReader().use { it.readText() })
        return parse(arr)
    }

    internal fun parse(arr: JSONArray): List<Landmark> {
        val landmarks = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Landmark(
                id = o.getString("id"),
                name = o.getString("name"),
                type = LandmarkType.valueOf(o.getString("type")),
                region = o.getString("region"),
                lat = o.getDouble("lat"),
                lon = o.getDouble("lon"),
                radiusM = o.optDouble("radiusM", 300.0),
                story = o.optString("story").ifBlank { null },
            )
        }
        require(landmarks.isNotEmpty()) { "Empty landmark catalog" }
        require(landmarks.map { it.id }.distinct().size == landmarks.size) { "Duplicate landmark ID" }
        landmarks.forEach {
            require(it.id.isNotBlank() && it.name.isNotBlank() && it.region.isNotBlank()) { "Missing landmark identity" }
            require(it.lat.isFinite() && it.lat in -90.0..90.0 && it.lon.isFinite() && it.lon in -180.0..180.0) {
                "Invalid landmark coordinate"
            }
            require(it.radiusM.isFinite() && it.radiusM > 0.0 && it.radiusM <= 5_000.0) { "Invalid landmark radius" }
        }
        return landmarks
    }
}
