package com.pleos.explorer.platform.map

import com.pleos.explorer.di.ExplorerApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ExplorerApplication::class)
class KakaoMapCacheTest {
    @Test fun unopenedSdkCanDeferCacheCleanupWithoutNativeCallsOrAppKey() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        MapCacheCleaner(context).clear()
        assertTrue(context.getSharedPreferences("kakao_map_cache", 0).getBoolean("clear_pending", false))
    }
}
