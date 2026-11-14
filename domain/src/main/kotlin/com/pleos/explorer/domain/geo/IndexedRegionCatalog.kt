package com.pleos.explorer.domain.geo

import com.pleos.explorer.domain.model.GeoBounds
import com.pleos.explorer.domain.model.GeoPoint
import com.pleos.explorer.domain.model.RegionDefinition
import com.pleos.explorer.domain.model.RegionGeometry
import com.pleos.explorer.domain.model.RegionPolygon
import com.pleos.explorer.domain.port.RegionCatalog

class IndexedRegionCatalog(regions: List<RegionDefinition>) : RegionCatalog {
    private val index: BoundsIndex<RegionDefinition>

    init {
        require(regions.map { it.code }.distinct().size == regions.size) { "Duplicate region code" }
        val copied = regions.map { region ->
            region.copy(geometry = RegionGeometry(region.geometry.polygons.map { polygon ->
                RegionPolygon(polygon.outer.toList(), polygon.holes.map { it.toList() })
            }))
        }
        copied.forEach { RegionShapeMath.validate(it.geometry) }
        index = BoundsIndex(copied) { it.bbox }
    }

    override fun findCandidates(point: GeoPoint): List<RegionDefinition> = findCandidates(GeoBounds.between(point, point))

    override fun findCandidates(bounds: GeoBounds): List<RegionDefinition> = index.query(bounds).sortedBy { it.code }
}

internal class BoundsIndex<T>(entries: List<T>, private val bounds: (T) -> GeoBounds) {
    private class Node<T>(val bbox: GeoBounds, val items: List<T>, val left: Node<T>?, val right: Node<T>?)
    private val root = build(entries)

    fun query(query: GeoBounds): List<T> = buildList { collect(root, query, this) }

    private fun collect(node: Node<T>?, query: GeoBounds, result: MutableList<T>) {
        if (node == null || !node.bbox.intersects(query)) return
        result.addAll(node.items.filter { bounds(it).intersects(query) })
        collect(node.left, query, result)
        collect(node.right, query, result)
    }

    private fun build(items: List<T>): Node<T>? {
        if (items.isEmpty()) return null
        val boxes = items.map(bounds)
        val box = GeoBounds(boxes.minOf { it.westDeg }, boxes.minOf { it.southDeg },
            boxes.maxOf { it.eastDeg }, boxes.maxOf { it.northDeg })
        if (items.size <= 8) return Node(box, items, null, null)
        val horizontal = box.eastDeg - box.westDeg >= box.northDeg - box.southDeg
        val ordered = items.sortedBy { item -> bounds(item).let {
            if (horizontal) it.westDeg + it.eastDeg else it.southDeg + it.northDeg
        } }
        val middle = ordered.size / 2
        return Node(box, emptyList(), build(ordered.take(middle)), build(ordered.drop(middle)))
    }
}
