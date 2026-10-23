package com.motogps.android.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.motogps.android.ble.MotoBLEManager
import com.motogps.android.jni.GeoPointE6
import com.motogps.android.jni.MotoBLEMapRoadPolyline
import com.motogps.android.jni.MotoBLEMapScene
import com.motogps.android.jni.MotoBLENavigationSnapshot
import com.motogps.android.jni.MotoBLERouteGeometry
import com.motogps.android.map.HybridMapRepository
import com.motogps.android.map.SavedMapInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class PlaceSearchResult(
    val name: String,
    val address: String,
    val displayArea: String,
    val latitude: Double,
    val longitude: Double
)

data class RouteOption(
    val id: String,
    val name: String,
    val distanceMeters: Long,
    val durationSeconds: Long,
    val steps: List<String>,
    val rawRouteJson: JSONObject
)

data class RouteManeuverStep(
    val latitude: Double,
    val longitude: Double,
    val maneuverName: String,
    val instruction: String,
    val roadName: String
)

class NavigationViewModel(private val bleManager: MotoBLEManager) : ViewModel() {
    private val _connectionState = MutableStateFlow("Non connesso")
    val connectionState: StateFlow<String> = _connectionState

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _searchResults = MutableStateFlow<List<PlaceSearchResult>>(emptyList())
    val searchResults: StateFlow<List<PlaceSearchResult>> = _searchResults

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _routePreview = MutableStateFlow<PlaceSearchResult?>(null)
    val routePreview: StateFlow<PlaceSearchResult?> = _routePreview

    private val _routeOptions = MutableStateFlow<List<RouteOption>>(emptyList())
    val routeOptions: StateFlow<List<RouteOption>> = _routeOptions

    private val _selectedRouteIndex = MutableStateFlow(0)
    val selectedRouteIndex: StateFlow<Int> = _selectedRouteIndex

    private val _isNavigating = MutableStateFlow(false)
    val isNavigating: StateFlow<Boolean> = _isNavigating

    private val _currentDevicePage = MutableStateFlow(0)
    val currentDevicePage: StateFlow<Int> = _currentDevicePage

    private val _savedMaps = MutableStateFlow<List<SavedMapInfo>>(emptyList())
    val savedMaps: StateFlow<List<SavedMapInfo>> = _savedMaps

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress

    private val _downloadStatus = MutableStateFlow<String?>(null)
    val downloadStatus: StateFlow<String?> = _downloadStatus

    private val _isDownloadingMap = MutableStateFlow(false)
    val isDownloadingMap: StateFlow<Boolean> = _isDownloadingMap

    private var mapDownloadJob: Job? = null

    private var currentHeadingCentiDeg: Int = 0
    private var currentSpeedDeciKph: Int = 0
    private var prevLat: Double? = null
    private var prevLon: Double? = null
    private var isCompassSensorActive: Boolean = false
    private var telemetryJob: Job? = null

    private var navigationJob: Job? = null
    private var currentSnapshot: MotoBLENavigationSnapshot? = null
    private var currentGeometry: MotoBLERouteGeometry? = null
    private var routeManeuverSteps: List<RouteManeuverStep> = emptyList()
    private var activeStepIndex: Int = 0
    private var lastSpeedLimitKph = 0

    private var lastLocationTimeMs: Long = 0L
    private var lastMapSceneSendTimeMs: Long = 0L
    private var lastMapSceneLat: Double = 0.0
    private var lastMapSceneLon: Double = 0.0
    private var mapSceneUpdateJob: Job? = null

    init {
        bleManager.onDeviceCommandReceived = { cmd ->
            if (cmd.kind == 0) { // PageSelected
                _currentDevicePage.value = cmd.page
            }
        }
    }

