package com.pleos.explorer.platform.map

enum class KakaoMapAvailability { READY, MISSING_KEY, UNSUPPORTED_ABI }

fun kakaoMapAvailability(key: String, abis: List<String>): KakaoMapAvailability = when {
    key.isBlank() -> KakaoMapAvailability.MISSING_KEY
    abis.none { it == "arm64-v8a" || it == "armeabi-v7a" } -> KakaoMapAvailability.UNSUPPORTED_ABI
    else -> KakaoMapAvailability.READY
}
