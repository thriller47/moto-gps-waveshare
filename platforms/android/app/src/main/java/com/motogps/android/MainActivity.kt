package com.motogps.android

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.motogps.android.diagnostics.DiagnosticLog
import com.motogps.android.diagnostics.ReplayTraceRecorder
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.motogps.android.ble.MotoBLEManager
import com.motogps.android.location.LocationForegroundService
import com.motogps.android.ui.MotoChatDarkColorScheme
import com.motogps.android.ui.NavigationScreen
import com.motogps.android.viewmodel.NavigationViewModel
import com.motogps.android.viewmodel.NavigationViewModelFactory
import com.motogps.android.viewmodel.NavigationReconnectPolicy

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
    private val rotationMatrix = FloatArray(9)
    private val inclinationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var diagnosticMode by mutableStateOf(0)
    private var replayRecording by mutableStateOf(ReplayTraceRecorder.isRecording())
    private val diagnosticTimeoutHandler = Handler(Looper.getMainLooper())
    private var requestedDiagnosticMode = 0
    private val diagnosticRefresh = object : Runnable {
        override fun run() {
            if (!replayRecording || requestedDiagnosticMode == 0) return
            if (bleManager.setDiagnosticMode(requestedDiagnosticMode)) {
                diagnosticMode = requestedDiagnosticMode
                DiagnosticLog.event("ESP32_DIAG_REFRESH",
                    "mode=$requestedDiagnosticMode")
                // Firmware logging is capped at ~60 minutes. Renew it early.
                diagnosticTimeoutHandler.postDelayed(this, 45L * 60L * 1000L)
            } else {
                diagnosticMode = 0
                // The GPS trace continues. Try again when BLE comes back.
                diagnosticTimeoutHandler.postDelayed(this, 60_000L)
            }
        }
    }

    /** Start/stop GPS replay and ESP32 BLE events as one explicit session. */
    private fun setCompleteDiagnosticMode(mode: Int) {
        if (mode == 0) {
            ReplayTraceRecorder.stop()
            replayRecording = false
            bleManager.setDiagnosticMode(0)
            diagnosticMode = 0
            requestedDiagnosticMode = 0
            diagnosticTimeoutHandler.removeCallbacks(diagnosticRefresh)
            DiagnosticLog.event("FULL_DIAGNOSTICS", "stopped")
            return
        }
        if (mode !in 1..2) return
        if (!bleManager.setDiagnosticMode(mode)) {
            Toast.makeText(this, "Connetti l’ESP32 per registrare GPS e BLE insieme",
                Toast.LENGTH_LONG).show()
            return
        }
        try {
            ReplayTraceRecorder.start(this)
            replayRecording = ReplayTraceRecorder.isRecording()
            diagnosticMode = mode
            requestedDiagnosticMode = mode
            diagnosticTimeoutHandler.removeCallbacks(diagnosticRefresh)
            diagnosticTimeoutHandler.postDelayed(diagnosticRefresh, 45L * 60L * 1000L)
            DiagnosticLog.event("FULL_DIAGNOSTICS", "started mode=$mode")
        } catch (e: Exception) {
            bleManager.setDiagnosticMode(0)
            ReplayTraceRecorder.stop()
            replayRecording = false
            diagnosticMode = 0
            requestedDiagnosticMode = 0
            Toast.makeText(this, "Diagnostica non avviata: ${e.message}",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun exportDiagnostics() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Freeze both streams before exporting a consistent, single snapshot.
                withContext(Dispatchers.Main) { setCompleteDiagnosticMode(0) }
                val file = DiagnosticLog.exportCompleteZip(this@MainActivity)
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.diagnostics", file)
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(send, "Esporta diagnostica Moto GPS"))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this@MainActivity, "Esportazione non riuscita: ${e.message}",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private val viewModel: NavigationViewModel by viewModels {
        // Keep the same BLE manager as the retained ViewModel across rotation.
        NavigationViewModelFactory(applicationContext)
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            if (!bleManager.hasActiveConnectionOrScan()) bleManager.startScan()
            startLocationUpdates()
        } else {
            Toast.makeText(this, "Permessi necessari per la connessione BLE e GPS", Toast.LENGTH_LONG).show()
        }
    }

    private val importUnifiedPackLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importUnifiedOfflinePack(this, uri)
    }

    private val importOsmPackLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importOsmRoadPack(this, uri)
    }

    private val importPhotonLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importPhotonIndex(this, uri)
    }

    private val importValhallaGraphLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importValhallaGraph(this, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationVectorSensor == null) {
            accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            magnetometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        }

        bleManager = viewModel.bleManager.apply {
            onConnectionStateChange = { state ->
                DiagnosticLog.event("BLE_LINK", state)
                viewModel.setConnectionState(state)
                if (!NavigationReconnectPolicy.isReady(state)) {
                    diagnosticTimeoutHandler.removeCallbacks(diagnosticRefresh)
                    diagnosticMode = 0
                } else if (replayRecording && requestedDiagnosticMode != 0) {
                    diagnosticTimeoutHandler.removeCallbacks(diagnosticRefresh)
                    diagnosticTimeoutHandler.post(diagnosticRefresh)
                }
            }
            onDeviceCommandReceived = { command ->
                if (command.kind == 21) { // Confirmed waypoint-marker request from ESP32
                    DiagnosticLog.event("WAYPOINT_SKIP", "phase=device_command")
                    viewModel.skipNextWaypoint()
                }
            }
            onDiagnosticBatchReceived = { records ->
                DiagnosticLog.esp32Batch(records)
            }
            onConnectionError = { errorMsg ->
                DiagnosticLog.event("BLE_ERROR", errorMsg)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                }
            }
        }

        viewModel.loadRoutePreferences(this)

        // BLE must exist before the foreground GPS service can initialize the
        // ViewModel through its first location update on an already-running app.
        lifecycleScope.launch {
            LocationForegroundService.locationFlow.collect { location ->
                if (!isFinishing && !isDestroyed) {
                    viewModel.updateDeviceLocation(location, context = this@MainActivity)
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

        setContent {
            var coffeeTimeoutMinutes by remember {
                mutableStateOf(bleManager.getCoffeeTimeoutMinutes())
            }
            val connectionState = viewModel.connectionState.collectAsState().value
            val isLoading = viewModel.isLoading.collectAsState().value
            val searchResults = viewModel.searchResults.collectAsState().value
            val errorMessage = viewModel.errorMessage.collectAsState().value
            val routePreview = viewModel.routePreview.collectAsState().value
            val routeOptions = viewModel.routeOptions.collectAsState().value
            val routeWaypoints = viewModel.routeWaypoints.collectAsState().value
            val nextWaypointToSkip = viewModel.nextWaypointToSkip.collectAsState().value
            val waypointSearchResults = viewModel.waypointSearchResults.collectAsState().value
            val waypointSearching = viewModel.waypointSearching.collectAsState().value
            val waypointSearchError = viewModel.waypointSearchError.collectAsState().value
            val routingSource = viewModel.routingSource.collectAsState().value
            val routePreviewError = viewModel.routePreviewError.collectAsState().value
            val routePreferences = viewModel.routePreferences.collectAsState().value
            val selectedRouteIndex = viewModel.selectedRouteIndex.collectAsState().value
            val roadDnaSummary = viewModel.roadDnaSummary.collectAsState().value
            val roadDnaLoading = viewModel.roadDnaLoading.collectAsState().value
            val roadDnaError = viewModel.roadDnaError.collectAsState().value
            val roadDnaOnline = viewModel.roadDnaOnline.collectAsState().value
            val roadDnaOnlineLoading = viewModel.roadDnaOnlineLoading.collectAsState().value
            val roadDnaOnlineError = viewModel.roadDnaOnlineError.collectAsState().value
            val roadDnaNearbyCenter = viewModel.roadDnaNearbyCenter.collectAsState().value
            val roadDnaZoneOffline =
                viewModel.roadDnaZoneOffline.collectAsState().value
            val roadDnaZone = viewModel.roadDnaZone.collectAsState().value
            val roadDnaZoneLoading = viewModel.roadDnaZoneLoading.collectAsState().value
            val roadDnaZoneError = viewModel.roadDnaZoneError.collectAsState().value
            val roadDnaAreaAccesses =
                viewModel.roadDnaAreaAccesses.collectAsState().value
            val roadDnaAreaAccessLoading =
                viewModel.roadDnaAreaAccessLoading.collectAsState().value
            val roadDnaAreaAccessError =
                viewModel.roadDnaAreaAccessError.collectAsState().value
            val roadDnaDetails = viewModel.roadDnaDetails.collectAsState().value
            val roadDnaDetailsLoading = viewModel.roadDnaDetailsLoading.collectAsState().value
            val geoapifyConfigured = viewModel.geoapifyConfigured.collectAsState().value
            val opentripmapConfigured = viewModel.opentripmapConfigured.collectAsState().value
            val roadDnaKeyStatus = viewModel.roadDnaKeyStatus.collectAsState().value
            val roadDnaKeyBusy = viewModel.roadDnaKeyBusy.collectAsState().value
            val isNavigating = viewModel.isNavigating.collectAsState().value
            val hasArrived = viewModel.hasArrived.collectAsState().value
            val currentLocation = viewModel.currentLocation.collectAsState().value
            val downloadStatus = viewModel.downloadStatus.collectAsState().value
            val downloadProgress = viewModel.downloadProgress.collectAsState().value
            val isDownloadingMap = viewModel.isDownloadingMap.collectAsState().value
            val photonInstalledBytes = viewModel.photonInstalledBytes.collectAsState().value
            val roadDnaInstalledBytes = viewModel.roadDnaInstalledBytes.collectAsState().value
            val photonImporting = viewModel.photonImporting.collectAsState().value
            val photonStatus = viewModel.photonStatus.collectAsState().value
            val valhallaInstalledBytes = viewModel.valhallaInstalledBytes.collectAsState().value
            val valhallaImporting = viewModel.valhallaImporting.collectAsState().value
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

            MaterialTheme(colorScheme = MotoChatDarkColorScheme) {
                NavigationScreen(
                    onExportDiagnostics = { exportDiagnostics() },
                    replayRecording = replayRecording,
                    diagnosticMode = diagnosticMode,
                    onUnifiedDiagnosticModeChange = { mode -> setCompleteDiagnosticMode(mode) },
                    onSearch = { query ->
                        viewModel.searchDestination(query)
                    },
                    onConnectRequest = {
                        checkPermissionsAndScan(forceScan = true)
                    },
                    connectionState = connectionState,
                    coffeeTimeoutMinutes = coffeeTimeoutMinutes,
                    onCoffeeTimeoutChange = { minutes ->
                        bleManager.setCoffeeTimeoutMinutes(minutes)
                        coffeeTimeoutMinutes = bleManager.getCoffeeTimeoutMinutes()
                    },
                    isLoading = isLoading,
                    searchResults = searchResults,
                    routePreview = routePreview,
                    routeOptions = routeOptions,
                    routeWaypoints = routeWaypoints,
                    nextWaypointToSkip = nextWaypointToSkip,
                    onSkipNextWaypoint = { viewModel.skipNextWaypoint() },
                    waypointSearchResults = waypointSearchResults,
                    waypointSearching = waypointSearching,
                    waypointSearchError = waypointSearchError,
                    onSearchWaypoint = { viewModel.searchWaypoint(it) },
                    onAddWaypoint = { viewModel.addRouteWaypoint(it) },
                    onRemoveWaypoint = { viewModel.removeRouteWaypoint(it) },
                    onMoveWaypoint = { index, offset ->
                        viewModel.moveRouteWaypoint(index, offset)
                    },
                    routingSource = routingSource,
                    routePreviewError = routePreviewError,
                    routePreferences = routePreferences,
                    onRoutePreferencesChange = {
                        viewModel.updateRoutePreferences(this@MainActivity, it)
                    },
                    selectedRouteIndex = selectedRouteIndex,
                    roadDnaSummary = roadDnaSummary,
                    roadDnaLoading = roadDnaLoading,
                    roadDnaError = roadDnaError,
                    onRoadDnaAnalyze = { viewModel.analyzeSelectedRoadDna() },
                    roadDnaOnline = roadDnaOnline,
                    roadDnaOnlineLoading = roadDnaOnlineLoading,
                    roadDnaOnlineError = roadDnaOnlineError,
                    roadDnaDetails = roadDnaDetails,
                    roadDnaDetailsLoading = roadDnaDetailsLoading,
                    onRoadDnaOnlineSearch = { categoryId ->
                        viewModel.searchRoadDnaOnline(categoryId)
                    },
                    onRoadDnaDetails = { viewModel.loadRoadDnaDetails(it) },
                    roadDnaNearbyCenter = roadDnaNearbyCenter,
                    roadDnaZone = roadDnaZone,
                    roadDnaZoneOffline = roadDnaZoneOffline,
                    roadDnaZoneLoading = roadDnaZoneLoading,
                    roadDnaZoneError = roadDnaZoneError,
                    onRoadDnaZoneSearch = { lat, lon, radius, category ->
                        viewModel.searchRoadDnaZone(lat, lon, radius, category)
                    },
                    onRoadDnaZoneClear = { viewModel.clearRoadDnaZone() },
                    onRoadDnaNearbySearch = { radius, category ->
                        viewModel.searchRoadDnaAroundMe(radius, category)
                    },
                    roadDnaAreaAccesses = roadDnaAreaAccesses,
                    roadDnaAreaAccessLoading = roadDnaAreaAccessLoading,
                    roadDnaAreaAccessError = roadDnaAreaAccessError,
                    onRoadDnaAreaAccessSearch = {
                        viewModel.searchRoadDnaAreaAccesses(it)
                    },
                    onRoadDnaAreaAccessClear = {
                        viewModel.clearRoadDnaAreaAccesses()
                    },
                    geoapifyConfigured = geoapifyConfigured,
                    opentripmapConfigured = opentripmapConfigured,
                    roadDnaKeyStatus = roadDnaKeyStatus,
                    roadDnaKeyBusy = roadDnaKeyBusy,
                    onSaveRoadDnaKey = { provider, value ->
                        viewModel.saveRoadDnaKey(provider, value)
                    },
                    onDeleteRoadDnaKey = { viewModel.deleteRoadDnaKey(it) },
                    onTestRoadDnaKey = { viewModel.testRoadDnaKey(it) },
                    isNavigating = isNavigating,
                    hasArrived = hasArrived,
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
                    onCorridorTileCount = {
                        viewModel.corridorTileCount(this@MainActivity)
                    },
                    onDownloadCorridor = {
                        viewModel.downloadOpenFreeMapCorridor(this@MainActivity)
                    },
                    onStopNavigation = {
                        viewModel.stopNavigation()
                    },
                    onBackToSearch = {
                        viewModel.clearRoutePreview()
                    },
                    onImportUnifiedPack = {
                        importUnifiedPackLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
                    onImportOsmPack = {
                        importOsmPackLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
                    onDeleteMap = { fileName ->
                        viewModel.deleteSavedMap(this@MainActivity, fileName)
                        mapsUpdateTrigger++
                    },
                    downloadStatus = downloadStatus,
                    downloadProgress = downloadProgress,
                    isDownloadingMap = isDownloadingMap,
                    photonInstalledBytes = photonInstalledBytes,
                    roadDnaInstalledBytes = roadDnaInstalledBytes,
                    photonImporting = photonImporting,
                    photonStatus = photonStatus,
                    onImportPhoton = {
                        importPhotonLauncher.launch(arrayOf(
                            "application/x-sqlite3", "application/octet-stream", "*/*"
                        ))
                    },
                    onDeletePhoton = { viewModel.deletePhotonIndex(this@MainActivity) },
                    valhallaInstalledBytes = valhallaInstalledBytes,
                    valhallaImporting = valhallaImporting,
                    valhallaStatus = valhallaStatus,
                    onImportValhalla = {
                        importValhallaGraphLauncher.launch(
                            arrayOf("application/zstd", "application/x-zstd", "application/x-tar", "application/octet-stream", "*/*")
                        )
                    },
                    onCancelValhalla = { viewModel.cancelValhallaImport() },
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

    private fun checkPermissionsAndScan(forceScan: Boolean = false) {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
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
            // Automatic startup must not disconnect an already-live session
            // after Activity recreation. An explicit Connect request may rescan.
            if (forceScan || !bleManager.hasActiveConnectionOrScan()) {
                bleManager.startScan()
            }
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
        diagnosticTimeoutHandler.removeCallbacks(diagnosticRefresh)
        // The retained ViewModel owns GATT and releases it from onCleared().
        // A display rotation destroys the Activity, not the navigation session.
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
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            val azimuthRad = orientationAngles[0]
            azimuthDeg = ((Math.toDegrees(azimuthRad.toDouble()).toFloat() % 360f) + 360f) % 360f
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, gravity, 0, 3)
            hasGravity = true
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, geomagnetic, 0, 3)
            hasGeomagnetic = true
        }

        if (azimuthDeg == null && hasGravity && hasGeomagnetic) {
            if (SensorManager.getRotationMatrix(rotationMatrix, inclinationMatrix, gravity, geomagnetic)) {
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                val azimuthRad = orientationAngles[0]
                azimuthDeg = ((Math.toDegrees(azimuthRad.toDouble()).toFloat() % 360f) + 360f) % 360f
            }
        }

        if (azimuthDeg != null) {
            val now = System.currentTimeMillis()
            val deltaDeg = if (lastSentHeading < 0f) 360f else kotlin.math.abs(((azimuthDeg - lastSentHeading + 540f) % 360f) - 180f)
            if (deltaDeg >= 1.5f || now - lastSentHeadingTime >= 150L) {
                lastSentHeading = azimuthDeg
                lastSentHeadingTime = now
                viewModel.updateDeviceHeading(azimuthDeg)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}


}
