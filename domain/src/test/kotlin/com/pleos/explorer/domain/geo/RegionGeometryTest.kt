package com.pleos.explorer.domain.geo

import com.pleos.explorer.domain.RegionTestFixtures.point
import com.pleos.explorer.domain.RegionTestFixtures.rectangle
import com.pleos.explorer.domain.RegionTestFixtures.region
import com.pleos.explorer.domain.model.GeoBounds
import com.pleos.explorer.domain.model.RegionGeometry
import com.pleos.explorer.domain.model.RegionPolygon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionGeometryTest {
    @Test
    fun `polygon excludes hole interiors and includes boundary points`() {
        val geometry = RegionGeometry(listOf(RegionPolygon(
            rectangle(-200.0, -200.0, 200.0, 200.0),
            listOf(rectangle(-50.0, -50.0, 50.0, 50.0)),
        )))
        val resolver = RegionGeometryResolver(IndexedRegionCatalog(listOf(region("A", geometry))))
        assertEquals("A", resolver.locate(point(100.0, 0.0)))
        assertNull(resolver.locate(point(0.0, 0.0)))
        assertNull(resolver.locate(point(300.0, 0.0)))
        assertEquals("A", resolver.locate(point(200.0, 0.0)))
        assertEquals("A", resolver.locate(point(50.0, 0.0)))
    }

    @Test
    fun `multipolygon preserves separated islands`() {
        val geometry = RegionGeometry(listOf(
            RegionPolygon(rectangle(-300.0, -100.0, -100.0, 100.0)),
            RegionPolygon(rectangle(100.0, -100.0, 300.0, 100.0)),
        ))
        val resolver = RegionGeometryResolver(IndexedRegionCatalog(listOf(region("A", geometry))))
        assertEquals("A", resolver.locate(point(-200.0, 0.0)))
        assertEquals("A", resolver.locate(point(200.0, 0.0)))
        assertNull(resolver.locate(point(0.0, 0.0)))
    }

    @Test
    fun `shared boundary retains previous region otherwise chooses code order`() {
        val left = region("A", -200.0, -100.0, 0.0, 100.0)
        val right = region("B", 0.0, -100.0, 200.0, 100.0)
        val resolver = RegionGeometryResolver(IndexedRegionCatalog(listOf(right, left)))
        assertEquals("A", resolver.locate(point(0.0, 0.0)))
        assertEquals("B", resolver.locate(point(0.0, 0.0), "B"))
        assertEquals("A", resolver.locate(point(0.0, 0.0), "missing"))
        assertEquals("A", resolver.locate(point(-10.0, 0.0), "B"))
    }

    @Test
    fun `line splitting finds crossed region even when both endpoints are outside`() {
        val catalog = IndexedRegionCatalog(listOf(region("A", -50.0, -100.0, 50.0, 100.0)))
        val sections = RegionGeometryResolver(catalog).split(point(-100.0, 0.0), point(100.0, 0.0))
        assertEquals(listOf(null, "A", null), sections.map { it.regionCode })
        assertEquals(0.25, sections[1].startFraction, 1e-8)
        assertEquals(0.75, sections[1].endFraction, 1e-8)
        assertEquals(1.0, sections.sumOf { it.endFraction - it.startFraction }, 1e-9)
    }

    @Test
    fun `line crossing a hole is split into disconnected inside intervals`() {
        val geometry = RegionGeometry(listOf(RegionPolygon(
            rectangle(-200.0, -100.0, 200.0, 100.0),
            listOf(rectangle(-50.0, -50.0, 50.0, 50.0)),
        )))
        val resolver = RegionGeometryResolver(IndexedRegionCatalog(listOf(region("A", geometry))))
        val sections = resolver.split(point(-100.0, 0.0), point(100.0, 0.0))
        assertEquals(listOf("A", null, "A"), sections.map { it.regionCode })
        assertEquals(0.5, sections.filter { it.regionCode == null }
            .sumOf { it.endFraction - it.startFraction }, 1e-8)
    }

    @Test
    fun `line on shared border preserves selected region without duplicate intervals`() {
        val resolver = RegionGeometryResolver(IndexedRegionCatalog(listOf(
            region("A", -100.0, -200.0, 0.0, 200.0),
            region("B", 0.0, -200.0, 100.0, 200.0),
        )))
        val sections = resolver.split(point(0.0, -100.0), point(0.0, 100.0), "B")
        assertEquals(listOf("B"), sections.map { it.regionCode })
        assertEquals(0.0, sections.single().startFraction, 0.0)
        assertEquals(1.0, sections.single().endFraction, 0.0)
    }

    @Test
    fun `bounding box queries omit distant regions and preserve crossing candidates`() {
        val catalog = IndexedRegionCatalog(listOf(
            region("A", -100.0, -100.0, 100.0, 100.0),
            region("B", 1000.0, 1000.0, 1100.0, 1100.0),
        ))
        assertEquals(listOf("A"), catalog.findCandidates(point(0.0, 0.0)).map { it.code })
        assertTrue(catalog.findCandidates(point(500.0, 500.0)).isEmpty())
        assertEquals(listOf("A"), catalog.findCandidates(GeoBounds.between(
            point(-500.0, 0.0), point(500.0, 0.0))).map { it.code })
        assertTrue(IndexedRegionCatalog(emptyList()).findCandidates(point(0.0, 0.0)).isEmpty())
    }

    @Test
    fun `invalid polygon data and duplicate region codes fail instead of becoming empty catalog`() {
        assertThrows(IllegalArgumentException::class.java) { RegionGeometry(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            RegionPolygon(listOf(point(0.0, 0.0), point(100.0, 0.0), point(100.0, 100.0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RegionPolygon(listOf(point(0.0, 0.0), point(0.0, 0.0), point(0.0, 0.0), point(0.0, 0.0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            IndexedRegionCatalog(listOf(region("A"), region("A")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            IndexedRegionCatalog(listOf(region("A", RegionGeometry(listOf(RegionPolygon(
                rectangle(-100.0, -100.0, 100.0, 100.0),
                listOf(rectangle(200.0, 200.0, 300.0, 300.0)),
            ))))))
        }
    }

    @Test
    fun `consecutive duplicate ring vertices remain valid`() {
        val ring = rectangle(-100.0, -100.0, 100.0, 100.0)
        val duplicated = ring.take(2) + ring.drop(1)
        val resolver = RegionGeometryResolver(IndexedRegionCatalog(listOf(region("A",
            RegionGeometry(listOf(RegionPolygon(duplicated)))))))
        assertEquals("A", resolver.locate(point(0.0, 0.0)))
    }

    @Test
    fun `indexed queries cross multiple tree branches without losing candidates`() {
        val catalog = IndexedRegionCatalog((0..5).flatMap { row -> (0..5).map { column ->
            region("R${row}_$column", column * 300.0, row * 300.0, column * 300.0 + 100, row * 300.0 + 100)
        } })
        assertEquals(listOf("R4_3"), catalog.findCandidates(point(950.0, 1250.0)).map { it.code })
        val expected = (1..3).flatMap { row -> (1..3).map { column -> "R${row}_$column" } }.toSet()
        assertEquals(expected, catalog.findCandidates(GeoBounds.between(point(250.0, 250.0),
            point(1050.0, 1050.0))).map { it.code }.toSet())
        val sections = RegionGeometryResolver(catalog).split(point(-100.0, 350.0), point(1700.0, 350.0))
        assertEquals((0..5).map { "R1_$it" }, sections.mapNotNull { it.regionCode })
    }

    @Test
    fun `concave polygon may be exited and reentered within one segment`() {
        val ring = listOf(point(-200.0, -200.0), point(200.0, -200.0), point(200.0, 200.0),
            point(50.0, 200.0), point(50.0, -50.0), point(-50.0, -50.0), point(-50.0, 200.0),
            point(-200.0, 200.0), point(-200.0, -200.0))
        val resolver = RegionGeometryResolver(IndexedRegionCatalog(listOf(region("A",
            RegionGeometry(listOf(RegionPolygon(ring)))))))
        assertEquals(listOf("A", null, "A"), resolver.split(point(-100.0, 100.0),
            point(100.0, 100.0)).map { it.regionCode })
    }

    @Test
    fun `touching a polygon vertex does not manufacture an inside interval`() {
        val resolver = RegionGeometryResolver(IndexedRegionCatalog(listOf(region("A", 0.0, 0.0, 100.0, 100.0))))
        val sections = resolver.split(point(-100.0, 100.0), point(100.0, -100.0))
        assertEquals(listOf(null), sections.map { it.regionCode })
        assertEquals("A", resolver.locate(point(0.0, 0.0)))
    }

    @Test
    fun `invalid bounds self crossing rings and overlapping holes are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { GeoBounds(Double.NaN, 0.0, 1.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { GeoBounds(2.0, 0.0, 1.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { GeoBounds(0.0, 91.0, 1.0, 92.0) }
        assertThrows(IllegalArgumentException::class.java) {
            IndexedRegionCatalog(listOf(region("A", RegionGeometry(listOf(RegionPolygon(
                listOf(point(0.0, 0.0), point(100.0, 100.0), point(0.0, 100.0), point(80.0, 0.0), point(0.0, 0.0)),
            ))))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            IndexedRegionCatalog(listOf(region("A", RegionGeometry(listOf(RegionPolygon(
                rectangle(-200.0, -200.0, 200.0, 200.0),
                listOf(rectangle(-100.0, -100.0, 50.0, 50.0), rectangle(0.0, 0.0, 100.0, 100.0)),
            ))))))
        }
    }
}
