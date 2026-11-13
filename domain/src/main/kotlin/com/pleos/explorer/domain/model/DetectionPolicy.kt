package com.pleos.explorer.domain.model

object DetectionPolicy {
    const val MAX_LOCATION_ACCURACY_M = 50.0
    const val LOCATION_GAP_MS = 10_000L
    const val TRACK_DISTANCE_M = 20.0
    const val TRACK_INTERVAL_MS = 5_000L
    const val MAX_JUMP_SPEED_MPS = 200.0 / 3.6
    const val PATH_TOLERANCE_M = 5.0
    const val GRID_SIZE_M = 100.0
    const val MIN_TRIP_DISTANCE_M = 200.0
}
