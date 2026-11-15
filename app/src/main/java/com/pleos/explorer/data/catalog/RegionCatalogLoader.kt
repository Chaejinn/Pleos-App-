package com.pleos.explorer.data.catalog

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.pleos.explorer.domain.geo.IndexedRegionCatalog
import com.pleos.explorer.domain.model.*
import java.security.MessageDigest

data class RegionCard(val code: String, val name: String, val provinceCode: String, val provinceName: String, val badgeAssetPath: String?)

sealed interface RegionCatalogState {
    data object Missing : RegionCatalogState
    data object Invalid : RegionCatalogState
    data class Ready(val regions: List<RegionDefinition>, val index: IndexedRegionCatalog,
        val cards: List<RegionCard> = regions.map { RegionCard(it.code,it.name,it.provinceCode,it.provinceCode,null) },
        val attributions: List<String> = emptyList()) : RegionCatalogState
}

object RegionCatalogLoader {
    fun load(context: Context): RegionCatalogState {
        val assets = context.assets
        val available=assets.list("catalog").orEmpty()
        if ("regions.geojson" !in available) return RegionCatalogState.Missing
        if ("manifest.json" !in available) return RegionCatalogState.Invalid
        return try {
            val manifest = JsonParser.parseString(assets.open("catalog/manifest.json").bufferedReader().use { it.readText() }).asJsonObject
            require(manifest["schemaVersion"].asInt == 1 && manifest["coordinateSystem"].asString == "WGS84")
            require(!manifest["synthetic"].asBoolean)
            require(manifest["dataVersion"].asString.isNotBlank())
            val sources = manifest.getAsJsonArray("sources")
            require(sources.size() > 0)
            sources.forEach {
                val source = it.asJsonObject
                listOf("name", "url", "retrievedAt", "license", "attribution").forEach { key ->
                    require(source[key].asString.isNotBlank())
                }
                require(source["license"].asString != "UNVERIFIED")
            }
            val files = manifest.getAsJsonArray("files").map { it.asJsonObject }
            val paths = files.map { it["path"].asString }
            require(paths.distinct().size == paths.size && "regions.geojson" in paths)
            files.forEach { file ->
                val path = file["path"].asString
                require(safePath(path))
                val bytes = assets.open("catalog/$path").use { it.readBytes() }
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                require(hash.equals(file["sha256"].asString, ignoreCase = true))
            }
            val cards = mutableListOf<RegionCard>()
            val regions = parse(assets.open("catalog/regions.geojson").bufferedReader().use { it.readText() }, onMetadata = { cards.add(it) }) { path ->
                require(safePath(path) && path in paths) { "Badge asset must be in manifest" }
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                assets.open("catalog/$path").use { android.graphics.BitmapFactory.decodeStream(it,null,bounds) }
                require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048) { "Invalid badge raster image" }
            }
            RegionCatalogState.Ready(regions, IndexedRegionCatalog(regions), cards.toList(),
                sources.map { it.asJsonObject["attribution"].asString }.distinct())
        } catch (_: Exception) { RegionCatalogState.Invalid }
    }

    internal fun parse(json: String, onMetadata: (RegionCard) -> Unit = {}, validateAsset: (String) -> Unit): List<RegionDefinition> {
        val root = JsonParser.parseString(json).asJsonObject
        require(root["type"].asString == "FeatureCollection")
        val regions = root.getAsJsonArray("features").map { element ->
            val feature = element.asJsonObject
            require(feature["type"].asString == "Feature")
            val properties = feature.getAsJsonObject("properties")
            fun text(key: String): String {
                val value = properties[key].asJsonPrimitive
                require(value.isString && value.asString.isNotBlank())
                return value.asString
            }
            val assetPath = text("badgeAssetPath")
            validateAsset(assetPath)
            val geometry = feature.getAsJsonObject("geometry")
            val coordinates = geometry.getAsJsonArray("coordinates")
            val polygons = when (geometry["type"].asString) {
                "Polygon" -> listOf(polygon(coordinates))
                "MultiPolygon" -> coordinates.map { polygon(it.asJsonArray) }
                else -> error("Unsupported region geometry")
            }
            RegionDefinition(text("code"), text("name"), text("provinceCode"),
                point(properties.getAsJsonArray("representativePoint")), RegionGeometry(polygons)).also {
                    onMetadata(RegionCard(it.code,it.name,it.provinceCode,text("provinceName"),assetPath))
                }
        }
        require(regions.isNotEmpty() && regions.map { it.code }.distinct().size == regions.size)
        IndexedRegionCatalog(regions)
        return regions
    }
    private fun polygon(rings: JsonArray): RegionPolygon {
        val parsed = rings.map { ring -> ring.asJsonArray.map { point(it.asJsonArray) } }
        require(parsed.isNotEmpty())
        return RegionPolygon(parsed.first(), parsed.drop(1))
    }
    private fun point(values: JsonArray): GeoPoint {
        require(values.size() == 2)
        val lon = values[0].asDouble; val lat = values[1].asDouble
        require(lon.isFinite() && lon in -180.0..180.0 && lat.isFinite() && lat in -90.0..90.0)
        return GeoPoint(lat, lon)
    }
    private fun safePath(path: String) = path.isNotBlank() && !path.startsWith("/") &&
        !path.contains('\\') && !path.contains(':') && path.split('/').none { it == ".." || it == "." || it.isEmpty() }
}
