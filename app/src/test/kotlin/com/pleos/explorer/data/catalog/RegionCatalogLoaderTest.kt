package com.pleos.explorer.data.catalog

import org.junit.Assert.*
import org.junit.Test

class RegionCatalogLoaderTest {
    private val feature = """{"type":"Feature","properties":{"code":"001","name":"합성 영역","provinceCode":"00","provinceName":"합성","representativePoint":[127,36],"badgeAssetPath":"badges/test.png"},"geometry":{"type":"Polygon","coordinates":[[[126.9,35.9],[127.1,35.9],[127.1,36.1],[126.9,36.1],[126.9,35.9]],[[126.95,35.95],[126.95,35.98],[126.98,35.98],[126.98,35.95],[126.95,35.95]]]}}"""
    private fun collection(features: String = feature) = """{"type":"FeatureCollection","features":[$features]}"""
    @Test fun preservesStringCodeCoordinateOrderAndHoles() {
        val assets = mutableListOf<String>()
        val cards = mutableListOf<RegionCard>()
        val region = RegionCatalogLoader.parse(collection(),onMetadata={cards.add(it)}) { assets.add(it) }.single()
        assertEquals("001", region.code)
        assertEquals(36.0, region.representativePoint.latitudeDeg, 0.0)
        assertEquals(127.0, region.representativePoint.longitudeDeg, 0.0)
        assertEquals(1, region.geometry.polygons.single().holes.size)
        assertEquals(listOf("badges/test.png"), assets)
        assertEquals("합성",cards.single().provinceName)
        assertEquals("badges/test.png",cards.single().badgeAssetPath)
        assertEquals(region.code,cards.single().code)
    }
    @Test fun duplicateRegionsAreRejected() {
        try { RegionCatalogLoader.parse(collection("$feature,$feature")) {}; fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun invalidCoordinatesAreRejected() {
        try { RegionCatalogLoader.parse(collection().replace("[127,36]", "[127,136]")) {}; fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun unavailableBadgeAssetIsNotIgnored() {
        try { RegionCatalogLoader.parse(collection()) { error("Missing asset") }; fail() }
        catch (_: IllegalStateException) { }
    }
}
