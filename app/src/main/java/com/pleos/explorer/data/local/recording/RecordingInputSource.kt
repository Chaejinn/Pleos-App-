package com.pleos.explorer.data.local.recording

enum class RecordingInputSource(val label: String) {
    REPLAY("합성 경로 재생"),
    ANDROID_GPS("Android 위치 입력"),
    UNKNOWN("입력 종류 미확인"),
}
