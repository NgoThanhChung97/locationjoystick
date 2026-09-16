package com.locationjoystick.core.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.locationjoystick.core.model.LatLng
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DeviceLocationProvider"

/**
 * Reads the device's real last-known GPS fix via [LocationManager] — used to seed the app's default
 * position (map camera, first spoof start) with wherever the phone actually is, instead of the
 * hardcoded `MapConstants.DEFAULT_LAT/LON` fallback.
 *
 * Returns `null` when location permission is missing, no provider has a cached fix, or the only
 * available fix came from a mock provider (this app or another spoofer) — the point is the real
 * location, so a stale mock fix must not masquerade as it.
 */
@Singleton
class DeviceLocationProvider
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        suspend fun lastKnownLocation(): LatLng? =
            withContext(Dispatchers.IO) {
                if (!hasLocationPermission()) return@withContext null
                val locationManager =
                    context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                        ?: return@withContext null
                try {
                    listOf(
                        LocationManager.GPS_PROVIDER,
                        LocationManager.NETWORK_PROVIDER,
                        LocationManager.PASSIVE_PROVIDER,
                    ).mapNotNull { provider ->
                        // getProviders/getLastKnownLocation can throw if a provider is absent on the device.
                        runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
                    }.filterNot { it.isFromMockProviderCompat() }
                        .maxByOrNull { it.time }
                        ?.let { LatLng(latitude = it.latitude, longitude = it.longitude) }
                } catch (e: SecurityException) {
                    Log.w(TAG, "No permission to read device location", e)
                    null
                }
            }

        private fun hasLocationPermission(): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        private fun Location.isFromMockProviderCompat(): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                isMock
            } else {
                @Suppress("DEPRECATION")
                isFromMockProvider
            }
    }
