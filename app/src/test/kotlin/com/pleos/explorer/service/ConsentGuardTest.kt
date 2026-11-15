package com.pleos.explorer.service

import android.content.Intent
import com.pleos.explorer.di.ExplorerApplication
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf
import android.Manifest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ExplorerApplication::class)
class ConsentGuardTest {
    @Test fun vehicleModeOnPhoneDoesNotCreateRecordingDatabase() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as ExplorerApplication
        app.graph.settingsRepository.setRecordingConsent(true)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val intent = Intent(app,RoadLogService::class.java).putExtra(RoadLogService.EXTRA_MODE,RoadLogService.MODE_VEHICLE)
        val controller = Robolectric.buildService(RoadLogService::class.java,intent).create().startCommand(0,1)
        assertFalse(app.graph.driveStatus.running)
        assertFalse(app.getDatabasePath("recording-v1.db").exists())
        controller.destroy()
        Unit
    }
    @Test fun lifecycleReplayWithoutConsentDoesNotCreateDatabase() {
        val app = RuntimeEnvironment.getApplication() as ExplorerApplication
        val intent = Intent(app, RoadLogService::class.java)
            .putExtra(RoadLogService.EXTRA_MODE, RoadLogService.MODE_LIFECYCLE_REPLAY)
        val controller = Robolectric.buildService(RoadLogService::class.java,intent).create().startCommand(0,1)
        assertFalse(app.graph.driveStatus.running)
        assertFalse(app.getDatabasePath("recording-v1.db").exists())
        controller.destroy()
    }
    @Test fun trackReplayWithoutConsentDoesNotCreateDatabase() {
        val app = RuntimeEnvironment.getApplication() as ExplorerApplication
        val intent = Intent(app, RoadLogService::class.java)
            .putExtra(RoadLogService.EXTRA_MODE, RoadLogService.MODE_TRACK_REPLAY)
        val controller = Robolectric.buildService(RoadLogService::class.java, intent).create().startCommand(0, 1)
        assertFalse(app.graph.driveStatus.running)
        assertFalse(app.getDatabasePath("recording-v1.db").exists())
        controller.destroy()
    }
    @Test fun directlyStartingServiceWithoutConsentDoesNotRecord() {
        val app = RuntimeEnvironment.getApplication() as ExplorerApplication
        val intent = Intent(app, RoadLogService::class.java)
            .putExtra(RoadLogService.EXTRA_MODE, RoadLogService.MODE_DEMO_ROUGH)
        val controller = Robolectric.buildService(RoadLogService::class.java, intent).create().startCommand(0, 1)
        assertFalse(app.graph.driveStatus.running)
        assertFalse(app.filesDir.resolve("trips.json").exists())
        assertFalse(app.filesDir.resolve("dex.json").exists())
        controller.destroy()
    }
}
