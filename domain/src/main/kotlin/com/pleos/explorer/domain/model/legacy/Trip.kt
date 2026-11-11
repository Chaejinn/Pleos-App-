package com.pleos.explorer.domain.model.legacy

/** 구간(기본 1km) 하나의 운전 기록. 고스트 비교는 같은 번호의 구간끼리 한다. */
data class SegmentStats(
    val index: Int,
    val harshBrakes: Int = 0,
    val harshSteers: Int = 0,
    val turnsWithoutSignal: Int = 0,
    val avgEfficiency: Float? = null,
) {
    val roughness: Int get() = harshBrakes + harshSteers + turnsWithoutSignal
}

data class Trip(
    val id: String,
    val startedAtMs: Long,
    val route: List<Cell>,
    val segments: List<SegmentStats>,
)
