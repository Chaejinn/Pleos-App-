package com.pleos.explorer.platform.speech

/** 호출 간격은 수신 공백과 달력 변경의 영향을 받지 않는 단조 시계로 계산한다. */
class SpeechGate(private val minIntervalMs: Long = 60_000) {
    private var lastSpokenAtMs: Long? = null

    fun allow(nowElapsedMs: Long, consent: Boolean, enabled: Boolean, driving: Boolean): Boolean {
        if (!consent || !enabled || !driving) return false
        val previous = lastSpokenAtMs
        if (previous != null && nowElapsedMs - previous < minIntervalMs) return false
        lastSpokenAtMs = nowElapsedMs
        return true
    }
}
