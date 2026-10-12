package com.vtrack.app.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.vtrack.app.domain.model.GpsPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Platform GPS tracking (no Google Play Services dependency, so it also works on
 * de-Googled devices).
 *
 * Signal hygiene that matters for a usable route:
 *  - Points with accuracy worse than [MAX_ACCURACY_METERS] are dropped.
 *  - Points that imply an impossible speed (> [MAX_SPEED_MPS]) are dropped as GPS noise.
 *  - Updates are requested at a modest interval/distance so the battery cost of a long
 *    workout stays reasonable; move more than [MIN_DISTANCE_METERS] or wait
 *    [MIN_TIME_MS] for the next fix.
 */
@Singleton
class LocationTracker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var listener: LocationListener? = null
    private var lastAccepted: Location? = null
    private var onPoint: ((GpsPoint) -> Unit)? = null

    val isGpsEnabled: Boolean
        get() = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Starts updates. Returns false when permission or hardware is missing so the caller
     * can degrade to step-derived distance instead of pretending to track a route.
     */
    @SuppressLint("MissingPermission")
    fun start(onPoint: (GpsPoint) -> Unit, onProviderDisabled: () -> Unit = {}): Boolean {
        val manager = locationManager ?: return false
        if (!hasPermission()) return false
        stop()
        this.onPoint = onPoint
        lastAccepted = null

        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) = accept(location)

            @Deprecated("Deprecated in API 29+ but still delivered on older devices")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) {
                if (provider == LocationManager.GPS_PROVIDER) onProviderDisabled()
            }
        }
        listener = locationListener

        val providers = buildList {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            // Fused/network gives a faster first fix; GPS refines it.
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }
        if (providers.isEmpty()) return false

        var registered = false
        providers.forEach { provider ->
            runCatching {
                manager.requestLocationUpdates(
                    provider,
                    MIN_TIME_MS,
                    MIN_DISTANCE_METERS,
                    locationListener,
                    android.os.Looper.getMainLooper(),
                )
                registered = true
            }
        }

        // Seed with the last known fix so the map/route starts immediately.
        runCatching {
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { accept(it) }
        }
        return registered
    }

    fun stop() {
        listener?.let { l -> runCatching { locationManager?.removeUpdates(l) } }
        listener = null
        onPoint = null
    }

    private fun accept(location: Location) {
        if (location.accuracy > MAX_ACCURACY_METERS) return
        val previous = lastAccepted
        if (previous != null) {
            val dtSeconds = (location.time - previous.time) / 1000.0
            val distance = previous.distanceTo(location).toDouble()
            if (dtSeconds > 0.5 && distance / dtSeconds > MAX_SPEED_MPS) return
            if (location.time - previous.time < MIN_TIME_MS / 2) return
        }
        lastAccepted = location
        onPoint?.invoke(
            GpsPoint(
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                accuracyMeters = location.accuracy,
                speedMps = if (location.hasSpeed()) location.speed else 0f,
                recordedAt = location.time,
            ),
        )
    }

    companion object {
        const val MIN_TIME_MS = 3_000L
        const val MIN_DISTANCE_METERS = 5f
        const val MAX_ACCURACY_METERS = 45f

        /** Faster than any human over ground — treated as noise. */
        const val MAX_SPEED_MPS = 12.0
    }
}
