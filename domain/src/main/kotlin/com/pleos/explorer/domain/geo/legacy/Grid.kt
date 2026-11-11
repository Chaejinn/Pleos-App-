package com.pleos.explorer.domain.geo.legacy

import com.pleos.explorer.domain.model.legacy.Cell

import kotlin.math.cos
import kotlin.math.floor

object Grid {
    private const val CELL_M = 150.0
    private const val M_PER_DEG_LAT = 111_320.0

    fun cellOf(lat: Double, lon: Double): Cell {
        val mPerDegLon = M_PER_DEG_LAT * cos(Math.toRadians(lat))
        return Cell(floor(lon * mPerDegLon / CELL_M).toInt(), floor(lat * M_PER_DEG_LAT / CELL_M).toInt())
    }
}
