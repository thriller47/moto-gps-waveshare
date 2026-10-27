package com.motogps.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
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

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var bleManager: MotoBLEManager
    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null
    private var magnetometerSensor: Sensor? = null

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false

    private var lastSentHeading = -1f
    private var lastSentHeadingTime = 0L
    private var lastBackPressTime = 0L
    private var foregroundLocationCallback: ((Location) -> Unit)? = null

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

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationVectorSensor == null) {
            accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            magnetometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
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

        // BLE must exist before the foreground GPS service can initialize the
        // ViewModel through its first location callback on an already-running app.
        val callback: (Location) -> Unit = { location ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    viewModel.updateDeviceLocation(location, context = this)
                }
            }
        }
        foregroundLocationCallback = callback
        LocationForegroundService.onLocationUpdate = callback


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

        setContent {
            val connectionState = viewModel.connectionState.collectAsState().value
            val isLoading = viewModel.isLoading.collectAsState().value
            val searchResults = viewModel.searchResults.collectAsState().value
            val errorMessage = viewModel.errorMessage.collectAsState().value
            val routePreview = viewModel.routePreview.collectAsState().value
            val routeOptions = viewModel.routeOptions.collectAsState().value
            val routingSource = viewModel.routingSource.collectAsState().value
            val selectedRouteIndex = viewModel.selectedRouteIndex.collectAsState().value
            val isNavigating = viewModel.isNavigating.collectAsState().value
            val currentLocation = viewModel.currentLocation.collectAsState().value
            val downloadStatus = viewModel.downloadStatus.collectAsState().value
            val downloadProgress = viewModel.downloadProgress.collectAsState().value
            val isDownloadingMap = viewModel.isDownloadingMap.collectAsState().value
            val valhallaRegions = viewModel.valhallaRegions.collectAsState().value
            val valhallaCatalogLoading = viewModel.valhallaCatalogLoading.collectAsState().value
            val valhallaInstalledBytes = viewModel.valhallaInstalledBytes.collectAsState().value
            val valhallaDownloading = viewModel.valhallaDownloading.collectAsState().value
            val valhallaProgress = viewModel.valhallaProgress.collectAsState().value
            val valhallaStatus = viewModel.valhallaStatus.collectAsState().value

            var mapsUpdateTrigger by remember { mutableStateOf(0) }
            val savedMaps = viewModel.savedMaps.collectAsState().value
            LaunchedEffect(mapsUpdateTrigger) {
                viewModel.refreshSavedMaps(this@MainActivity)
            }

            if (errorMessage != null) {
                LaunchedEffect(errorMessage) {
                    Toast.makeText(this@MainActivity, errorMessage, Toast.LENGTH_SHORT).show()
                    viewModel.clearError()
                }
            }

            MaterialTheme(colorScheme = lightColorScheme()) {
                NavigationScreen(
                    onSearch = { query ->
                        viewModel.searchDestination(query)
                    },
                    onConnectRequest = {
                        checkPermissionsAndScan()
                    },
                    connectionState = connectionState,
                    isLoading = isLoading,
                    searchResults = searchResults,
                    routePreview = routePreview,
                    routeOptions = routeOptions,
                    routingSource = routingSource,
                    selectedRouteIndex = selectedRouteIndex,
                    isNavigating = isNavigating,
                    currentLocation = currentLocation,
                    savedMaps = savedMaps,
                    onRouteOptionSelect = { index ->
                        viewModel.selectRouteOption(index)
                    },
                    onRouteSelected = { place ->
                        viewModel.previewRoute(place)
                    },
                    onStartNavigation = { place ->
                        viewModel.startNavigation(place)
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
                    },
                    downloadStatus = downloadStatus,
                    downloadProgress = downloadProgress,
                    isDownloadingMap = isDownloadingMap,
                    valhallaRegions = valhallaRegions,
                    valhallaCatalogLoading = valhallaCatalogLoading,
                    valhallaInstalledBytes = valhallaInstalledBytes,
                    valhallaDownloading = valhallaDownloading,
                    valhallaProgress = valhallaProgress,
                    valhallaStatus = valhallaStatus,
                    onRefreshValhallaCatalog = { viewModel.refreshValhallaCatalog() },
                    onDownloadValhalla = { id -> viewModel.downloadValhallaRegion(this@MainActivity, id) },
                    onCancelValhalla = { viewModel.cancelValhallaDownload() },
                    onDeleteValhalla = { viewModel.deleteValhallaGraph(this@MainActivity) },
                    onCancelDownload = {
                        viewModel.cancelMapDownload()
                    },
                    onRefreshMaps = {
                        viewModel.refreshSavedMaps(this@MainActivity)
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

    private fun startLocationUpdates() {
        // Only the foreground service registers with LocationManager. The
        // Activity previously registered a SECOND GPS+network listener, so
        // competing callbacks could overwrite a good GPS fix with a coarse
        // network fix and teleport the ESP32 map origin for ~3 seconds.
        val serviceIntent = Intent(this, LocationForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onDestroy() {
        // finish() is an actual app exit, not merely Home/background navigation.
        // Release GATT and callbacks so the next launch can reconnect to an
        // already powered-on ESP32 without an orphaned Android BLE session.
        if (LocationForegroundService.onLocationUpdate === foregroundLocationCallback) {
            LocationForegroundService.onLocationUpdate = null
        }
        foregroundLocationCallback = null
        if (::bleManager.isInitialized) bleManager.close()
        if (isFinishing) {
            stopService(Intent(this, LocationForegroundService::class.java))
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        registerSensors()
    }

    override fun onPause() {
        super.onPause()
        unregisterSensors()
    }

    private fun registerSensors() {
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        } ?: run {
            accelerometerSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            magnetometerSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        var azimuthDeg: Float? = null

        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            val azimuthRad = orientation[0]
            azimuthDeg = ((Math.toDegrees(azimuthRad.toDouble()).toFloat() % 360f) + 360f) % 360f
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, gravity, 0, 3)
            hasGravity = true
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, geomagnetic, 0, 3)
            hasGeomagnetic = true
        }

        if (azimuthDeg == null && hasGravity && hasGeomagnetic) {
            val R = FloatArray(9)
            val I = FloatArray(9)
            if (SensorManager.getRotationMatrix(R, I, gravity, geomagnetic)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(R, orientation)
                val azimuthRad = orientation[0]
                azimuthDeg = ((Math.toDegrees(azimuthRad.toDouble()).toFloat() % 360f) + 360f) % 360f
            }
        }

        if (azimuthDeg != null) {
            val now = System.currentTimeMillis()
            if (Math.abs(azimuthDeg - lastSentHeading) >= 1.5f || now - lastSentHeadingTime >= 150L) {
                lastSentHeading = azimuthDeg
                lastSentHeadingTime = now
                viewModel.updateDeviceHeading(azimuthDeg)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}


}
