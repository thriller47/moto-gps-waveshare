package com.motogps.android.location

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

open class LocationForegroundService : Service(), LocationListener {
    companion object {
        const val CHANNEL_ID = "MotoGPS_Location_Channel"
        const val NOTIFICATION_ID = 1001

        private val _locationFlow = MutableSharedFlow<Location>(
            replay = 1,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val locationFlow: SharedFlow<Location> = _locationFlow.asSharedFlow()

        var onLocationUpdate: ((Location) -> Unit)? = null
    }

    // Request high-rate real GNSS fixes; Android may deliver fewer if the chip cannot.
    private val gpsIntervalMs = 200L
    private val networkFallbackIntervalMs = 1000L
    private var locationManager: LocationManager? = null
    private var lastGpsFixTime = 0L

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        try {
            createNotificationChannel()
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Moto GPS")
                .setContentText("Servizio di localizzazione attivo")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                } catch (e: SecurityException) {
                    Log.w("LocationForegroundService", "SecurityException starting foreground with CONNECTED_DEVICE; falling back to LOCATION", e)
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    )
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("LocationForegroundService", "Failed to start foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }

        startLocationUpdates()
        return START_STICKY
    }

    private fun dispatchLocation(location: Location) {
        _locationFlow.tryEmit(location)
        onLocationUpdate?.invoke(location)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        try {
            val mgr = locationManager ?: (getSystemService(Context.LOCATION_SERVICE) as? LocationManager) ?: return
            // A START_STICKY service may receive onStartCommand repeatedly.
            // Keep only one pair of system listeners for each active service.
            mgr.removeUpdates(this)
            val lastGps = mgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNet = mgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            val bestLast = selectBestLocation(lastGps, lastNet)
            if (bestLast != null) {
                if (bestLast.provider == LocationManager.GPS_PROVIDER) {
                    val ageMs = (currentElapsedRealtimeNanos() - bestLast.elapsedRealtimeNanos) / 1_000_000L
                    if (ageMs in 0 until 5_000L) {
                        lastGpsFixTime = currentElapsedRealtime()
                    }
                }
                dispatchLocation(bestLast)
            }

            if (mgr.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mgr.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    gpsIntervalMs,
                    0f,
                    this
                )
            }

            if (mgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mgr.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    networkFallbackIntervalMs,
                    1f,
                    this
                )
            }
        } catch (e: Exception) {
            Log.w("LocationForegroundService", "Failed to register location updates", e)
        }
    }

    private fun selectBestLocation(gps: Location?, net: Location?): Location? {
        if (gps == null) return net
        if (net == null) return gps
        val gpsNs = gps.elapsedRealtimeNanos
        val netNs = net.elapsedRealtimeNanos
        val timeDiffMs = if (gpsNs > 0L && netNs > 0L) {
            (netNs - gpsNs) / 1_000_000L
        } else {
            net.time - gps.time
        }
        if (timeDiffMs > 60_000L) {
            return net
        }
        if (gps.hasAccuracy() && net.hasAccuracy() && gps.accuracy <= net.accuracy) {
            return gps
        }
        return if (timeDiffMs <= 0L) gps else net
    }

    override fun onLocationChanged(location: Location) {
        if (location.provider == LocationManager.GPS_PROVIDER) {
            lastGpsFixTime = currentElapsedRealtime()
            dispatchLocation(location)
        } else {
            // Only use network fallback if no GPS fix received in the last 5 seconds
            val now = currentElapsedRealtime()
            if (now - lastGpsFixTime > 5_000L) {
                dispatchLocation(location)
            }
        }
    }

    protected open fun currentElapsedRealtime(): Long = SystemClock.elapsedRealtime()
    protected open fun currentElapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    @SuppressLint("MissingPermission")
    override fun onProviderEnabled(provider: String) {
        try {
            val mgr = locationManager ?: (getSystemService(Context.LOCATION_SERVICE) as? LocationManager) ?: return
            when (provider) {
                LocationManager.GPS_PROVIDER -> {
                    mgr.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        gpsIntervalMs,
                        0f,
                        this
                    )
                }
                LocationManager.NETWORK_PROVIDER -> {
                    mgr.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        networkFallbackIntervalMs,
                        1f,
                        this
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("LocationForegroundService", "Failed to register updates onProviderEnabled for $provider", e)
        }
    }
    override fun onProviderDisabled(provider: String) {}

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            locationManager?.removeUpdates(this)
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
