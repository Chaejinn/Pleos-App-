package com.pleos.explorer.domain.model

enum class TripStatus { ACTIVE, FINISHED, DISCARDED }

data class ExploredSection(val gridKey: String, val from: GeoPoint, val to: GeoPoint)

data class RecordingSnapshot(
    val trip: Trip,
    val sessionId: String,
    val revision: Long = 0,
    val lastInputSequence: Long = -1,
    val status: TripStatus = TripStatus.ACTIVE,
    val previous: ValidatedLocation? = null,
    val lastStored: TrackPoint? = null,
    val lastStoredElapsedMs: Long? = null,
    val segmentId: String? = null,
    val nextPointSequence: Long = 0,
    val continuityBroken: Boolean = true,
    val pendingRoads: Map<String, ExploredRoad> = emptyMap(),
    val newGridKeys: Set<String> = emptySet(),
    val creditedSections: Map<String, List<ExploredSection>> = emptyMap(),
)

data class TrackUpdate(
    val snapshot: RecordingSnapshot,
    val points: List<TrackPoint> = emptyList(),
    val roads: Map<String, ExploredRoad> = emptyMap(),
    val connectedDistanceM: Double = 0.0,
    val newSegment: Boolean = false,
    val validation: LocationValidation? = null,
)
