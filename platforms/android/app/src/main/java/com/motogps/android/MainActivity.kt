package com.motogps.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.motogps.android.jni.MotoBLEProtocolCodec

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        var loadError: String? = null
        try {
            // Force load check here to catch it on UI start
            MotoBLEProtocolCodec.isLibraryLoaded
        } catch (t: Throwable) {
            loadError = t.message ?: t.toString()
        }

        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (loadError != null) {
                        Text(text = "Failed to load native library:\n$loadError", modifier = Modifier.padding(16.dp))
                    } else {
                        Text(text = "Moto GPS Native Core Loaded Successfully", modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
    }
}
