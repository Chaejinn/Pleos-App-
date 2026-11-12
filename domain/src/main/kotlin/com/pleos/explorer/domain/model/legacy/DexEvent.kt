package com.pleos.explorer.domain.model.legacy

sealed interface DexEvent {
    data class Collected(val landmark: Landmark) : DexEvent
    data class Revisited(val landmark: Landmark) : DexEvent
}
