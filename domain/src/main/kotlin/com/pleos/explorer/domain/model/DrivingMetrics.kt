package com.pleos.explorer.domain.model

data class MetricsSample(
    val position: TrackPosition,
    val harshBrake: HarshBrakeUpdate? = null,
    val harshSteer: HarshSteerUpdate? = null,
    val turnSignal: TurnSignalUpdate? = null,
    val efficiency: EfficiencySample? = null,
)

data class DrivingMetrics(
    val id: String,
    val tripId: String,
    val segmentIndex: Int,
    val startPointId: String?,
    val pathSegmentId: String,
    val startDistanceM: Double,
    val endDistanceM: Double,
    val startElapsedMs: Long,
    val endElapsedMs: Long,
    val harshBrakes: Int?,
    val harshSteers: Int?,
    val turnSignalRate: Double?,
    val avgEfficiency: Efficiency?,
    val turnCount: Int?,
    val signaledTurnCount: Int?,
)
