package com.pleos.explorer.data.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Online tile access is optional and off until the user enables it. */
class MapPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("map_preferences", Context.MODE_PRIVATE)
    private val changes = Mutex()
    private val value = MutableStateFlow(preferences.getBoolean("online_map_kakao_v1", false))
    val enabled = value.asStateFlow()
    suspend fun setEnabled(enabled: Boolean) = changes.withLock {
        withContext(Dispatchers.IO) {
            check(preferences.edit().putBoolean("online_map_kakao_v1", enabled).commit()) { "Map preference not saved" }
        }
        value.value = enabled
    }
}