    fun searchDestination(query: String, gatewayUrl: String) {
        if (query.isBlank() || gatewayUrl.isBlank()) return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _routePreview.value = null

            try {
                val results = withContext(Dispatchers.IO) {
                    performSearch(query, gatewayUrl)
                }
                _searchResults.value = results
                if (results.isEmpty()) {
                    _errorMessage.value = "Nessun risultato trovato per '$query'"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Errore durante la ricerca: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun previewRoute(place: PlaceSearchResult, gatewayUrl: String) {
        _routePreview.value = place
        _routeOptions.value = emptyList()
        _selectedRouteIndex.value = 0

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val options = withContext(Dispatchers.IO) {
                    fetchRouteOptions(place, gatewayUrl)
                }
                _routeOptions.value = options
            } catch (e: Exception) {
                _errorMessage.value = "Impossibile recuperare opzioni percorso: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun selectRouteOption(index: Int) {
        if (index in _routeOptions.value.indices) {
            _selectedRouteIndex.value = index
        }
    }

    fun clearRoutePreview() {
        _routePreview.value = null
        _routeOptions.value = emptyList()
        _selectedRouteIndex.value = 0
    }

    fun startNavigation(place: PlaceSearchResult, gatewayUrl: String) {
        if (gatewayUrl.isBlank()) return

        activeDestination = place
        activeGatewayUrl = gatewayUrl
        _isNavigating.value = true
        _currentDevicePage.value = 0 // Reset to navigation page on start

        navigationJob?.cancel()
        navigationJob = viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val (snapshot, geometry) = withContext(Dispatchers.IO) {
                    performRoutingWithGeometry(place, gatewayUrl)
                }

                currentSnapshot = snapshot
                currentGeometry = geometry

                if (geometry != null) {
                    bleManager.sendRouteGeometry(geometry)
                }
                bleManager.sendNavigationSnapshot(snapshot)

                _isLoading.value = false

                var loopCount = 0
                while (_isNavigating.value) {
                    delay(350)
                    if (!_isNavigating.value) break
                    loopCount++

                    if (System.currentTimeMillis() - lastLocationTimeMs > 3000L) {
                        currentSpeedDeciKph = 0
                    }

                    val pageName = "navigation"

                    val (lat, lon) = currentLocation.value
                    val updatedSnap = updateManeuverAndDistance(lat, lon) ?: currentSnapshot?.copy() ?: snapshot.copy()

                    val activeSnap = updatedSnap.copy(
                        displayPageName = pageName,
                        speedLimitKPH = lastSpeedLimitKph,
                        speedDeciKPH = currentSpeedDeciKph,
                        headingCentiDegrees = currentHeadingCentiDeg,
                        hasFix = hasFirstFix
                    )
                    currentSnapshot = activeSnap
                    bleManager.sendNavigationSnapshot(activeSnap)

                    if (loopCount % 9 == 0) { // ~3.15s interval
                        val activeGeom = currentGeometry ?: geometry
                        if (activeGeom != null) {
                            bleManager.sendRouteGeometry(activeGeom)
                        }
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Errore durante la navigazione: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun stopNavigation() {
        _isNavigating.value = false
        navigationJob?.cancel()
        navigationJob = null
        activeDestination = null
        currentSnapshot = null
        currentGeometry = null

        val stopSnapshot = MotoBLENavigationSnapshot(
            stateName = "idle",
            networkName = "online",
            displayPageName = "idle",
            maneuverName = "none",
            trafficName = "none",
            hasDestination = false,
            hasFix = hasFirstFix,
            gnssStale = false,
            offRoute = false,
            routeRequestInFlight = false,
            trafficRequestInFlight = false,
            routeToken = 0L,
            routeGeneration = 0L,
            maneuverID = 0L,
            distanceToManeuverM = 0L,
            remainingDistanceM = 0L,
            remainingDurationS = 0L,
            routeProgressM = 0L,
            totalDistanceM = 0L,
            speedDeciKPH = currentSpeedDeciKph,
            speedLimitKPH = 0,
            headingCentiDegrees = currentHeadingCentiDeg,
            accuracyDecimeters = 0,
            crossTrackDecimeters = 0,
            roundaboutExit = 0,
            instructionText = ""
        )
        bleManager.sendNavigationSnapshot(stopSnapshot)

        _routePreview.value = null
        _routeOptions.value = emptyList()
        _selectedRouteIndex.value = 0
    }

    private val _currentLocation = MutableStateFlow<Pair<Double, Double>>(Pair(41.9028, 12.4964))
    val currentLocation: StateFlow<Pair<Double, Double>> = _currentLocation

    private var hasFirstFix = false
    private var activeDestination: PlaceSearchResult? = null
    private var activeGatewayUrl: String = ""
    private var lastRecalculationTime: Long = 0
    private var offRouteFixCount = 0
    private var rerouteInProgress = false

    fun updateDeviceHeading(headingDeg: Float) {
        isCompassSensorActive = true
        val normalized = (headingDeg % 360f + 360f) % 360f
        currentHeadingCentiDeg = (normalized * 100f).toInt().coerceIn(0, 35999)
    }

    fun updateDeviceLocation(
        latitude: Double,
        longitude: Double,
        speedMs: Float = 0f,
        gpsBearingDeg: Float? = null,
        context: Context? = null
    ) {
        hasFirstFix = true
        _currentLocation.value = Pair(latitude, longitude)
        val now = System.currentTimeMillis()
        lastLocationTimeMs = now

        val speedZero = speedMs < 0.8f
        if (speedZero) {
            currentSpeedDeciKph = 0
        } else {
            currentSpeedDeciKph = (speedMs * 36f).toInt().coerceAtLeast(0)
        }

        if (gpsBearingDeg != null && speedMs > 1.5f) {
            val normalized = (gpsBearingDeg % 360f + 360f) % 360f
            currentHeadingCentiDeg = (normalized * 100f).toInt().coerceIn(0, 35999)
        } else if (!isCompassSensorActive && prevLat != null && prevLon != null) {
            val distMeters = calculateDistanceMeters(prevLat!!, prevLon!!, latitude, longitude)
            if (distMeters >= 3.0) {
                val calcBearing = calculateBearingDeg(prevLat!!, prevLon!!, latitude, longitude)
                val normalized = (calcBearing % 360f + 360f) % 360f
                currentHeadingCentiDeg = (normalized * 100f).toInt().coerceIn(0, 35999)
            }
        }

        prevLat = latitude
        prevLon = longitude

        if (context != null) {
            val distFromLastScene = calculateDistanceMeters(lastMapSceneLat, lastMapSceneLon, latitude, longitude)
            if (now - lastMapSceneSendTimeMs >= 1200L || distFromLastScene >= 12.0) {
                lastMapSceneSendTimeMs = now
                lastMapSceneLat = latitude
                lastMapSceneLon = longitude
                updateSpeedLimitAndMapScene(latitude, longitude, context)
            }
        }

        if (_isNavigating.value && activeDestination != null && activeGatewayUrl.isNotBlank()) {
            checkOffRouteAndRecalculate(latitude, longitude)
        }
    }

    private fun calculateBearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val y = Math.sin(deltaLambda) * Math.cos(phi2)
        val x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(deltaLambda)
        val theta = Math.atan2(y, x)
        val bearingDeg = Math.toDegrees(theta).toFloat()
        return (bearingDeg % 360f + 360f) % 360f
    }

    private fun updateSpeedLimitAndMapScene(lat: Double, lon: Double, context: Context) {
        if (mapSceneUpdateJob?.isActive == true) return
        mapSceneUpdateJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val repository = HybridMapRepository(context)
                val latE6 = (lat * 1e6).toInt()
                val lonE6 = (lon * 1e6).toInt()
                val deltaE6 = 5000

                val segments = repository.loadFromRegionalFiles(
                    queryMinLatE6 = latE6 - deltaE6,
                    queryMinLonE6 = lonE6 - deltaE6,
                    queryMaxLatE6 = latE6 + deltaE6,
                    queryMaxLonE6 = lonE6 + deltaE6
                )

                val roadPolylines = mutableListOf<MotoBLEMapRoadPolyline>()

                if (segments.isNotEmpty()) {
                    var minDistance = Double.MAX_VALUE
                    var bestSpeed = 0

                    for (seg in segments) {
                        for (pt in seg.points) {
                            val dist = calculateDistanceMeters(lat, lon, pt.latitudeE6 / 1e6, pt.longitudeE6 / 1e6)
                            if (dist < minDistance) {
                                minDistance = dist
                                bestSpeed = seg.maxSpeedKph
                            }
                        }
                    }

                    lastSpeedLimitKph = if (minDistance <= 50.0) bestSpeed else 0

                    // Prefer actual nearby OSM roads and never exceed the ESP32's
                    // aggregate 192-point / 24-polyline scene capacities.
                    val candidates = segments.asSequence()
                        .filter { it.roadClass in 1..3 && it.points.size >= 2 }
                        .sortedBy { seg ->
                            seg.points.minOf { pt ->
                                calculateDistanceMeters(lat, lon, pt.latitudeE6 / 1e6, pt.longitudeE6 / 1e6)
                            }
                        }
                    var pointsRemaining = 192
                    for (seg in candidates) {
                        if (roadPolylines.size >= 24 || pointsRemaining < 2) break
                        val points = seg.points.take(pointsRemaining)
                        if (points.size < 2) continue
                        roadPolylines.add(MotoBLEMapRoadPolyline(roadClass = seg.roadClass, points = points))
                        pointsRemaining -= points.size
                    }
                }

                // An unavailable map tile must not erase a valid scene that
                // is already being displayed. Wait for real OSM data.
                if (roadPolylines.isEmpty()) return@launch

                val viewOrigin = GeoPointE6(latE6, lonE6)
                val scene = MotoBLEMapScene(
                    coordinateSystem = 2,
                    sceneRevision = System.currentTimeMillis() / 1000,
                    viewOrigin = viewOrigin,
                    radiusM = 500,
                    roads = roadPolylines,
                    buildings = emptyList()
                )

                bleManager.sendMapScene(scene)
            } catch (e: Exception) {
                Log.w("NavigationVM", "Error updating map scene and speed limit: ${e.message}")
            }
        }
    }

    private fun parseManeuverType(typeStr: String, modifierStr: String): String {
        val combined = "$typeStr $modifierStr".lowercase(Locale.ROOT)
        return when {
            combined.contains("uturn") || combined.contains("u_turn") || combined.contains("u-turn") -> "u_turn"
            combined.contains("roundabout") || combined.contains("rotary") -> "roundabout"
            combined.contains("arrive") || combined.contains("finish") || combined.contains("destination") -> "arrive"
            combined.contains("left") -> {
                if (combined.contains("slight") || combined.contains("keep")) "keep_left" else "turn_left"
            }
            combined.contains("right") -> {
                if (combined.contains("slight") || combined.contains("keep")) "keep_right" else "turn_right"
            }
            else -> "continue"
        }
    }

    private fun updateManeuverAndDistance(lat: Double, lon: Double): MotoBLENavigationSnapshot? {
        val baseSnapshot = currentSnapshot ?: return null
        // Recompute distance along the route at every GPS fix, even when
        // maneuver instructions are provided separately by the routing engine.
        val geometryPoints = currentGeometry?.points.orEmpty()
        var remainingMeters = baseSnapshot.remainingDistanceM
        if (geometryPoints.size >= 2) {
            var nearestSegment = 0
            var nearestDistance = Double.POSITIVE_INFINITY
            for (i in 0 until geometryPoints.size - 1) {
                val d = distanceToSegmentMeters(lat, lon, geometryPoints[i], geometryPoints[i + 1])
                if (d < nearestDistance) {
                    nearestDistance = d
                    nearestSegment = i
                }
            }
            val nextPoint = geometryPoints[nearestSegment + 1]
            remainingMeters = calculateDistanceMeters(
                lat, lon, nextPoint.latitudeE6 / 1e6, nextPoint.longitudeE6 / 1e6
            ).coerceAtLeast(0.0).toLong()
            for (i in nearestSegment + 1 until geometryPoints.size - 1) {
                val a = geometryPoints[i]
                val b = geometryPoints[i + 1]
                remainingMeters += calculateDistanceMeters(
                    a.latitudeE6 / 1e6, a.longitudeE6 / 1e6,
                    b.latitudeE6 / 1e6, b.longitudeE6 / 1e6
                ).toLong()
            }
        }
        val snapshot = baseSnapshot.copy(remainingDistanceM = remainingMeters)

        if (routeManeuverSteps.isNotEmpty() && activeStepIndex in routeManeuverSteps.indices) {
            val step = routeManeuverSteps[activeStepIndex]
            val distToStep = calculateDistanceMeters(lat, lon, step.latitude, step.longitude)

            if (distToStep < 15.0 && activeStepIndex < routeManeuverSteps.size - 1) {
                activeStepIndex++
                val nextStep = routeManeuverSteps[activeStepIndex]
                val newDist = calculateDistanceMeters(lat, lon, nextStep.latitude, nextStep.longitude)
                val instrText = if (nextStep.roadName.isNotBlank()) "${nextStep.roadName}\n${nextStep.instruction}" else nextStep.instruction
                return snapshot.copy(
                    maneuverName = nextStep.maneuverName,
                    distanceToManeuverM = newDist.toLong(),
                    instructionText = instrText
                )
            } else {
                val instrText = if (step.roadName.isNotBlank()) "${step.roadName}\n${step.instruction}" else step.instruction
                return snapshot.copy(
                    maneuverName = step.maneuverName,
                    distanceToManeuverM = distToStep.toLong(),
                    instructionText = instrText
                )
            }
        }

        // Geometry-based maneuver fallback
        val geom = currentGeometry ?: return null
        if (geom.points.size < 3) return null

        var closestIdx = 0
        var minDist = Double.MAX_VALUE
        for (i in geom.points.indices) {
            val pt = geom.points[i]
            val d = calculateDistanceMeters(lat, lon, pt.latitudeE6 / 1e6, pt.longitudeE6 / 1e6)
            if (d < minDist) {
                minDist = d
                closestIdx = i
            }
        }

        if (closestIdx >= geom.points.size - 1) {
            return snapshot.copy(
                maneuverName = "arrive",
                distanceToManeuverM = minDist.toLong(),
                instructionText = "Arrivo a destinazione"
            )
        }

        val targetIdx = (closestIdx + 2).coerceAtMost(geom.points.size - 1)
        val p1 = geom.points[closestIdx]
        val p2 = geom.points[(closestIdx + 1).coerceAtMost(geom.points.size - 1)]
        val p3 = geom.points[targetIdx]

        val b1 = calculateBearingDeg(p1.latitudeE6 / 1e6, p1.longitudeE6 / 1e6, p2.latitudeE6 / 1e6, p2.longitudeE6 / 1e6)
        val b2 = calculateBearingDeg(p2.latitudeE6 / 1e6, p2.longitudeE6 / 1e6, p3.latitudeE6 / 1e6, p3.longitudeE6 / 1e6)

        var deltaAngle = b2 - b1
        while (deltaAngle > 180f) deltaAngle -= 360f
        while (deltaAngle < -180f) deltaAngle += 360f

        val calculatedManeuver = when {
            deltaAngle < -45f -> "turn_left"
            deltaAngle in -45f..-20f -> "keep_left"
            deltaAngle in 20f..45f -> "keep_right"
            deltaAngle > 45f -> "turn_right"
            Math.abs(deltaAngle) > 135f -> "u_turn"
            else -> "continue"
        }

        val distToTurn = calculateDistanceMeters(lat, lon, p2.latitudeE6 / 1e6, p2.longitudeE6 / 1e6)
        return snapshot.copy(
            maneuverName = calculatedManeuver,
            distanceToManeuverM = distToTurn.toLong()
        )
    }

    fun cancelMapDownload() {
        mapDownloadJob?.cancel()
        mapDownloadJob = null
        _isDownloadingMap.value = false
        _downloadProgress.value = 0f
        _downloadStatus.value = "Download annullato"
    }

    private fun getOrCreateRepo(context: Context): HybridMapRepository {
        return HybridMapRepository(context.applicationContext)
    }

    fun refreshSavedMaps(context: Context) {
        val list = getOrCreateRepo(context).listSavedMaps()
        _savedMaps.value = list
    }

    fun downloadCurrentArea(context: Context) {
        mapDownloadJob?.cancel()
        mapDownloadJob = viewModelScope.launch {
            _isDownloadingMap.value = true
            _downloadProgress.value = 0.02f
            _downloadStatus.value = "Avvio download Area Attuale (50km)..."
            _errorMessage.value = null

            try {
                val (lat, lon) = currentLocation.value
                val repo = getOrCreateRepo(context)
                val res = withContext(Dispatchers.IO) {
                    repo.downloadCurrentArea(lat, lon) { step, total, name ->
                        val prog = step.toFloat() / total.toFloat()
                        _downloadProgress.value = prog
                        _downloadStatus.value = "Download $name: ${(prog * 100).toInt()}% (quadrante $step / $total)"
                    }
                }
                _downloadProgress.value = 1.0f
                val file = res.file
                val kb = maxOf(1L, file.length() / 1024)
                _downloadStatus.value = "Salvata ${file.name}: ${res.totalSegments} strade ($kb KB)"
                refreshSavedMaps(context)
            } catch (e: Exception) {
                _downloadStatus.value = "Errore download mappa: ${e.message}"
                _errorMessage.value = "Errore download mappa: ${e.message}"
            } finally {
                _isDownloadingMap.value = false
            }
        }
    }

    fun downloadRouteCorridor(context: Context, rawRoutePoints: List<Pair<Double, Double>>) {
        if (rawRoutePoints.isEmpty()) return
        mapDownloadJob?.cancel()
        mapDownloadJob = viewModelScope.launch {
            _isDownloadingMap.value = true
            _downloadProgress.value = 0.02f
            _downloadStatus.value = "Avvio download Corridoio Rotta..."
            _errorMessage.value = null

            try {
                val repo = getOrCreateRepo(context)
                val res = withContext(Dispatchers.IO) {
                    repo.downloadRouteCorridor(rawRoutePoints) { step, total, name ->
                        val prog = step.toFloat() / total.toFloat()
                        _downloadProgress.value = prog
                        _downloadStatus.value = "Download $name: ${(prog * 100).toInt()}% (tappa $step / $total)"
                    }
                }
                _downloadProgress.value = 1.0f
                val file = res.file
                val kb = maxOf(1L, file.length() / 1024)
                _downloadStatus.value = "Salvata ${file.name}: ${res.totalSegments} strade ($kb KB)"
                refreshSavedMaps(context)
            } catch (e: Exception) {
                _downloadStatus.value = "Errore download corridoio: ${e.message}"
                _errorMessage.value = "Errore download corridoio: ${e.message}"
            } finally {
                _isDownloadingMap.value = false
            }
        }
    }

    fun downloadRegion(context: Context, regionName: String) {
        mapDownloadJob?.cancel()
        mapDownloadJob = viewModelScope.launch {
            _isDownloadingMap.value = true
            _downloadProgress.value = 0.02f
            _downloadStatus.value = "Avvio download Regione $regionName..."
            _errorMessage.value = null

            try {
                val repo = getOrCreateRepo(context)
                val res = withContext(Dispatchers.IO) {
                    repo.downloadRegion(regionName) { step, total, name ->
                        val prog = step.toFloat() / total.toFloat()
                        _downloadProgress.value = prog
                        _downloadStatus.value = "Download $name: ${(prog * 100).toInt()}% (quadrante $step / $total)"
                    }
                }
                _downloadProgress.value = 1.0f
                val file = res.file
                val kb = maxOf(1L, file.length() / 1024)
                _downloadStatus.value = "Salvata ${file.name}: ${res.totalSegments} strade ($kb KB)"
                refreshSavedMaps(context)
            } catch (e: Exception) {
                _downloadStatus.value = "Errore download regione: ${e.message}"
                _errorMessage.value = "Errore download regione: ${e.message}"
            } finally {
                _isDownloadingMap.value = false
            }
        }
    }

    fun listSavedMaps(context: Context): List<SavedMapInfo> {
        val list = getOrCreateRepo(context).listSavedMaps()
        _savedMaps.value = list
        return list
    }

    fun deleteSavedMap(context: Context, fileName: String): Boolean {
        val result = getOrCreateRepo(context).deleteSavedMap(fileName)
        refreshSavedMaps(context)
        return result
    }

    // Approximate shortest distance to a polyline segment in local meters.
    private fun distanceToSegmentMeters(
        lat: Double, lon: Double, a: GeoPointE6, b: GeoPointE6
    ): Double {
        val scaleX = 111320.0 * kotlin.math.cos(Math.toRadians(lat))
        val scaleY = 111320.0
        val ax = (a.longitudeE6 / 1e6 - lon) * scaleX
        val ay = (a.latitudeE6 / 1e6 - lat) * scaleY
        val bx = (b.longitudeE6 / 1e6 - lon) * scaleX
        val by = (b.latitudeE6 / 1e6 - lat) * scaleY
        val dx = bx - ax
        val dy = by - ay
        val t = if (dx * dx + dy * dy > 0.001)
            (-(ax * dx + ay * dy) / (dx * dx + dy)).coerceIn(0.0, 1.0)
        else 0.0
        return kotlin.math.hypot(ax + t * dx, ay + t * dy)
    }

    private fun checkOffRouteAndRecalculate(latitude: Double, longitude: Double) {
        val dest = activeDestination ?: return
        val points = currentGeometry?.points ?: return
        if (points.size < 2 || rerouteInProgress) return

        // Do not mistake distance to the destination for distance to the route.
        var distanceFromRoute = Double.POSITIVE_INFINITY
        for (i in 1 until points.size) {
            distanceFromRoute = minOf(distanceFromRoute,
                distanceToSegmentMeters(latitude, longitude, points[i - 1], points[i]))
        }
        offRouteFixCount = if (distanceFromRoute > 35.0) offRouteFixCount + 1 else 0
        if (offRouteFixCount < 2) return

        val now = System.currentTimeMillis()
        if (now - lastRecalculationTime < 4000L) return
        lastRecalculationTime = now
        offRouteFixCount = 0
        rerouteInProgress = true
        viewModelScope.launch {
            try {
                val (snapshot, geometry) = withContext(Dispatchers.IO) {
                    performRoutingWithGeometry(dest, activeGatewayUrl)
                }
                currentSnapshot = snapshot
                currentGeometry = geometry
                if (geometry != null) bleManager.sendRouteGeometry(geometry)
                bleManager.sendNavigationSnapshot(snapshot)
            } catch (e: Exception) {
                Log.w("NavigationVM", "Route recalculation failed: ${e.message}")
            } finally {
                rerouteInProgress = false
            }
        }
    }

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    private fun computeFnv1aRouteToken(routeId: String): Long {
        var hash = 2166136261L
        for (char in routeId.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (char.toInt() and 0xFF).toLong()
            hash = (hash * 16777619L) and 0xFFFFFFFFL
        }
        return if (hash == 0L) 1L else hash
    }

    private fun fetchRouteOptions(place: PlaceSearchResult, gatewayBaseUrl: String): List<RouteOption> {
        val baseUrl = if (gatewayBaseUrl.endsWith("/")) gatewayBaseUrl else "$gatewayBaseUrl/"
        val endpoint = "${baseUrl}v1/route-options"

        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.doOutput = true

        val (originLat, originLon) = currentLocation.value

        val jsonPayload = JSONObject().apply {
            put("protocol_version", 1)
            put("request_id", System.currentTimeMillis().rem(Int.MAX_VALUE).toInt())
            put("route_mode", "driving")

            val originObj = JSONObject().apply {
                put("coordinate_system", "WGS84")
                put("longitude_deg", originLon)
                put("latitude_deg", originLat)
            }
            put("origin", originObj)

            val destObj = JSONObject().apply {
                put("coordinate_system", "WGS84")
                put("longitude_deg", place.longitude)
                put("latitude_deg", place.latitude)
            }
            put("destination", destObj)
            put("is_reroute", false)
        }

        connection.outputStream.use { os ->
            val input = jsonPayload.toString().toByteArray(Charsets.UTF_8)
            os.write(input, 0, input.size)
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(response)

                val routesArray = jsonObject.optJSONArray("routes")
                val results = mutableListOf<RouteOption>()

                if (routesArray != null && routesArray.length() > 0) {
                    for (i in 0 until routesArray.length()) {
                        val routeObj = routesArray.getJSONObject(i)
                        val summary = routeObj.optJSONObject("summary")
                        val dist = summary?.optLong("total_distance_meters", 0L) ?: routeObj.optLong("total_distance_m", routeObj.optLong("totalDistanceM", 0L))
                        val dur = summary?.optLong("estimated_duration_seconds", 0L) ?: routeObj.optLong("estimated_duration_s", routeObj.optLong("totalDurationS", 0L))

                        val stepsList = mutableListOf<String>()
                        val stepsArr = routeObj.optJSONArray("steps") ?: routeObj.optJSONArray("maneuvers")
                        if (stepsArr != null) {
                            for (j in 0 until stepsArr.length()) {
                                val stepObj = stepsArr.getJSONObject(j)
                                val instruction = stepObj.optString("instruction", stepObj.optString("name", ""))
                                if (instruction.isNotBlank()) {
                                    stepsList.add(instruction)
                                }
                            }
                        }

                        val title = if (i == 0) "Percorso consigliato" else "Percorso alternativo $i"
                        results.add(RouteOption("route_$i", title, dist, dur, stepsList, routeObj))
                    }
                } else if (jsonObject.has("route")) {
                    val routeObj = jsonObject.getJSONObject("route")
                    val dist = routeObj.optLong("total_distance_m", routeObj.optLong("totalDistanceM", 0L))
                    val dur = routeObj.optLong("estimated_duration_s", routeObj.optLong("totalDurationS", 0L))
                    results.add(RouteOption("route_0", "Percorso consigliato", dist, dur, emptyList(), routeObj))
                }
                return results
            } else {
                throw Exception("HTTP Error $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun performRoutingWithGeometry(place: PlaceSearchResult, gatewayBaseUrl: String): Pair<MotoBLENavigationSnapshot, MotoBLERouteGeometry?> {
        val baseUrl = if (gatewayBaseUrl.endsWith("/")) gatewayBaseUrl else "$gatewayBaseUrl/"
        val endpoint = "${baseUrl}v1/routes"

        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.doOutput = true

        val (originLat, originLon) = currentLocation.value

        val jsonPayload = JSONObject().apply {
            put("protocol_version", 1)
            put("request_id", System.currentTimeMillis().rem(Int.MAX_VALUE).toInt())
            put("route_mode", "driving")

            val originObj = JSONObject().apply {
                put("coordinate_system", "WGS84")
                put("longitude_deg", originLon)
                put("latitude_deg", originLat)
            }
            put("origin", originObj)

            val destObj = JSONObject().apply {
                put("coordinate_system", "WGS84")
                put("longitude_deg", place.longitude)
                put("latitude_deg", place.latitude)
            }
            put("destination", destObj)
            put("is_reroute", false)
        }

        connection.outputStream.use { os ->
            val input = jsonPayload.toString().toByteArray(Charsets.UTF_8)
            os.write(input, 0, input.size)
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(response)

                val protocolVersion = jsonObject.optInt("protocol_version", 1)
                if (protocolVersion != 1) {
                    throw Exception("Versione del protocollo non valida: $protocolVersion")
                }

                val routeObj = jsonObject.getJSONObject("route")
                val totalDistanceM = routeObj.optLong("total_distance_m", routeObj.optDouble("totalDistanceM", 0.0).toLong())
                val totalDurationS = routeObj.optLong("estimated_duration_s", routeObj.optInt("totalDurationS", 0).toLong())
                val routeID = routeObj.optString("route_id", routeObj.optString("routeID", "route_${System.currentTimeMillis()}"))

                var maneuverName = "continue"
                var instruction = "Segui il percorso"
                val maneuversArray = routeObj.optJSONArray("maneuvers")
                if (maneuversArray != null && maneuversArray.length() > 0) {
                    val firstManeuver = maneuversArray.getJSONObject(0)
                    maneuverName = firstManeuver.optString("type", "continue")
                    instruction = firstManeuver.optString("instruction", "Segui il percorso")
                }

                val routeToken = computeFnv1aRouteToken(routeID)

                // Extract geometry points (up to 24)
                val pointsE6 = mutableListOf<GeoPointE6>()
                val geomArray = routeObj.optJSONArray("geometry_gcj02")
                    ?: routeObj.optJSONArray("polyline")
                    ?: routeObj.optJSONArray("coordinates")

                if (geomArray != null) {
                    for (i in 0 until geomArray.length()) {
                        val item = geomArray.optJSONObject(i)
                        if (item != null) {
                            val lat = item.optDouble("latitude_deg", item.optDouble("latitude", Double.NaN))
                            val lon = item.optDouble("longitude_deg", item.optDouble("longitude", Double.NaN))
                            if (!lat.isNaN() && !lon.isNaN()) {
                                pointsE6.add(GeoPointE6((lat * 1e6).toInt(), (lon * 1e6).toInt()))
                            }
                        } else {
                            val coordArr = geomArray.optJSONArray(i)
                            if (coordArr != null && coordArr.length() >= 2) {
                                val lon = coordArr.optDouble(0, Double.NaN)
                                val lat = coordArr.optDouble(1, Double.NaN)
                                if (!lat.isNaN() && !lon.isNaN()) {
                                    pointsE6.add(GeoPointE6((lat * 1e6).toInt(), (lon * 1e6).toInt()))
                                }
                            }
                        }
                    }
                }

                if (pointsE6.isEmpty()) {
                    pointsE6.add(GeoPointE6((originLat * 1e6).toInt(), (originLon * 1e6).toInt()))
                    pointsE6.add(GeoPointE6((place.latitude * 1e6).toInt(), (place.longitude * 1e6).toInt()))
                }

                val boundedPoints = pointsE6.take(24)
                val viewOrigin = boundedPoints.first()

                val routeGeometry = MotoBLERouteGeometry(
                    coordinateSystem = 2,
                    routeToken = routeToken,
                    routeGeneration = 1L,
                    chunkIndex = 0,
                    chunkCount = 1,
                    firstPointIndex = 0,
                    totalPointCount = boundedPoints.size,
                    viewOrigin = viewOrigin,
                    points = boundedPoints
                )

                val snapshot = MotoBLENavigationSnapshot(
                    stateName = "navigating",
                    networkName = "online",
                    displayPageName = "navigation",
                    maneuverName = maneuverName,
                    trafficName = "free_flow",
                    hasDestination = true,
                    hasFix = hasFirstFix,
                    gnssStale = false,
                    offRoute = false,
                    routeRequestInFlight = false,
                    trafficRequestInFlight = false,
                    routeToken = routeToken,
                    routeGeneration = 1L,
                    maneuverID = 1L,
                    distanceToManeuverM = totalDistanceM,
                    remainingDistanceM = totalDistanceM,
                    remainingDurationS = totalDurationS,
                    routeProgressM = 0L,
                    totalDistanceM = totalDistanceM,
                    speedDeciKPH = currentSpeedDeciKph,
                    speedLimitKPH = lastSpeedLimitKph,
                    headingCentiDegrees = currentHeadingCentiDeg,
                    accuracyDecimeters = 50,
                    crossTrackDecimeters = 0,
                    roundaboutExit = 0,
                    instructionText = instruction
                )

                return Pair(snapshot, routeGeometry)
            } else {
                throw Exception("HTTP Error $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun performSearch(query: String, gatewayBaseUrl: String): List<PlaceSearchResult> {
        val baseUrl = if (gatewayBaseUrl.endsWith("/")) gatewayBaseUrl else "$gatewayBaseUrl/"
        val (originLat, originLon) = currentLocation.value
        val endpoint = "${baseUrl}v1/places?keywords=${URLEncoder.encode(query, "UTF-8")}&longitude_deg=$originLon&latitude_deg=$originLat"

        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        try {
            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(response)

                val protocolVersion = jsonObject.optInt("protocol_version", 1)
                if (protocolVersion != 1) {
                    throw Exception("Versione del protocollo non valida: $protocolVersion")
                }

                val placesArray = jsonObject.optJSONArray("places") ?: return emptyList()
                val results = mutableListOf<PlaceSearchResult>()

                for (i in 0 until placesArray.length()) {
                    val placeObj = placesArray.getJSONObject(i)
                    val name = placeObj.optString("name", "")
                    val address = placeObj.optString("address", "")
                    val displayArea = placeObj.optString("display_area", "")

                    val locationObj = placeObj.optJSONObject("location")
                    val lat = locationObj?.optDouble("latitude_deg", 0.0) ?: 0.0
                    val lon = locationObj?.optDouble("longitude_deg", 0.0) ?: 0.0

                    results.add(PlaceSearchResult(name, address, displayArea, lat, lon))
                }
                return results
            } else {
                throw Exception("HTTP Error $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }

    fun setConnectionState(state: String) {
        _connectionState.value = state
        val isConnected = state.contains("Connesso", ignoreCase = true) ||
                state.contains("Connected", ignoreCase = true) ||
                state.contains("Ready", ignoreCase = true) ||
                state.contains("Online", ignoreCase = true)
        if (isConnected) {
            startTelemetryLoop()
        } else {
            telemetryJob?.cancel()
            telemetryJob = null
        }
    }

    private fun startTelemetryLoop() {
        if (telemetryJob?.isActive == true) return
        telemetryJob = viewModelScope.launch {
            while (true) {
                delay(600)
                if (!_isNavigating.value && _connectionState.value.let {
                    it.contains("Connesso", ignoreCase = true) ||
                    it.contains("Connected", ignoreCase = true) ||
                    it.contains("Ready", ignoreCase = true) ||
                    it.contains("Online", ignoreCase = true)
                }) {
                    val pageName = when (_currentDevicePage.value) {
                        1 -> "speedometer"
                        2 -> "heading"
                        3 -> "music"
                        else -> "idle"
                    }
                    val idleSnapshot = MotoBLENavigationSnapshot(
                        stateName = "idle",
                        networkName = "online",
                        displayPageName = pageName,
                        maneuverName = "none",
                        trafficName = "none",
                        hasDestination = false,
                        hasFix = hasFirstFix,
                        gnssStale = false,
                        offRoute = false,
                        routeRequestInFlight = false,
                        trafficRequestInFlight = false,
                        routeToken = 0L,
                        routeGeneration = 0L,
                        maneuverID = 0L,
                        distanceToManeuverM = 0L,
                        remainingDistanceM = 0L,
                        remainingDurationS = 0L,
                        routeProgressM = 0L,
                        totalDistanceM = 0L,
                        speedDeciKPH = currentSpeedDeciKph,
                        speedLimitKPH = lastSpeedLimitKph,
                        headingCentiDegrees = currentHeadingCentiDeg,
                        accuracyDecimeters = 50,
                        crossTrackDecimeters = 0,
                        roundaboutExit = 0,
                        instructionText = ""
                    )
                    bleManager.sendNavigationSnapshot(idleSnapshot)
                }
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}

class NavigationViewModelFactory(private val bleManager: MotoBLEManager) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NavigationViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return NavigationViewModel(bleManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
