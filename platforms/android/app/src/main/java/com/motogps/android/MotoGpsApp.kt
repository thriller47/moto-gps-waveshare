package com.motogps.android

import android.app.Application
import android.content.Context
import org.osmdroid.config.Configuration

class MotoGpsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            Configuration.getInstance().userAgentValue = packageName
            Configuration.getInstance().load(this, getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE))
        } catch (_: Exception) {}
    }
}
