package com.pleos.explorer.platform.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.pleos.explorer.domain.model.GeoPoint
import com.pleos.explorer.domain.model.LocationSample

/** Maps only documented Android fields; absent speed/accuracy/bearing remain null. */
fun Location.toRecordingSample() = LocationSample(
    timeMs = time,
    elapsedMs = elapsedRealtimeNanos / 1_000_000L,
    point = GeoPoint(latitude, longitude),
    speedMps = if (hasSpeed()) speed.toDouble() else null,
    bearingDeg = if (hasBearing()) bearing.toDouble() else null,
    accuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
)

/** GPS-only diagnostic input. Does not call lastKnownLocation or convert Pleos confidence. */
class AndroidGpsSubscription(private val context: Context) {
    private var manager: LocationManager? = null
    private var listener: LocationListener? = null
    private var generation = 0L

    fun start(onSample: (LocationSample) -> Unit, onFailure: (Exception) -> Unit) {
        stop()
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED) { "Precise location permission required" }
        val locations = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        check(LocationManager.GPS_PROVIDER in locations.allProviders &&
            locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) { "GPS provider unavailable" }
        val token = generation
        val callback = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (token == generation) onSample(location.toRecordingSample())
            }
            override fun onProviderDisabled(provider: String) {
                if (token == generation && provider == LocationManager.GPS_PROVIDER)
                    onFailure(IllegalStateException("GPS provider disabled"))
            }
            override fun onProviderEnabled(provider: String) = Unit
            @Deprecated("Legacy Android callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        manager = locations
        listener = callback
        try { locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, callback, Looper.getMainLooper()) }
        catch (error: Exception) { stop(); throw error }
    }

    fun stop() {
        generation++
        val callback = listener
        listener = null
        if (callback != null) runCatching { manager?.removeUpdates(callback) }
        manager = null
    }
}
