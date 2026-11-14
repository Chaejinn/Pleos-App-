package com.pleos.explorer.platform.map

import org.junit.Assert.*
import org.junit.Test

class KakaoMapAvailabilityTest {
    @Test fun noKeyCannotStartSdkEvenOnArm() {
        assertEquals(KakaoMapAvailability.MISSING_KEY, kakaoMapAvailability("", listOf("arm64-v8a")))
        assertEquals(KakaoMapAvailability.MISSING_KEY, kakaoMapAvailability("  ", listOf("x86_64")))
    }
    @Test fun x86CannotLoadArmOnlyKakaoNativeLibrary() {
        assertEquals(KakaoMapAvailability.UNSUPPORTED_ABI, kakaoMapAvailability("test", listOf("x86_64", "x86")))
        assertEquals(KakaoMapAvailability.UNSUPPORTED_ABI, kakaoMapAvailability("test", emptyList()))
    }
    @Test fun configuredArmDevicesMayAttemptAuthentication() {
        assertEquals(KakaoMapAvailability.READY, kakaoMapAvailability("test", listOf("arm64-v8a")))
        assertEquals(KakaoMapAvailability.READY, kakaoMapAvailability("test", listOf("armeabi-v7a")))
    }
}
