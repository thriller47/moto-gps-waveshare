package com.motogps.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.motogps.android.jni.MotoBLEProtocolCodec
import com.motogps.android.location.LocationForegroundService

class MainActivity : ComponentActivity() {

    private var bleStatus by mutableStateOf("Idle/Disconnected")
    private var isNavigating by mutableStateOf(false)
    private var serviceError by mutableStateOf<String?>(null)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            bleStatus = "Permissions Granted."
            serviceError = null
        } else {
            bleStatus = "Permissions Denied. Core functionality restricted."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        var loadError: String? = null
        try {
            if (!MotoBLEProtocolCodec.isLibraryLoaded) {
                loadError = MotoBLEProtocolCodec.libraryLoadError?.message ?: "Unknown JNI error"
            }
        } catch (t: Throwable) {
            loadError = t.message ?: t.toString()
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    NavigationUI(
                        context = this,
                        loadError = loadError,
                        bleStatus = bleStatus,
                        serviceError = serviceError,
                        isNavigating = isNavigating,
                        onAction = { action, gatewayUrl -> handleAction(action, gatewayUrl) }
                    )
                }
            }
        }

        checkPermissions()
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun checkPermissions() {
        if (!hasRequiredPermissions()) {
            val permissions = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            requestPermissionLauncher.launch(permissions.toTypedArray())
        } else {
            bleStatus = "Permissions Granted."
        }
    }

    private fun handleAction(action: String, gatewayUrl: String?) {
        when (action) {
            "START_NAV" -> {
                if (!hasRequiredPermissions()) {
                    serviceError = "Cannot start: Missing required permissions."
                    checkPermissions()
                    return
                }

                if (gatewayUrl != null) {
                    val prefs = getSharedPreferences("MotoGPSPrefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("saved_gateway_url", gatewayUrl).apply()
                }

                try {
                    val intent = Intent(applicationContext, LocationForegroundService::class.java).apply {
                        this.action = LocationForegroundService.ACTION_TEST_START
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        applicationContext.startForegroundService(intent)
                    } else {
                        applicationContext.startService(intent)
                    }
                    isNavigating = true
                    serviceError = null
                } catch (e: SecurityException) {
                    isNavigating = false
                    serviceError = "Security Exception: ${e.message}"
                } catch (e: IllegalArgumentException) {
                    isNavigating = false
                    serviceError = "Illegal Argument: ${e.message}"
                } catch (e: Exception) {
                    isNavigating = false
                    serviceError = "Startup Error: ${e.message}"
                }
            }
            "STOP_NAV" -> {
                try {
                    val intent = Intent(applicationContext, LocationForegroundService::class.java).apply {
                        this.action = LocationForegroundService.ACTION_STOP
                    }
                    applicationContext.startService(intent)
                } catch (e: Exception) {
                    // Just log or update status if needed
                } finally {
                    isNavigating = false
                }
            }
        }
    }
}

@Composable
fun NavigationUI(context: Context, loadError: String?, bleStatus: String, serviceError: String?, isNavigating: Boolean, onAction: (String, String?) -> Unit) {
    var gatewayUrl by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("MotoGPSPrefs", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("saved_gateway_url", "https://example.invalid/moto-gps/api/")
        if (savedUrl != null) {
            gatewayUrl = savedUrl
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp).fillMaxWidth()
        ) {
            Text(
                text = "Glimpse MOTO GPS",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (loadError != null) {
                Text(text = "Status: Native Library FAILED", color = Color.Red, fontWeight = FontWeight.Bold)
            } else {
                Text(text = "Status: Native Library Loaded", color = Color.Green, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Navigation Provider: OpenStreetMap (OSM)", color = Color.Cyan)

            if (serviceError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = serviceError, color = Color.Red, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(24.dp))

            TextField(
                value = gatewayUrl,
                onValueChange = { gatewayUrl = it },
                label = { Text("Gateway URL") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            TextField(
                value = destination,
                onValueChange = { destination = it },
                label = { Text("Destination Search") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (!isNavigating) {
                Button(onClick = { onAction("START_NAV", gatewayUrl) }) {
                    Text("Start Navigation")
                }
            } else {
                Text(text = "Navigation Active", color = Color.Yellow, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { onAction("STOP_NAV", null) }) {
                    Text("End Navigation")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(text = "GPS: Waiting for fix... (WGS84 strictly)", color = Color.LightGray, fontSize = 12.sp)
        }
    }
}
