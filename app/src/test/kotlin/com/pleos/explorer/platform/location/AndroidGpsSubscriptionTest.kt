package com.pleos.explorer.platform.location

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import com.pleos.explorer.di.ExplorerApplication
import com.pleos.explorer.domain.engine.LocationValidator
import com.pleos.explorer.domain.model.LocationRejection
import com.pleos.explorer.domain.model.LocationValidation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ExplorerApplication::class)
class AndroidGpsSubscriptionTest {
    private fun fix() = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = 36.0; longitude = 127.0
        time = 1_700_000_000_000L; elapsedRealtimeNanos = 4_000_000_000L
    }

    @Test fun absentTelemetryDoesNotInventSpeedBearingOrAccuracy() {
        val sample = fix().toRecordingSample()
        assertNull(sample.speedMps)
        assertNull(sample.bearingDeg)
        assertNull(sample.accuracyM)
        assertEquals(4_000L, sample.elapsedMs)
        val rejected = LocationValidator().validate(sample, null) as LocationValidation.Rejected
        assertEquals(LocationRejection.MISSING_ACCURACY, rejected.reason)
    }
    @Test fun meterAccuracyAndObservationTimeAreMappedWithoutVehicleSpeed() {
        val value = fix().apply { accuracy = 5f }
        val sample = value.toRecordingSample()
        assertEquals(5.0, sample.accuracyM!!, 0.0)
        assertEquals(value.time, sample.timeMs)
        assertNull(sample.speedMps)
        assertTrue(LocationValidator().validate(sample, null) is LocationValidation.Accepted)
    }
    @Test fun lowAccuracyRemainsRejectedAndSpeedIsAlreadyMetersPerSecond() {
        val sample = fix().apply { accuracy = 51f; speed = 10f; bearing = 359f }.toRecordingSample()
        assertEquals(10.0, sample.speedMps!!, 0.0)
        assertEquals(359.0, sample.bearingDeg!!, 0.0)
        assertEquals(LocationRejection.LOW_ACCURACY,
            (LocationValidator().validate(sample, null) as LocationValidation.Rejected).reason)
    }
    @Test fun restartDoesNotDuplicateCallbacksAndStopUnsubscribes() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val subscription = AndroidGpsSubscription(context)
        var samples = 0
        subscription.start({ samples++ }, { throw it })
        subscription.start({ samples++ }, { throw it })
        shadowOf(manager).simulateLocation(fix().apply { accuracy = 5f })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, samples)
        subscription.stop()
        shadowOf(manager).simulateLocation(fix().apply { accuracy = 5f; elapsedRealtimeNanos += 2_000_000_000L })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, samples)
    }
    @Test fun noPrecisePermissionDoesNotSubscribe() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val subscription = AndroidGpsSubscription(context)
        try { subscription.start({}, {}); fail("Missing permission accepted") }
        catch (_: IllegalStateException) { }
        subscription.stop()
    }
}
