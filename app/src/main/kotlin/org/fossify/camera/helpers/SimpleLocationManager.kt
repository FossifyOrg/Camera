package org.fossify.camera.helpers

import android.Manifest
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.annotation.RequiresPermission
import org.fossify.camera.extensions.checkLocationPermission
import org.fossify.commons.activities.BaseSimpleActivity

class SimpleLocationManager(private val activity: BaseSimpleActivity) {

    companion object {
        private const val LOCATION_UPDATE_MIN_TIME_INTERVAL_MS = 5000L
        private const val LOCATION_UPDATE_MIN_DISTANCE_M = 10F
    }

    private var location: Location? = null

    private val locationManager = activity.getSystemService(LocationManager::class.java)!!
    private val locationListener = object: LocationListener {
        override fun onLocationChanged(location: Location) {
            this@SimpleLocationManager.location = location
        }

        // No-op methods that must be overridden.
        // See https://github.com/FossifyOrg/Camera/issues/177
        @Suppress("DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun getLocation(): Location? {
        if (location == null) {
            location = getLastKnownLocation()
        }

        return location
    }

    private fun getLastKnownLocation(): Location? {
        return if (activity.checkLocationPermission()) {
            var accurateLocation: Location? = null
            for (provider in locationManager.allProviders) {
                val location = locationManager.getLastKnownLocation(provider) ?: continue
                if (accurateLocation == null || location.accuracy < accurateLocation.accuracy) {
                    accurateLocation = location
                }
            }
            accurateLocation
        } else {
            null
        }
    }

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun requestLocationUpdates() {
        locationManager.allProviders.forEach { provider ->
            locationManager.requestLocationUpdates(
                provider,
                LOCATION_UPDATE_MIN_TIME_INTERVAL_MS,
                LOCATION_UPDATE_MIN_DISTANCE_M,
                locationListener
            )
        }
    }

    fun dropLocationUpdates() {
        locationManager.removeUpdates(locationListener)
    }
}
