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
    @Test fun deletingOneTripPreservesOtherTripsAndCollection() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        context.filesDir.resolve("trips.json").delete()
        val store = com.pleos.explorer.data.local.TripStore(context)
        store.add(com.pleos.explorer.domain.model.legacy.Trip("one", 1, emptyList(), emptyList()))
        store.add(com.pleos.explorer.domain.model.legacy.Trip("two", 2, emptyList(), emptyList()))
        org.junit.Assert.assertEquals("before deletion", listOf("one", "two"), store.all().map { it.id })
        context.filesDir.resolve("dex.json").writeText("{}")
        LegacyReportRepository(context).deleteTrip("one")
        org.junit.Assert.assertEquals(listOf("two"), com.pleos.explorer.data.local.TripStore(context).all().map { it.id })
        assertTrue(context.filesDir.resolve("dex.json").exists())
    }

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
