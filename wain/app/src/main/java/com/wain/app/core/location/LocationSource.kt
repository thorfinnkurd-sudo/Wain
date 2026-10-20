package com.wain.app.core.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.wain.app.core.geo.LatLon
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

interface LocationSource {
    /** Emits real device fixes. Requires location permission; throws SecurityException otherwise. */
    fun fixes(intervalMs: Long): Flow<LocationFix>
}

internal fun Location.toFix(): LocationFix = LocationFix(
    position = LatLon(latitude, longitude),
    accuracyM = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
    speedMps = if (hasSpeed()) speed else null,
    speedAccuracyMps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
    bearingDeg = if (hasBearing()) bearing else null,
    bearingAccuracyDeg = if (hasBearingAccuracy()) bearingAccuracyDegrees else null,
    elapsedRealtimeMs = elapsedRealtimeNanos / 1_000_000L,
    provider = provider ?: "unknown",
)

/** Google Play services fused provider (best accuracy and battery behaviour where available). */
class FusedLocationSource(private val context: Context) : LocationSource {
    @SuppressLint("MissingPermission")
    override fun fixes(intervalMs: Long): Flow<LocationFix> = callbackFlow {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (l in result.locations) trySend(l.toFix())
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }
}

/** Fallback using the platform LocationManager (GPS provider) for devices without Play services. */
class PlatformLocationSource(private val context: Context) : LocationSource {
    @SuppressLint("MissingPermission")
    override fun fixes(intervalMs: Long): Flow<LocationFix> = callbackFlow {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location.toFix())
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { lm.isProviderEnabled(it) }
        for (p in providers) lm.requestLocationUpdates(p, intervalMs, 0f, listener, Looper.getMainLooper())
        awaitClose { lm.removeUpdates(listener) }
    }
}

object LocationSourceFactory {
    fun create(context: Context): LocationSource {
        val available = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        return if (available == ConnectionResult.SUCCESS) FusedLocationSource(context)
        else PlatformLocationSource(context)
    }
}
