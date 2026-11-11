package com.pleos.explorer.domain.engine.legacy

import com.pleos.explorer.domain.geo.legacy.Geo
import com.pleos.explorer.domain.model.legacy.DexEvent
import com.pleos.explorer.domain.model.legacy.Landmark
import com.pleos.explorer.domain.port.legacy.DexStore

/**
 * 위치가 들어올 때마다 근처 명소를 확인한다.
 * 실제 앱에서는 FusedLocation의 구역 진입 알림으로 후보를 좁히고, 여기서 최종 판정한다.
 */
class RoadDex(private val landmarks: List<Landmark>, private val store: DexStore) {
    private val inside = mutableSetOf<String>()

    fun onLocation(lat: Double, lon: Double, timeMs: Long): DexEvent? {
        var result: DexEvent? = null
        for (lm in landmarks) {
            val isIn = Geo.distanceM(lat, lon, lm.lat, lm.lon) <= lm.radiusM
            if (isIn && lm.id !in inside) {
                inside += lm.id
                result = if (store.isCollected(lm.id)) {
                    DexEvent.Revisited(lm)
                } else {
                    store.markCollected(lm.id, timeMs)
                    DexEvent.Collected(lm)
                }
            } else if (!isIn) {
                inside -= lm.id
            }
        }
        return result
    }

    /** 지역별 완성률(0~100). */
    fun completion(region: String): Int {
        val inRegion = landmarks.filter { it.region == region }
        if (inRegion.isEmpty()) return 0
        val got = inRegion.count { store.isCollected(it.id) }
        return got * 100 / inRegion.size
    }

    companion object {
        fun announce(e: DexEvent): String = when (e) {
            is DexEvent.Collected -> "새로 수집: ${e.landmark.name}!" + (e.landmark.story?.let { " $it" } ?: "")
            is DexEvent.Revisited -> "${e.landmark.name}, 다시 만났네요."
        }
    }
}
