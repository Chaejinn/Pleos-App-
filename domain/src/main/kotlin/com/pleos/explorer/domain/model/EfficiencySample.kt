package com.pleos.explorer.domain.model

data class EfficiencySample(
    val distanceM: Observation<Double?>,
    val consumption: Observation<Double?>,
    val unit: EfficiencyUnit,
)
