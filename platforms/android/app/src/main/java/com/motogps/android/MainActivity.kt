package com.motogps.android

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import com.motogps.android.ui.NavigationScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hide ActionBar programmatically if the base theme applies one
        actionBar?.hide()

        val sharedPrefs = getSharedPreferences("MotoGpsPrefs", Context.MODE_PRIVATE)
        val initialGateway = sharedPrefs.getString("gateway_address", "") ?: ""

        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                NavigationScreen(
                    initialGateway = initialGateway,
                    onGatewaySave = { newGateway ->
                        sharedPrefs.edit().putString("gateway_address", newGateway).apply()
                    }
                )
            }
        }
    }
}
