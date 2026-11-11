package com.pleos.explorer.data.repository

import android.content.Context
import com.pleos.explorer.di.ExplorerApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ExplorerApplication::class)
class LegacyDataDeletionTest {
    @Test fun allLegacyRecordsAreDeletedButSettingsAndUnrelatedFilesRemain() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val settings = PreferenceSettingsRepository(context, Dispatchers.Unconfined)
        settings.setRecordingConsent(true)
        settings.setTtsEnabled(true)
        context.filesDir.resolve("trips.json").writeText("[]")
        context.filesDir.resolve("dex.json").writeText("{}")
        context.filesDir.resolve("unrelated.txt").writeText("keep")
        LegacyReportRepository(context).deleteAll()
        assertFalse(context.filesDir.resolve("trips.json").exists())
        assertFalse(context.filesDir.resolve("dex.json").exists())
        assertTrue(context.filesDir.resolve("unrelated.txt").exists())
        val restored = PreferenceSettingsRepository(context, Dispatchers.Unconfined)
        assertTrue(restored.settings.value.recordingConsentGranted)
        assertTrue(restored.settings.value.ttsEnabled)
    }
}
