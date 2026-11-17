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

    private class TestableLocationForegroundService(private val mockContext: Context) : LocationForegroundService() {
        init {
            try {
                attachBaseContext(mockContext)
            } catch (_: Exception) {}
        }

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
}
