package com.pleos.explorer.demo

import androidx.lifecycle.ViewModel
import com.pleos.explorer.domain.engine.legacy.RoadDex
import com.pleos.explorer.domain.model.legacy.DexEvent
import com.pleos.explorer.domain.model.legacy.GearState
import com.pleos.explorer.domain.model.legacy.VehicleSignal
import com.pleos.explorer.domain.port.legacy.InMemoryDexStore
import com.pleos.explorer.platform.vehicle.DemoPoint
import com.pleos.explorer.platform.vehicle.DemoSignalSource
import com.pleos.explorer.platform.vehicle.SeoulDemoRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DemoState(
    val started: Boolean = false,
    val playing: Boolean = false,
    val completed: Boolean = false,
    val fraction: Float = 0f,
    val point: DemoPoint = SeoulDemoRoute.points.first(),
    val collected: Set<String> = emptySet(),
    val rewardId: String? = null,
    val completedRuns: Int = 0,
)

/** Session-only replay; never writes GPS, consent, trips or collection files. */
class DemoViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(DemoState())
    val state = mutableState.asStateFlow()
    private var source: DemoSignalSource? = null

    fun play() {
        if (state.value.started && !state.value.completed) {
            source?.paused = false
            mutableState.update { it.copy(playing = true) }
            return
        }
        source?.stop()
        val store = InMemoryDexStore()
        val dex = RoadDex(SeoulDemoRoute.landmarks, store)
        mutableState.value = DemoState(started = true, playing = true, completedRuns = state.value.completedRuns)
        source = DemoSignalSource(rough = false).also { input ->
            input.start { signal ->
                when (signal) {
                    is VehicleSignal.Location -> {
                        val reward = dex.onLocation(signal.lat, signal.lon, signal.timeMs)
                        mutableState.update {
                            it.copy(fraction = (signal.timeMs / 288_000f).coerceIn(0f, 1f),
                                point = DemoPoint(signal.lat, signal.lon, signal.bearingDeg),
                                collected = store.collectedIds().toSet(),
                                rewardId = (reward as? DexEvent.Collected)?.landmark?.id ?: it.rewardId)
                        }
                    }
                    is VehicleSignal.Gear -> if (signal.gear == GearState.PARK) {
                        mutableState.update { it.copy(playing = false, completed = true,
                            completedRuns = it.completedRuns + 1) }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun pause() {
        source?.paused = true
        mutableState.update { it.copy(playing = false) }
    }

    fun reset() {
        source?.stop()
        source = null
        mutableState.value = DemoState(completedRuns = state.value.completedRuns)
    }

    fun dismissReward() { mutableState.update { it.copy(rewardId = null) } }
    override fun onCleared() { source?.stop() }
}
