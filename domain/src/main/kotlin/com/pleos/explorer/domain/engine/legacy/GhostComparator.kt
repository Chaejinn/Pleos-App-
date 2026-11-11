package com.pleos.explorer.domain.engine.legacy

import com.pleos.explorer.domain.model.legacy.SegmentStats
import com.pleos.explorer.domain.model.legacy.Trip

/**
 * 같은 경로의 지난 주행(고스트)과 구간별로 비교해 짧은 중계 문장을 만든다.
 * 속도는 비교하지 않는다. 부드러움(급제동·급조향·깜빡이)과 효율만 본다.
 */
class GhostComparator(private val ghost: Trip) {

    fun commentFor(now: SegmentStats): String? {
        val before = ghost.segments.getOrNull(now.index) ?: return null

        val brakeDiff = before.harshBrakes - now.harshBrakes
        val steerDiff = before.harshSteers - now.harshSteers
        val effPct = efficiencyChangePct(before.avgEfficiency, now.avgEfficiency)

        return when {
            brakeDiff > 0 -> "지난번 이 구간보다 급제동이 ${brakeDiff}번 줄었어요."
            steerDiff > 0 -> "지난번보다 핸들을 ${steerDiff}번 덜 급하게 꺾었어요."
            effPct != null && effPct >= 5 -> "이 구간 연비가 지난번보다 ${effPct}% 좋아요."
            now.roughness == 0 && before.roughness == 0 -> null // 둘 다 깔끔하면 말하지 않는다
            now.roughness < before.roughness -> "지난번보다 부드럽게 달리고 있어요."
            now.roughness > before.roughness -> "이 구간은 지난번이 조금 더 부드러웠어요."
            else -> null
        }
    }

    /** 주행 전체 요약. 도착 리포트와 마지막 음성에 쓴다. */
    fun summary(trip: Trip): String {
        val nowRough = trip.segments.sumOf { it.roughness }
        val ghostRough = ghost.segments.take(trip.segments.size).sumOf { it.roughness }
        return when {
            nowRough < ghostRough -> "오늘은 지난번보다 거친 운전이 ${ghostRough - nowRough}번 줄었어요."
            nowRough == ghostRough -> "오늘도 지난번만큼 부드럽게 도착했어요."
            else -> "오늘은 지난번보다 ${nowRough - ghostRough}번 더 거칠었어요. 다음엔 이겨 봐요."
        }
    }

    private fun efficiencyChangePct(before: Float?, now: Float?): Int? {
        if (before == null || now == null || before <= 0f) return null
        return ((now - before) / before * 100).toInt()
    }
}
