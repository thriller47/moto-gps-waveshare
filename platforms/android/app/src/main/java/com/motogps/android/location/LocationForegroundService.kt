package com.motogps.android.location

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat

class LocationForegroundService : Service(), LocationListener {
    companion object {
        const val CHANNEL_ID = "MotoGPS_Location_Channel"
        const val NOTIFICATION_ID = 1001
        var onLocationUpdate: ((Location) -> Unit)? = null
    }

    // Request high-rate real GNSS fixes; Android may deliver fewer if the chip cannot.
    private val gpsIntervalMs = 200L
    private val networkFallbackIntervalMs = 1000L
    private lateinit var locationManager: LocationManager
    private var lastGpsFixTime = 0L

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Moto GPS")
            .setContentText("Servizio di localizzazione attivo")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        startLocationUpdates()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        try {
            // A START_STICKY service may receive onStartCommand repeatedly.
            // Keep only one pair of system listeners for each active service.
            locationManager.removeUpdates(this)
            val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            val bestLast = selectBestLocation(lastGps, lastNet)
            if (bestLast != null) {
                if (bestLast.provider == LocationManager.GPS_PROVIDER) {
                    lastGpsFixTime = System.currentTimeMillis()
                }
                onLocationUpdate?.invoke(bestLast)
            }

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    gpsIntervalMs,
                    0f,
                    this
                )
            }

            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    networkFallbackIntervalMs,
                    1f,
                    this
                )
            }
        } catch (_: Exception) {}
    }

    private fun selectBestLocation(gps: Location?, net: Location?): Location? {
        if (gps == null) return net
        if (net == null) return gps
        // Prefer GPS unless network is significantly fresher and GPS is stale (> 60s)
        val timeDiff = net.time - gps.time
        if (timeDiff > 60_000L) {
            return net
        }
        if (gps.hasAccuracy() && net.hasAccuracy() && gps.accuracy <= net.accuracy) {
            return gps
        }
        return if (gps.time >= net.time) gps else net
    }

    override fun onLocationChanged(location: Location) {
        if (location.provider == LocationManager.GPS_PROVIDER) {
            lastGpsFixTime = System.currentTimeMillis()
            onLocationUpdate?.invoke(location)
        } else {
            // Only use network fallback if no GPS fix received in the last 5 seconds
            val now = System.currentTimeMillis()
            if (now - lastGpsFixTime > 5_000L) {
                onLocationUpdate?.invoke(location)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Moto GPS Navigation Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
