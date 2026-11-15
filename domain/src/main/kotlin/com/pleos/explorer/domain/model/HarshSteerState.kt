package com.pleos.explorer.domain.model

data class HarshSteerState(
    val previous: Observation<Double?>? = null,
    val active: Boolean = false,
) {
    init {
        require(previous == null || previous.observedAtElapsedMs >= 0)
        require(previous?.value?.isFinite() != false)
        require(!active || previous?.value != null)
    }
}

data class HarshSteerUpdate(
    val state: HarshSteerState,
    val angularVelocityDegPerSec: Double? = null,
    val started: Boolean = false,
)
