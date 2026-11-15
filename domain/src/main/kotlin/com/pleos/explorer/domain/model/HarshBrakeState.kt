package com.pleos.explorer.domain.model

data class HarshBrakeState(
    val previous: Observation<Double?>? = null,
    val active: Boolean = false,
) {
    init {
        require(previous == null || previous.observedAtElapsedMs >= 0)
        require(previous?.value?.let { it.isFinite() && it >= 0 } != false)
        require(!active || previous?.value != null)
    }
}

data class HarshBrakeUpdate(
    val state: HarshBrakeState,
    val decelerationMps2: Double? = null,
    val started: Boolean = false,
)
