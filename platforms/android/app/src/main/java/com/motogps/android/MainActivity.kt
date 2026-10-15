package com.motogps.android

import android.Manifest
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

class MainActivity : ComponentActivity() {

    private var bleStatus by mutableStateOf("Idle/Disconnected")

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            bleStatus = "Permissions Granted. Ready to Scan."
        } else {
            bleStatus = "Permissions Denied."
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
                    SandboxUI(loadError, bleStatus) { action ->
                        handleMockAction(action)
                    }
                }
            }
        }

        checkPermissions()
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
            if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
                bleStatus = "Permissions Granted. Ready to Scan."
            } else {
                requestPermissionLauncher.launch(permissions)
            }
        } else {
            bleStatus = "Permissions Granted (Legacy API). Ready to Scan."
        }
    }

    private fun handleMockAction(action: String) {
        when (action) {
            "SCAN" -> bleStatus = "Scanning for Waveshare device..."
            "HANDSHAKE" -> bleStatus = "Handshaking..."
            "CONNECT" -> bleStatus = "Connected (Ready)"
            "DISCONNECT" -> bleStatus = "Disconnected (Queue Cleared)"
            "ERROR" -> bleStatus = "Error (Timeout)"
        }
    }
}

@Composable
fun SandboxUI(loadError: String?, bleStatus: String, onAction: (String) -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Glimpse MOTO GPS - Android Port Sandbox",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (loadError != null) {
                Text(
                    text = "Status: Native C++ Library FAILED",
                    color = Color.Red,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = loadError,
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            } else {
                Text(
                    text = "Status: Native C++ Library Loaded Successfully",
                    color = Color.Green,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Bluetooth State: $bleStatus",
                color = Color.Cyan,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = { onAction("SCAN") }) {
                Text("Mock Scan")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { onAction("HANDSHAKE") }) {
                Text("Mock Handshake")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { onAction("CONNECT") }) {
                Text("Mock Connect")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { onAction("DISCONNECT") }) {
                Text("Mock Disconnect")
            }
        }
    }
}
