package com.pleos.explorer.data.catalog

import android.content.Context
import com.pleos.explorer.domain.model.legacy.Landmark
import com.pleos.explorer.domain.model.legacy.LandmarkType
import org.json.JSONArray

/** assets/catalog/legacy/landmarks.json에서 도감 명소 목록을 읽는다. */
object LandmarkLoader {
    fun load(context: Context): List<Landmark> {
        val arr = JSONArray(context.assets.open("catalog/legacy/landmarks.json").bufferedReader().readText())
        return (0 until arr.length()).map {
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
    }
}
