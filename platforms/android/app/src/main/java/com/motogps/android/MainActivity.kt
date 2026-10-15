package com.motogps.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import com.motogps.android.jni.MotoBLEProtocolCodec

class MainActivity : ComponentActivity() {

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
                    color = Color.Black // Ensuring black background to contrast white text
                ) {
                    SandboxUI(loadError)
                }
            }
        }
    }
}

@Composable
fun SandboxUI(loadError: String?) {
    var scanStatus by remember { mutableStateOf("Idle/Disconnected") }

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
                text = "Bluetooth State: $scanStatus",
                color = Color.LightGray
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = { scanStatus = "Scanning for Waveshare device..." }) {
                Text("Trigger Mock Scan")
            }
        }
    }
}
