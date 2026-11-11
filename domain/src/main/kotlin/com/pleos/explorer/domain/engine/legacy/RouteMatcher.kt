package com.pleos.explorer.domain.engine.legacy

import com.pleos.explorer.domain.geo.legacy.RouteFingerprint

object RouteMatcher {
    /** 두 경로가 지나간 칸 집합의 겹침 비율(자카드). 0~1. */
    fun similarity(a: RouteFingerprint, b: RouteFingerprint): Double {
        val sa = a.cells.toSet()
        val sb = b.cells.toSet()
        if (sa.isEmpty() || sb.isEmpty()) return 0.0
        return sa.intersect(sb).size.toDouble() / sa.union(sb).size
    }

    /** 이전 주행들 중 현재 경로와 가장 비슷한 것을 찾는다. 기준 미만이면 null(새로운 길). */
    fun <T> bestMatch(current: RouteFingerprint, history: List<Pair<T, RouteFingerprint>>, threshold: Double = 0.6): T? =
        history.map { it.first to similarity(current, it.second) }
            .filter { it.second >= threshold }
            .maxByOrNull { it.second }
            ?.first
}
