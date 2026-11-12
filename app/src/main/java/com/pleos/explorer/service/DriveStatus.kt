package com.pleos.explorer.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DriveUiState(
    val running: Boolean = false,
    val deleting: Boolean = false,
    val finishing: Boolean = false,
    val hasDeparted: Boolean = false,
    val mode: String? = null,
    val message: String = "주행을 시작해 주세요",
    val channels: Map<String, String> = emptyMap(),
)

class DriveStatus {
    private val mutableState = MutableStateFlow(DriveUiState())
    val state = mutableState.asStateFlow()
    var deleting: Boolean
        get() = state.value.deleting
        internal set(value) { mutableState.update { it.copy(deleting = value) } }
    var running: Boolean
        get() = state.value.running
        internal set(value) { mutableState.update { it.copy(running = value) } }
    var finishing: Boolean
        get() = state.value.finishing
        internal set(value) { mutableState.update { it.copy(finishing = value) } }
    var hasDeparted: Boolean
        get() = state.value.hasDeparted
        internal set(value) { mutableState.update { it.copy(hasDeparted = value) } }
    var mode: String?
        get() = state.value.mode
        internal set(value) { mutableState.update { it.copy(mode = value) } }
    var message: String
        get() = state.value.message
        internal set(value) { mutableState.update { it.copy(message = value) } }
    fun clearChannels() { mutableState.update { it.copy(channels = emptyMap()) } }
    fun updateChannel(channel: String, message: String) {
        mutableState.update { it.copy(channels = it.channels + (channel to message)) }
    }
}
