package com.pleos.explorer.domain.model

data class UserSettings(
    val recordingConsentGranted: Boolean = false,
    val ttsEnabled: Boolean = false,
)
