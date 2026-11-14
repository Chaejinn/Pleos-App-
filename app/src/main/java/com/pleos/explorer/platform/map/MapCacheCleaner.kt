package com.pleos.explorer.platform.map

import android.content.Context
import com.kakao.vectormap.KakaoMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A closed SDK has no public cache-delete handle; defer to the next authorized map start. */
object KakaoMapCache {
    private val active = mutableSetOf<KakaoMap>()
    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences("kakao_map_cache", Context.MODE_PRIVATE)

    fun ready(context: Context, map: KakaoMap) {
        if (preferences(context).getBoolean("clear_pending", false)) {
            map.clearAllCache()
            check(preferences(context).edit().putBoolean("clear_pending", false).commit())
        }
        active.add(map)
    }
    fun release(context: Context, map: KakaoMap) {
        try { map.clearAllCache() }
        catch (_: Exception) { preferences(context).edit().putBoolean("clear_pending", true).apply() }
        finally { active.remove(map) }
    }
    suspend fun clear(context: Context): Unit = withContext(Dispatchers.Main.immediate) {
        check(preferences(context).edit().putBoolean("clear_pending", true).commit())
        if (active.isNotEmpty()) {
            active.toList().forEach { it.clearAllCache() }
            check(preferences(context).edit().putBoolean("clear_pending", false).commit())
        }
    }
}

class MapCacheCleaner(private val context: Context) {
    suspend fun clear() = KakaoMapCache.clear(context)
}
