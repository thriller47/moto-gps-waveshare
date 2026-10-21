package com.motogps.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.motogps.android.ble.MotoBLEManager
import com.motogps.android.location.LocationForegroundService
import com.motogps.android.ui.NavigationScreen
import com.motogps.android.viewmodel.NavigationViewModel
import com.motogps.android.viewmodel.NavigationViewModelFactory

class MainActivity : ComponentActivity(), LocationListener {
    private lateinit var bleManager: MotoBLEManager
    private lateinit var locationManager: LocationManager
    private var lastBackPressTime = 0L

    private val viewModel: NavigationViewModel by viewModels {
        NavigationViewModelFactory(bleManager)
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            bleManager.startScan()
            startLocationUpdates()
        } else {
            Toast.makeText(this, "Permessi necessari per la connessione BLE e GPS", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        LocationForegroundService.onLocationUpdate = { lat, lon ->
            runOnUiThread {
                viewModel.updateDeviceLocation(lat, lon, this)
            }
        }

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

        // Double-tap Back button to exit
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val routePreview = viewModel.routePreview.value
                if (routePreview != null) {
                    viewModel.clearRoutePreview()
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastBackPressTime < 2000) {
                        finish()
                    } else {
                        lastBackPressTime = now
                        Toast.makeText(this@MainActivity, "Premi ancora per uscire", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })

        val sharedPrefs = getSharedPreferences("MotoGpsPrefs", Context.MODE_PRIVATE)
        val defaultGateway = "https://moto-gps-waveshare.onrender.com"
        val rawSavedGateway = sharedPrefs.getString("gateway_address", null)
        if (!rawSavedGateway.isNullOrBlank() && !rawSavedGateway.startsWith(defaultGateway)) {
            sharedPrefs.edit().remove("gateway_address").apply()
        }

        fun getGatewayToUse(): String {
            val saved = sharedPrefs.getString("gateway_address", null)
            return if (!saved.isNullOrBlank() && saved.startsWith(defaultGateway)) saved else defaultGateway
        }

        val initialGateway = getGatewayToUse()

        setContent {
            val connectionState = viewModel.connectionState.collectAsState().value
            val isLoading = viewModel.isLoading.collectAsState().value
            val searchResults = viewModel.searchResults.collectAsState().value
            val errorMessage = viewModel.errorMessage.collectAsState().value
            val routePreview = viewModel.routePreview.collectAsState().value
            val routeOptions = viewModel.routeOptions.collectAsState().value
            val selectedRouteIndex = viewModel.selectedRouteIndex.collectAsState().value
            val isNavigating = viewModel.isNavigating.collectAsState().value
            val currentLocation = viewModel.currentLocation.collectAsState().value

            var mapsUpdateTrigger by remember { mutableStateOf(0) }
            val savedMaps = remember(errorMessage, mapsUpdateTrigger) {
                viewModel.listSavedMaps(this@MainActivity)
            }

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
                        viewModel.searchDestination(query, getGatewayToUse())
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
                    isNavigating = isNavigating,
                    currentLocation = currentLocation,
                    savedMaps = savedMaps,
                    onRouteOptionSelect = { index ->
                        viewModel.selectRouteOption(index)
                    },
                    onRouteSelected = { place ->
                        viewModel.previewRoute(place, getGatewayToUse())
                    },
                    onStartNavigation = { place ->
                        viewModel.startNavigation(place, getGatewayToUse())
                    },
                    onStopNavigation = {
                        viewModel.stopNavigation()
                    },
                    onBackToSearch = {
                        viewModel.clearRoutePreview()
                    },
                    onDownloadArea = {
                        viewModel.downloadCurrentArea(this@MainActivity)
                    },
                    onDownloadCorridor = { points ->
                        viewModel.downloadRouteCorridor(this@MainActivity, points)
                    },
                    onDownloadRegion = { region ->
                        viewModel.downloadRegion(this@MainActivity, region)
                    },
                    onDeleteMap = { fileName ->
                        viewModel.deleteSavedMap(this@MainActivity, fileName)
                        mapsUpdateTrigger++
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
            startLocationUpdates()
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        try {
            val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            val bestLast = selectBestLocation(lastGps, lastNet)
            if (bestLast != null) {
                viewModel.updateDeviceLocation(bestLast.latitude, bestLast.longitude, this)
            }

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    1f,
                    this
                )
            }

            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    1f,
                    this
                )
            }

            val serviceIntent = Intent(this, LocationForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (_: Exception) {}
    }

    private fun selectBestLocation(loc1: Location?, loc2: Location?): Location? {
        if (loc1 == null) return loc2
        if (loc2 == null) return loc1
        return if (loc1.time >= loc2.time) loc1 else loc2
    }

    override fun onLocationChanged(location: Location) {
        viewModel.updateDeviceLocation(location.latitude, location.longitude, this)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
