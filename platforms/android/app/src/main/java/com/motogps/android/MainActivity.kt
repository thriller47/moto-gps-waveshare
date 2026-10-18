package com.motogps.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import com.motogps.android.ble.MotoBLEManager
import com.motogps.android.ui.NavigationScreen
import com.motogps.android.viewmodel.NavigationViewModel
import com.motogps.android.viewmodel.NavigationViewModelFactory

class MainActivity : ComponentActivity() {
    private lateinit var bleManager: MotoBLEManager
    private val viewModel: NavigationViewModel by viewModels {
        NavigationViewModelFactory(bleManager)
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            bleManager.startScan()
        } else {
            Toast.makeText(this, "Permessi necessari per la connessione BLE", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bleManager = MotoBLEManager(this).apply {
            onConnectionStateChange = { state ->
                viewModel.setConnectionState(state)
            }
            onConnectionError = { errorMsg ->
                runOnUiThread {
                    Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                }
            }
        }

        val sharedPrefs = getSharedPreferences("MotoGpsPrefs", Context.MODE_PRIVATE)
        val initialGateway = sharedPrefs.getString("gateway_address", "") ?: ""

        setContent {
            val connectionState = viewModel.connectionState.collectAsState().value
            val isLoading = viewModel.isLoading.collectAsState().value
            val searchResults = viewModel.searchResults.collectAsState().value
            val errorMessage = viewModel.errorMessage.collectAsState().value
            val routePreview = viewModel.routePreview.collectAsState().value
            val routeOptions = viewModel.routeOptions.collectAsState().value
            val selectedRouteIndex = viewModel.selectedRouteIndex.collectAsState().value

            if (errorMessage != null) {
                LaunchedEffect(errorMessage) {
                    Toast.makeText(this@MainActivity, errorMessage, Toast.LENGTH_SHORT).show()
                    viewModel.clearError()
                }
            }

            MaterialTheme(colorScheme = lightColorScheme()) {
                NavigationScreen(
                    initialGateway = initialGateway,
                    onGatewaySave = { newGateway ->
                        sharedPrefs.edit().putString("gateway_address", newGateway).apply()
                    },
                    onSearch = { query ->
                        val savedGateway = sharedPrefs.getString("gateway_address", "") ?: ""
                        viewModel.searchDestination(query, savedGateway)
                    },
                    onConnectRequest = {
                        checkPermissionsAndScan()
                    },
                    connectionState = connectionState,
                    isLoading = isLoading,
                    searchResults = searchResults,
                    routePreview = routePreview,
                    routeOptions = routeOptions,
                    selectedRouteIndex = selectedRouteIndex,
                    onRouteOptionSelect = { index ->
                        viewModel.selectRouteOption(index)
                    },
                    onRouteSelected = { place ->
                        val savedGateway = sharedPrefs.getString("gateway_address", "") ?: ""
                        viewModel.previewRoute(place, savedGateway)
                    },
                    onStartNavigation = { place ->
                        val savedGateway = sharedPrefs.getString("gateway_address", "") ?: ""
                        viewModel.startNavigation(place, savedGateway)
                    },
                    onBackToSearch = {
                        viewModel.clearRoutePreview()
                    }
                )
            }
        }

        checkPermissionsAndScan()
    }

    private fun checkPermissionsAndScan() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            bleManager.startScan()
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }
}
