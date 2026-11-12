package com.pleos.explorer.domain.geo.legacy

import com.pleos.explorer.domain.model.legacy.Cell

/** 한 번의 주행에서 지나간 격자 칸을 순서대로 기록한 경로 지문. */
class RouteFingerprint {
    private val _cells = mutableListOf<Cell>()
    val cells: List<Cell> get() = _cells

    fun add(lat: Double, lon: Double) {
        val c = Grid.cellOf(lat, lon)
        if (_cells.lastOrNull() != c) _cells += c
    }

    companion object {
        fun of(cells: List<Cell>) = RouteFingerprint().also { it._cells.addAll(cells) }
    }
}
