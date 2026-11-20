package com.motogps.android.location

import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class LocationForegroundServiceTest {

    private class TestableLocationForegroundService(
        private val mockContext: Context,
        var mockTimeMs: Long = 10_000L,
        var mockTimeNanos: Long = 10_000_000_000L
    ) : LocationForegroundService() {
        init {
            try {
                attachBaseContext(mockContext)
            } catch (_: Exception) {}
        }

        override fun currentElapsedRealtime(): Long = mockTimeMs
        override fun currentElapsedRealtimeNanos(): Long = mockTimeNanos

        override fun getSystemService(name: String): Any? {
            return mockContext.getSystemService(name)
        }

        override fun getApplicationContext(): Context {
            return mockContext
        }

        override fun getPackageName(): String {
            return mockContext.packageName ?: "com.motogps.android"
        }
    }

    @Test
    fun onStartCommandExecutesWithoutWakeLockPermissionSecurityException() {
        val context = mock(Context::class.java)
        val locationManager = mock(LocationManager::class.java)
        `when`(context.getSystemService(Context.POWER_SERVICE)).thenThrow(
            SecurityException("Neither user 10677 nor current process has android.permission.WAKE_LOCK.")
        )
        `when`(context.getSystemService(Context.LOCATION_SERVICE)).thenReturn(locationManager)
        `when`(context.packageName).thenReturn("com.motogps.android")

        val service = TestableLocationForegroundService(context)
        try {
            val result = service.onStartCommand(Intent(), 0, 1)
            assertEquals(Service.START_STICKY, result)
        } catch (e: SecurityException) {
            fail("onStartCommand threw SecurityException when WAKE_LOCK is absent: ${e.message}")
        }
    }

    @Test
    fun serviceDoesNotRetainOrUseWakeLockOnDestroy() {
        val context = mock(Context::class.java)
        `when`(context.getSystemService(Context.POWER_SERVICE)).thenThrow(
            SecurityException("Neither user 10677 nor current process has android.permission.WAKE_LOCK.")
        )
        val service = TestableLocationForegroundService(context)
        try {
            service.onDestroy()
        } catch (e: SecurityException) {
            fail("onDestroy threw SecurityException when WAKE_LOCK is absent: ${e.message}")
        }
    }

    @Test
    fun onCreateInitializesWithoutWakeLock() {
        val context = mock(Context::class.java)
        `when`(context.getSystemService(Context.POWER_SERVICE)).thenThrow(
            SecurityException("Neither user 10677 nor current process has android.permission.WAKE_LOCK.")
        )
        val service = TestableLocationForegroundService(context)
        try {
            service.onCreate()
        } catch (e: SecurityException) {
            fail("onCreate threw SecurityException when WAKE_LOCK is absent: ${e.message}")
        }
    }
    @Test
    fun onProviderEnabledRegistersUpdates() {
        val context = mock(Context::class.java)
        val locationManager = mock(LocationManager::class.java)
        `when`(context.getSystemService(Context.LOCATION_SERVICE)).thenReturn(locationManager)
        `when`(context.packageName).thenReturn("com.motogps.android")

        val service = TestableLocationForegroundService(context)
        service.onCreate()

        service.onProviderEnabled(LocationManager.GPS_PROVIDER)
        org.mockito.Mockito.verify(locationManager).requestLocationUpdates(
            org.mockito.Mockito.eq(LocationManager.GPS_PROVIDER),
            org.mockito.Mockito.eq(200L),
            org.mockito.Mockito.eq(0f),
            org.mockito.Mockito.eq(service)
        )

        service.onProviderEnabled(LocationManager.NETWORK_PROVIDER)
        org.mockito.Mockito.verify(locationManager).requestLocationUpdates(
            org.mockito.Mockito.eq(LocationManager.NETWORK_PROVIDER),
            org.mockito.Mockito.eq(1000L),
            org.mockito.Mockito.eq(1f),
            org.mockito.Mockito.eq(service)
        )
    }

    @Test
    fun staleGpsLastKnownLocationDoesNotBlockNetworkFallback() {
        val context = mock(Context::class.java)
        val locationManager = mock(LocationManager::class.java)
        `when`(context.getSystemService(Context.LOCATION_SERVICE)).thenReturn(locationManager)
        `when`(context.packageName).thenReturn("com.motogps.android")

        val staleGps = mock(android.location.Location::class.java)
        `when`(staleGps.provider).thenReturn(LocationManager.GPS_PROVIDER)
        `when`(staleGps.time).thenReturn(1_000L)
        // 10 seconds old relative to mockTimeNanos
        `when`(staleGps.elapsedRealtimeNanos).thenReturn(0L)

        `when`(locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)).thenReturn(staleGps)
        `when`(locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)).thenReturn(null)

        var dispatchedLocation: android.location.Location? = null
        LocationForegroundService.onLocationUpdate = { dispatchedLocation = it }

        val service = TestableLocationForegroundService(context, mockTimeMs = 10_000L, mockTimeNanos = 10_000_000_000L)
        service.onCreate()
        service.onStartCommand(Intent(), 0, 1)

        val netLocation = mock(android.location.Location::class.java)
        `when`(netLocation.provider).thenReturn(LocationManager.NETWORK_PROVIDER)

        dispatchedLocation = null
        service.onLocationChanged(netLocation)
        assertEquals(netLocation, dispatchedLocation)

        LocationForegroundService.onLocationUpdate = null
    }

    @Test
    fun recentGpsLastKnownLocationBlocksNetworkFallbackTemporarily() {
        val context = mock(Context::class.java)
        val locationManager = mock(LocationManager::class.java)
        `when`(context.getSystemService(Context.LOCATION_SERVICE)).thenReturn(locationManager)
        `when`(context.packageName).thenReturn("com.motogps.android")

        val recentGps = mock(android.location.Location::class.java)
        `when`(recentGps.provider).thenReturn(LocationManager.GPS_PROVIDER)
        `when`(recentGps.time).thenReturn(9_000L)
        // 1 second old relative to mockTimeNanos (10s - 1s = 9s)
        `when`(recentGps.elapsedRealtimeNanos).thenReturn(9_000_000_000L)

        `when`(locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)).thenReturn(recentGps)
        `when`(locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)).thenReturn(null)

        var dispatchedLocation: android.location.Location? = null
        LocationForegroundService.onLocationUpdate = { dispatchedLocation = it }

        val service = TestableLocationForegroundService(context, mockTimeMs = 10_000L, mockTimeNanos = 10_000_000_000L)
        service.onCreate()
        service.onStartCommand(Intent(), 0, 1)

        val netLocation = mock(android.location.Location::class.java)
        `when`(netLocation.provider).thenReturn(LocationManager.NETWORK_PROVIDER)

        dispatchedLocation = null
        service.onLocationChanged(netLocation)
        assertEquals(null, dispatchedLocation)

        LocationForegroundService.onLocationUpdate = null
    }
}
