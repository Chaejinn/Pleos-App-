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
    const val REGION_VISIT_DISTANCE_M = 1_000.0
    const val REGION_VISIT_DWELL_MS = 180_000L
    const val GHOST_DISTANCE_M = 30.0
    const val GHOST_BEARING_DEG = 45.0
    const val GHOST_START_DISTANCE_M = 500.0
    const val GHOST_EXIT_DISTANCE_M = 200.0
    const val TITLE_REGION_COUNT = 30
    const val TITLE_REVISIT_DISTANCE_M = 5_000.0
    const val TITLE_REVISIT_AGE_MS = 30L * 24 * 60 * 60 * 1_000
    const val VEHICLE_OBSERVATION_MAX_AGE_MS = 5_000L
    const val TRIP_START_SPEED_MPS = 5.0 / 3.6
    const val TRIP_START_DURATION_MS = 30_000L
    const val TRIP_STOP_DURATION_MS = 300_000L
    const val PARKED_FALLBACK_DURATION_MS = 10_000L
}
