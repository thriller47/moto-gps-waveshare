package com.motogps.android

import android.app.Application
import android.content.Context
import com.motogps.android.diagnostics.DiagnosticLog
import org.osmdroid.config.Configuration

class MotoGpsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.init(this)
        try {
            Configuration.getInstance().userAgentValue = packageName
            Configuration.getInstance().load(this, getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE))
        } catch (_: Exception) {}
    }
}
