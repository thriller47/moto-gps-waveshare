package com.motogps.android.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

class LocationForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "moto_gps_nav_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.motogps.android.action.STOP"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // FOREGROUND_SERVICE_TYPE_LOCATION
                serviceType = serviceType or 8
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                serviceType = serviceType or 16
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Navigation Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active navigation connection and location tracking status"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, LocationForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStopIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("MOTO GPS Navigation")
            .setContentText("Navigating and connected to display")
            .setOngoing(true)
            .addAction(0, "End Navigation", pendingStopIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
