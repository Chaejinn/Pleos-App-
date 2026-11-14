package com.pleos.explorer.data.repository

import com.pleos.explorer.di.ExplorerApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ExplorerApplication::class)
class MapPreferencesTest {
    @Test fun previousProviderConsentDoesNotEnableKakaoAutomatically() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("map_preferences", 0).edit().putBoolean("online_map", true).commit()
        assertFalse(MapPreferences(context).enabled.value)
    }
    @Test fun onlineMapIsOptInAndChoiceSurvivesRepositoryRecreation() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val preferences = MapPreferences(context)
        assertFalse(preferences.enabled.value)
        preferences.setEnabled(true)
        assertTrue(MapPreferences(context).enabled.value)
        preferences.setEnabled(false)
        assertFalse(MapPreferences(context).enabled.value)
    }
}
