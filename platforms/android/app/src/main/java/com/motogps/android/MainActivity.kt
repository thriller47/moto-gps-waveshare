package com.motogps.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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
                        loadError = loadError,
                        bleStatus = bleStatus,
                        serviceError = serviceError,
                        isNavigating = isNavigating,
                        onAction = { action -> handleAction(action) }
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

    private fun handleAction(action: String) {
        when (action) {
            "START_NAV" -> {
                // Pure UI Simulation Mode
                isNavigating = true
                serviceError = "STATUS: Simulation Active - UI Thread Intact"
            }
            "STOP_NAV" -> {
                isNavigating = false
                serviceError = null
            }
            "TEST_SERVICE" -> {
                if (!hasRequiredPermissions()) {
                    serviceError = "Cannot start: Missing required permissions."
                    checkPermissions()
                    return
                }

                try {
                    val intent = Intent(this, LocationForegroundService::class.java).apply {
                        this.action = LocationForegroundService.ACTION_TEST_START
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    serviceError = "Status: Test Service Started Successfully"
                } catch (e: SecurityException) {
                    serviceError = "Security Exception: ${e.message}"
                } catch (e: IllegalArgumentException) {
                    serviceError = "Illegal Argument: ${e.message}"
                } catch (e: Exception) {
                    serviceError = "Startup Error: ${e.message}"
                }
            }
        }
    }
}

@Composable
fun NavigationUI(loadError: String?, bleStatus: String, serviceError: String?, isNavigating: Boolean, onAction: (String) -> Unit) {
    var gatewayUrl by remember { mutableStateOf("https://example.invalid/moto-gps/api/") }
    var destination by remember { mutableStateOf("") }

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
                Text(text = serviceError, color = Color.Yellow, fontSize = 12.sp)
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
                Button(onClick = { onAction("START_NAV") }) {
                    Text("Start Navigation (Simulation)")
                }
            } else {
                Text(text = "Navigation Active", color = Color.Yellow, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { onAction("STOP_NAV") }) {
                    Text("End Navigation")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = { onAction("TEST_SERVICE") }) {
                Text("Test Pure Service Startup")
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(text = "GPS: Waiting for fix... (WGS84 strictly)", color = Color.LightGray, fontSize = 12.sp)
        }
    }
}
