package com.motogps.android.viewmodel

import android.content.Context
import android.net.Uri
import com.motogps.android.map.MotomapPackImporter
import android.location.Location
import android.os.SystemClock
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
import com.motogps.android.location.LocationFixFilter
import com.motogps.android.routing.OfflineValhallaRouter
import com.motogps.android.routing.OnlineValhallaRouter
import com.motogps.android.routing.ValhallaGraphImporter
import com.motogps.android.routing.RoutePreferences
import com.motogps.android.routing.RouteStyle
import com.motogps.android.routing.ValhallaTurnGuidance
import com.motogps.android.routing.ValhallaRouteLocations
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
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

    private val _routeWaypoints = MutableStateFlow<List<PlaceSearchResult>>(emptyList())
    val routeWaypoints: StateFlow<List<PlaceSearchResult>> = _routeWaypoints
    private val _waypointSearchResults = MutableStateFlow<List<PlaceSearchResult>>(emptyList())
    val waypointSearchResults: StateFlow<List<PlaceSearchResult>> = _waypointSearchResults
    private val _waypointSearching = MutableStateFlow(false)
    val waypointSearching: StateFlow<Boolean> = _waypointSearching
    private val _waypointSearchError = MutableStateFlow<String?>(null)
    val waypointSearchError: StateFlow<String?> = _waypointSearchError
    private var waypointSearchJob: Job? = null
    private var activeWaypoints: List<PlaceSearchResult> = emptyList()
    private var nextWaypointIndex = 0

    fun searchWaypoint(query: String) {
        waypointSearchJob?.cancel()
        _waypointSearchResults.value = emptyList()
        _waypointSearchError.value = null
        if (query.isBlank()) {
            _waypointSearching.value = false
            return
        }
        OfflineCoordinateSearch.parse(query)?.let { place ->
            _waypointSearchResults.value = listOf(place)
            _waypointSearching.value = false
            return
        }
        waypointSearchJob = viewModelScope.launch {
            _waypointSearching.value = true
            try {
                val results = withContext(Dispatchers.IO) {
                    PhotonPlaceSearch.search(query,
                        if (hasFirstFix) currentLocation.value else null)
                }
                _waypointSearchResults.value = results
                if (results.isEmpty()) _waypointSearchError.value = "Nessun luogo trovato"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _waypointSearchError.value = "Ricerca tappa fallita: ${e.message}"
            } finally {
                _waypointSearching.value = false
            }
        }
    }

    fun addRouteWaypoint(place: PlaceSearchResult) {
        val destination = _routePreview.value ?: return
        if (_isNavigating.value) return
        if (_routeWaypoints.value.size >= ValhallaRouteLocations.MAX_STOPS) {
            _errorMessage.value = "Puoi inserire al massimo 8 tappe"
            return
        }
        fun same(a: PlaceSearchResult, b: PlaceSearchResult): Boolean =
            kotlin.math.abs(a.latitude - b.latitude) < 0.00001 &&
                kotlin.math.abs(a.longitude - b.longitude) < 0.00001
        if (same(destination, place) || _routeWaypoints.value.any { same(it, place) }) {
            _errorMessage.value = "La tappa è già presente nel percorso"
            return
        }
        _routeWaypoints.value = _routeWaypoints.value + place
        _waypointSearchResults.value = emptyList()
        _waypointSearchError.value = null
        previewRoute(destination)
    }

    fun removeRouteWaypoint(index: Int) {
        val destination = _routePreview.value ?: return
        if (_isNavigating.value || index !in _routeWaypoints.value.indices) return
        _routeWaypoints.value = _routeWaypoints.value.filterIndexed { i, _ -> i != index }
        previewRoute(destination)
    }

    fun moveRouteWaypoint(index: Int, direction: Int) {
        val destination = _routePreview.value ?: return
        if (_isNavigating.value || direction !in listOf(-1, 1)) return
        val list = _routeWaypoints.value.toMutableList()
        if (index !in list.indices || index + direction !in list.indices) return
        java.util.Collections.swap(list, index, index + direction)
        _routeWaypoints.value = list
        previewRoute(destination)
    }


    private val _routePreferences = MutableStateFlow(RoutePreferences())
    val routePreferences: StateFlow<RoutePreferences> = _routePreferences
    private var routePreviewJob: Job? = null

    fun loadRoutePreferences(context: Context) {
        val saved = context.applicationContext.getSharedPreferences(
            "moto_route_preferences", Context.MODE_PRIVATE)
        val style = RouteStyle.entries.firstOrNull {
            it.name == saved.getString("style", RouteStyle.FASTEST.name)
        } ?: RouteStyle.FASTEST
        _routePreferences.value = RoutePreferences(
            style,
            saved.getBoolean("avoid_highways", false),
            saved.getBoolean("avoid_tolls", false),
            saved.getBoolean("avoid_ferries", false),
            saved.getInt("curve_level", 0).coerceIn(0, 3)
        )
    }

    fun updateRoutePreferences(context: Context, preferences: RoutePreferences) {
        if (_isNavigating.value || _routePreferences.value == preferences) return
        _routePreferences.value = preferences
        context.applicationContext.getSharedPreferences(
            "moto_route_preferences", Context.MODE_PRIVATE).edit()
            .putString("style", preferences.style.name)
            .putBoolean("avoid_highways", preferences.avoidHighways)
            .putBoolean("avoid_tolls", preferences.avoidTolls)
            .putBoolean("avoid_ferries", preferences.avoidFerries)
            .putInt("curve_level", preferences.curveLevel)
            .apply()
        _routePreview.value?.let { previewRoute(it) }
    }

    private val _routeOptions = MutableStateFlow<List<RouteOption>>(emptyList())
    val routeOptions: StateFlow<List<RouteOption>> = _routeOptions
    private val _routePreviewError = MutableStateFlow<String?>(null)
    val routePreviewError: StateFlow<String?> = _routePreviewError
    private val _routingSource = MutableStateFlow<String?>(null)
    val routingSource: StateFlow<String?> = _routingSource

    private val _selectedRouteIndex = MutableStateFlow(0)
    val selectedRouteIndex: StateFlow<Int> = _selectedRouteIndex

    private val _isNavigating = MutableStateFlow(false)
    val isNavigating: StateFlow<Boolean> = _isNavigating

    private val _hasArrived = MutableStateFlow(false)
    val hasArrived: StateFlow<Boolean> = _hasArrived
    private val arrivalDetector = RouteArrivalDetector()

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
    private val _valhallaInstalledBytes = MutableStateFlow(0L)
    val valhallaInstalledBytes: StateFlow<Long> = _valhallaInstalledBytes
    private val _valhallaImporting = MutableStateFlow(false)
    val valhallaImporting: StateFlow<Boolean> = _valhallaImporting
    private val _valhallaStatus = MutableStateFlow<String?>(null)
    val valhallaStatus: StateFlow<String?> = _valhallaStatus
    private val valhallaCancel = AtomicBoolean(false)
    private var valhallaJob: Job? = null

    private var currentHeadingCentiDeg: Int = 0
    private var currentSpeedDeciKph: Int = 0
    private var prevLat: Double? = null
    private var prevLon: Double? = null
    private var isCompassSensorActive: Boolean = false
    private var telemetryJob: Job? = null

    private var navigationJob: Job? = null
    private var currentSnapshot: MotoBLENavigationSnapshot? = null
    private var currentGeometry: MotoBLERouteGeometry? = null

    private fun sendNearbyRouteGeometry(route: MotoBLERouteGeometry) {
        val (lat, lon) = currentLocation.value
        val window = RouteGeometryWindow.around(route, lat, lon)
        bleManager.sendRouteGeometry(window)
        val endpoint = route.points.lastOrNull()
        val endMeters = endpoint?.let {
            calculateDistanceMeters(lat, lon, it.latitudeE6 / 1e6, it.longitudeE6 / 1e6).toInt()
        } ?: -1
        Log.i("NavigationVM", "Route BLE window: ${window.points.size}/${route.points.size} points origin=$lat,$lon destinationPointDistance=${endMeters}m")
    }
    private var turnPlan = ValhallaTurnGuidance.Plan(0.0, emptyList())
    private var lastSpeedLimitKph = 0

    private val locationFixFilter = LocationFixFilter()
    private var lastLocationTimeMs: Long = 0L
    private var lastMapSceneSendTimeMs: Long = 0L
    private var lastMapSceneLat: Double = 0.0
    private var lastMapSceneLon: Double = 0.0
    private var mapSceneUpdateJob: Job? = null
    private var onlineTileJob: Job? = null
    private var lastOnlineTileAttemptMs: Long = 0L
    private var lastOnlineTileAttemptLat: Double = Double.NaN
    private var lastOnlineTileAttemptLon: Double = Double.NaN
    private var lastMapSceneRevision: Long = 0L
    private var navigationMapsContext: Context? = null

    init {
        bleManager.onDeviceCommandReceived = { cmd ->
            if (cmd.kind == 0) { // PageSelected
                _currentDevicePage.value = cmd.page
            }
        }
    }

    fun searchDestination(query: String) {
        if (query.isBlank()) return

        // Coordinates typed as "lat, lon" bypass Photon entirely.
        OfflineCoordinateSearch.parse(query)?.let { destination ->
            _searchResults.value = listOf(destination)
            _errorMessage.value = null
            _routePreview.value = null
            _routeOptions.value = emptyList()
            _routePreviewError.value = null
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _routePreview.value = null

            try {
                val results = withContext(Dispatchers.IO) {
                    PhotonPlaceSearch.search(query, if (hasFirstFix) currentLocation.value else null)
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

    fun previewRoute(place: PlaceSearchResult) {
        routePreviewJob?.cancel()
        _routePreview.value = place
        _routeOptions.value = emptyList()
        _selectedRouteIndex.value = 0
        _routingSource.value = null
        _routePreviewError.value = null
        _errorMessage.value = null

        routePreviewJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                val options = withContext(Dispatchers.IO) {
                    fetchRouteOptions(place)
                }
                _routeOptions.value = options
                _routingSource.value = if (options.isNotEmpty()) "Valhalla" else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _routePreviewError.value = e.message ?: "Impossibile calcolare il percorso Valhalla"
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
        _hasArrived.value = false
        arrivalDetector.reset()
        routePreviewJob?.cancel()
        waypointSearchJob?.cancel()
        _routeWaypoints.value = emptyList()
        _waypointSearchResults.value = emptyList()
        _waypointSearching.value = false
        _waypointSearchError.value = null
        routePreviewJob = null
        _routePreview.value = null
        _routeOptions.value = emptyList()
        _selectedRouteIndex.value = 0
        _routingSource.value = null
        _routePreviewError.value = null
    }

    private data class PlannedNavigation(
        val snapshot: MotoBLENavigationSnapshot,
        val geometry: MotoBLERouteGeometry?,
        val turnPlan: ValhallaTurnGuidance.Plan
    )

    fun startNavigation(place: PlaceSearchResult) {
        if (!hasFirstFix) {
            _errorMessage.value = "Attendi un segnale GPS valido prima di navigare"
            return
        }
        if (_routeOptions.value.isEmpty()) {
            _errorMessage.value = "Nessun percorso stradale calcolato. Installa un grafo Valhalla o verifica la connessione."
            return
        }
        if (_isLoading.value || _isNavigating.value) return

        _routePreviewError.value = null
        _hasArrived.value = false
        arrivalDetector.reset()

        navigationJob?.cancel()
        navigationJob = viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val (snapshot, geometry, guidance) = withContext(Dispatchers.IO) {
                    performRoutingWithGeometry(place)
                }

                activeDestination = place
                activeWaypoints = _routeWaypoints.value.toList()
                nextWaypointIndex = 0
                _isNavigating.value = true
                _currentDevicePage.value = 0 // Reset to navigation page on start
                currentSnapshot = snapshot
                currentGeometry = geometry
                turnPlan = guidance
                val (startLat, startLon) = currentLocation.value
                currentSnapshot = updateManeuverAndDistance(startLat, startLon)
                    ?: snapshot
                // Keep the source of the ACTUAL navigation route visible.
                _routingSource.value = lastRoutingProvider
                // Public Overpass is not suitable for downloading a complete
                // route in the background. A small GPS-local area is fetched
                // on demand and retained in the .motomap cache instead.
                if (geometry != null) {
                    sendNearbyRouteGeometry(geometry)
                }
                bleManager.sendNavigationSnapshot(requireNotNull(currentSnapshot))

                _isLoading.value = false

                var loopCount = 0
                var lastGeometryTransmissionMs = SystemClock.elapsedRealtime()
                while (_isNavigating.value) {
                    // A small navigation snapshot is replaceable in the BLE queue.
                    // Request 5 Hz near a turn, 3.3 Hz on open roads to avoid
                    // flooding GATT with fragmented route/map geometry.
                    val turnImminent = currentSnapshot?.let {
                        it.maneuverName !in setOf("continue", "none", "arrive") &&
                            it.distanceToManeuverM <= 100L
                    } ?: false
                    delay(if (turnImminent) 200L else 300L)
                    if (!_isNavigating.value) break
                    loopCount++

                    if (System.currentTimeMillis() - lastLocationTimeMs > 3000L) {
                        currentSpeedDeciKph = 0
                    }

                    val pageName = "navigation"

                    val (lat, lon) = currentLocation.value
                    val updatedSnap = if (_hasArrived.value)
                        (currentSnapshot?.copy() ?: snapshot.copy())
                    else updateManeuverAndDistance(lat, lon)
                        ?: currentSnapshot?.copy() ?: snapshot.copy()

                    val activeSnap = updatedSnap.copy(
                        stateName = if (_hasArrived.value) "arrived" else "navigating",
                        maneuverName = if (_hasArrived.value) "arrive" else updatedSnap.maneuverName,
                        distanceToManeuverM = if (_hasArrived.value) 0L else updatedSnap.distanceToManeuverM,
                        remainingDistanceM = if (_hasArrived.value) 0L else updatedSnap.remainingDistanceM,
                        remainingDurationS = if (_hasArrived.value) 0L else updatedSnap.remainingDurationS,
                        routeProgressM = if (_hasArrived.value) updatedSnap.totalDistanceM else updatedSnap.routeProgressM,
                        instructionText = if (_hasArrived.value) "Destinazione raggiunta"
                            else updatedSnap.instructionText,
                        displayPageName = pageName,
                        speedLimitKPH = lastSpeedLimitKph,
                        speedDeciKPH = currentSpeedDeciKph,
                        headingCentiDegrees = currentHeadingCentiDeg,
                        hasFix = hasFirstFix
                    )
                    currentSnapshot = activeSnap
                    bleManager.sendNavigationSnapshot(activeSnap)

                    // A stationary GPS provider may stop emitting fixes. Keep
                    // offline map lookup / online tile retry alive independently
                    // of location callbacks during an active route.
                    if (loopCount == 1 ||
                        System.currentTimeMillis() - lastMapSceneSendTimeMs >= 30_000L) {
                        val mapContext = navigationMapsContext
                        if (mapContext != null) {
                            val now = System.currentTimeMillis()
                            if (loopCount == 1 || now - lastMapSceneSendTimeMs >= 20_000L) {
                                lastMapSceneSendTimeMs = now
                                lastMapSceneLat = lat
                                lastMapSceneLon = lon
                                updateSpeedLimitAndMapScene(lat, lon, mapContext)
                            }
                        }
                    }

                    // Route windows are much larger than navigation snapshots.
                    // Update their GPS origin once per second around junctions;
                    // keep the former sparse cadence elsewhere for BLE headroom.
                    if (!_hasArrived.value) {
                        val activeGeom = currentGeometry ?: geometry
                        val now = SystemClock.elapsedRealtime()
                        val elapsed = now - lastGeometryTransmissionMs
                        if (activeGeom != null && elapsed >= 900L) {
                            val nearTurn = activeSnap.maneuverName !in
                                setOf("continue", "none", "arrive") &&
                                activeSnap.distanceToManeuverM <= 100L
                            val winding = !nearTurn && elapsed >= 1_400L &&
                                RouteGeometryWindow.isWinding(
                                    RouteGeometryWindow.around(activeGeom, lat, lon)
                                )
                            if (nearTurn || winding || elapsed >= 3_000L) {
                                sendNearbyRouteGeometry(activeGeom)
                                lastGeometryTransmissionMs = now
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _isNavigating.value = false
                _hasArrived.value = false
                arrivalDetector.reset()
                activeDestination = null
                _routePreviewError.value = e.message ?: "Navigazione Valhalla non disponibile"
                _errorMessage.value = "Errore durante la navigazione: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun stopNavigation() {
        _hasArrived.value = false
        arrivalDetector.reset()
        _isNavigating.value = false
        navigationJob?.cancel()
        navigationJob = null
        activeDestination = null
        activeWaypoints = emptyList()
        nextWaypointIndex = 0
        _routeWaypoints.value = emptyList()
        _routingSource.value = null
        currentSnapshot = null
        currentGeometry = null
        turnPlan = ValhallaTurnGuidance.Plan(0.0, emptyList())

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
    @Volatile private var lastRoutingProvider: String? = null
    private var lastRecalculationTime: Long = 0
    private var offRouteFixCount = 0
    private var offRouteFirstFixMs = 0L
    private var lastOffRouteFixMs = 0L
    private var lastGpsSpeedMs = 0f
    private var lastReliableGpsBearing: Float? = null
    private var lastReliableGpsBearingMs = 0L
    private var rerouteInProgress = false

    fun updateDeviceHeading(headingDeg: Float) {
        isCompassSensorActive = true
        val normalized = (headingDeg % 360f + 360f) % 360f
        currentHeadingCentiDeg = (normalized * 100f).toInt().coerceIn(0, 35999)
    }

    /**
     * Sole entry point for GPS/network provider updates. A rejected fix must
     * not move the rider, request OSM tiles or trigger an off-route recalculation.
     * Keeping the previous accepted origin also preserves the ESP32 route
     * window when Android reports a one-off network location kilometres away.
     */
    fun updateDeviceLocation(location: Location, context: Context? = null) {
        val fix = LocationFixFilter.Fix(
            latitude = location.latitude,
            longitude = location.longitude,
            elapsedRealtimeMs = location.elapsedRealtimeNanos / 1_000_000L,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else Float.POSITIVE_INFINITY,
            provider = location.provider.orEmpty(),
            speedMetersPerSecond = if (location.hasSpeed()) location.speed else 0f
        )
        val decision = locationFixFilter.evaluate(fix, SystemClock.elapsedRealtime())
        if (!decision.accepted) {
            Log.w("NavigationVM", "Location rejected: ${decision.reason} provider=${fix.provider} " +
                "accuracy=${fix.accuracyMeters.toInt()}m jump=${decision.distanceMeters.toInt()}m")
            return
        }
        updateAcceptedDeviceLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            speedMs = fix.speedMetersPerSecond,
            gpsBearingDeg = if (location.hasBearing()) location.bearing else null,
            context = context
        )
    }

    private fun updateAcceptedDeviceLocation(
        latitude: Double,
        longitude: Double,
        speedMs: Float,
        gpsBearingDeg: Float?,
        context: Context?
    ) {
        if (context != null) navigationMapsContext = context.applicationContext
        hasFirstFix = true
        _currentLocation.value = Pair(latitude, longitude)
        val now = System.currentTimeMillis()
        lastLocationTimeMs = now

        lastGpsSpeedMs = speedMs
        if (gpsBearingDeg != null && gpsBearingDeg.isFinite() && speedMs >= 3f) {
            lastReliableGpsBearing = (gpsBearingDeg % 360f + 360f) % 360f
            lastReliableGpsBearingMs = SystemClock.elapsedRealtime()
        }
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
            // Refresh the existing local MotoMap context earlier near a turn,
            // but never at the 200 ms snapshot cadence: map scenes are large.
            val nearTurn = currentSnapshot?.let {
                it.maneuverName !in setOf("continue", "none", "arrive") &&
                    it.distanceToManeuverM <= 110L
            } ?: false
            val sinceSceneMs = now - lastMapSceneSendTimeMs
            val minimumIntervalMs = if (nearTurn) 5_000L else 6_000L
            val movementThresholdM = if (nearTurn) 40.0 else 60.0
            if (sinceSceneMs >= minimumIntervalMs &&
                (distFromLastScene >= movementThresholdM || sinceSceneMs >= 25_000L)) {
                lastMapSceneSendTimeMs = now
                lastMapSceneLat = latitude
                lastMapSceneLon = longitude
                updateSpeedLimitAndMapScene(latitude, longitude, context)
            }
        }

        if (_isNavigating.value && !_hasArrived.value && activeDestination != null) {
            val reachedIndex = ValhallaRouteLocations.nextIndex(
                nextWaypointIndex, activeWaypoints.map { it.latitude to it.longitude },
                latitude, longitude)
            if (reachedIndex != nextWaypointIndex) {
                nextWaypointIndex = reachedIndex
                Log.i("NavigationVM", "Tappa completata: $nextWaypointIndex/${activeWaypoints.size}")
            }
            val destination = requireNotNull(activeDestination)
            val distanceToEnd = calculateDistanceMeters(
                latitude, longitude, destination.latitude, destination.longitude)
            // Avoid recomputing the entire remaining geometry while far away.
            val remainingRoadMeters = if (distanceToEnd <= 50.0 &&
                nextWaypointIndex >= activeWaypoints.size) {
                updateManeuverAndDistance(latitude, longitude)?.remainingDistanceM
                    ?: Long.MAX_VALUE
            } else Long.MAX_VALUE
            if (arrivalDetector.update(
                    distanceToEnd, remainingRoadMeters,
                    activeWaypoints.size - nextWaypointIndex,
                    SystemClock.elapsedRealtime())) {
                _hasArrived.value = true
                offRouteFixCount = 0
                rerouteInProgress = false
                val arrived = currentSnapshot?.copy(
                    stateName = "arrived",
                    maneuverName = "arrive",
                    remainingDistanceM = 0L,
                    remainingDurationS = 0L,
                    distanceToManeuverM = 0L,
                    routeProgressM = currentSnapshot?.totalDistanceM ?: 0L,
                    instructionText = "Destinazione raggiunta"
                )
                if (arrived != null) {
                    currentSnapshot = arrived
                    bleManager.sendNavigationSnapshot(arrived)
                }
                Log.i("NavigationVM", "Final destination reached")
            } else {
                checkOffRouteAndRecalculate(latitude, longitude)
            }
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
                val repository = getOrCreateRepo(context)
                val latE6 = (lat * 1e6).toInt()
                val lonE6 = (lon * 1e6).toInt()
                val deltaE6 = 5000

                val segments = repository.loadFromRegionalFiles(
                    queryMinLatE6 = latE6 - deltaE6,
                    queryMinLonE6 = lonE6 - deltaE6,
                    queryMaxLatE6 = latE6 + deltaE6,
                    queryMaxLonE6 = lonE6 + deltaE6
                )

                // Publish already cached vector geometry immediately; fetching a
                // missing tile must never block a display update for ~minutes.
                val usable = segments.filter { it.roadClass in 0..3 && it.points.size >= 2 }
                val closestMeters = usable.minOfOrNull { seg ->
                    seg.points.minOf { pt ->
                        calculateDistanceMeters(lat, lon, pt.latitudeE6 / 1e6, pt.longitudeE6 / 1e6)
                    }
                } ?: Double.POSITIVE_INFINITY
                if (usable.size < 5 || closestMeters > 250.0) {
                    requestOnlineTileInBackground(lat, lon, context)
                }

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
                    val roadSegments = segments.filter { it.roadClass in 0..3 && it.points.size >= 2 }
                    fun roadDistance(seg: com.motogps.android.map.MapRoadSegment, centerLat: Double, centerLon: Double) =
                        seg.points.minOf { pt ->
                            calculateDistanceMeters(centerLat, centerLon,
                                pt.latitudeE6 / 1e6, pt.longitudeE6 / 1e6)
                        }
                    val nearest = roadSegments.sortedBy { roadDistance(it, lat, lon) }
                    // Reserve some of the 12 BLE street slots for intersections
                    // ahead on the selected route. Otherwise the 12 nearest
                    // residential roads behind the rider hide roundabout exits
                    // until the last few metres.
                    val upcoming = currentGeometry?.let { geometry ->
                        val window = RouteGeometryWindow.around(geometry, lat, lon)
                        window.points.drop(1).firstOrNull { point ->
                            calculateDistanceMeters(lat, lon,
                                point.latitudeE6 / 1e6, point.longitudeE6 / 1e6) >= 160.0
                        } ?: window.points.lastOrNull()
                    }
                    val orderedRoads = LinkedHashSet<com.motogps.android.map.MapRoadSegment>()
                    nearest.take(6).forEach { orderedRoads.add(it) }
                    if (upcoming != null) {
                        roadSegments.sortedBy { roadDistance(it, upcoming.latitudeE6 / 1e6, upcoming.longitudeE6 / 1e6) }
                            .take(6).forEach { orderedRoads.add(it) }
                    }
                    nearest.forEach { orderedRoads.add(it) }
                    val candidates = orderedRoads.asSequence()
                    // Gradual increase after successful 8/64 field test.
                    // Stay well below the firmware ceiling of 24/192 to
                    // avoid BLE congestion from large fragmented scenes.
                    // Eight vertices per street preserve roundabout corners
                    // while leaving 96/8 = 12 slots for nearby and ahead roads.
                    var pointsRemaining = 96
                    for (seg in candidates) {
                        if (roadPolylines.size >= 12 || pointsRemaining < 2) break
                        // Each OSM way may begin kilometres away from the GPS fix.
                        // Select the nearby portion, retaining junction corners.
                        val points = RoadContextWindow.around(
                            seg.points, lat, lon, minOf(pointsRemaining, 8)
                        )
                        if (points.size < 2) continue
                        roadPolylines.add(MotoBLEMapRoadPolyline(roadClass = seg.roadClass, points = points))
                        pointsRemaining -= points.size
                    }
                }

                // An unavailable map tile must not erase a valid scene that
                // is already being displayed. Wait for real OSM data.
                if (roadPolylines.isEmpty()) {
                    Log.w("NavigationVM", "No road context for $lat,$lon: cached segments=${segments.size}")
                    return@launch
                }

                val viewOrigin = GeoPointE6(latE6, lonE6)
                lastMapSceneRevision = maxOf(lastMapSceneRevision + 1, System.currentTimeMillis() / 1000)
                val scene = MotoBLEMapScene(
                    coordinateSystem = 2,
                    sceneRevision = lastMapSceneRevision,
                    viewOrigin = viewOrigin,
                    radiusM = 500,
                    roads = roadPolylines,
                    buildings = emptyList()
                )

                Log.i("NavigationVM", "Context BLE MEDIUM(12/96): roads=${roadPolylines.size} points=${roadPolylines.sumOf { it.points.size }} sourceSegments=${segments.size}")
                bleManager.sendMapScene(scene)
            } catch (e: Exception) {
                Log.w("NavigationVM", "Error updating map scene and speed limit: ${e.message}")
            }
        }
    }

    private fun requestOnlineTileInBackground(lat: Double, lon: Double, context: Context) {
        val now = System.currentTimeMillis()
        val moved = lastOnlineTileAttemptLat.isNaN() ||
            calculateDistanceMeters(lat, lon, lastOnlineTileAttemptLat, lastOnlineTileAttemptLon) >= 400.0
        if (onlineTileJob?.isActive == true ||
            (!moved && now - lastOnlineTileAttemptMs < 60_000L)) return
        lastOnlineTileAttemptMs = now
        lastOnlineTileAttemptLat = lat
        lastOnlineTileAttemptLon = lon
        onlineTileJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val available = getOrCreateRepo(context).ensureOnlineTile(lat, lon)
                Log.i("NavigationVM", "OSM online tile available=$available at $lat,$lon")
                _downloadStatus.value = if (available)
                    "OSM: dati stradali ricevuti, aggiornamento ESP32..."
                else "OSM: nessuna strada scaricata per la posizione corrente"
                if (available) {
                    // Wait until the local read has finished, then publish the
                    // newly cached roads without waiting for another GPS fix.
                    mapSceneUpdateJob?.join()
                    val (currentLat, currentLon) = currentLocation.value
                    updateSpeedLimitAndMapScene(currentLat, currentLon, context)
                }
            } catch (e: Exception) {
                Log.w("NavigationVM", "OSM online tile fetch failed: ${e.message}")
                _downloadStatus.value = "Errore strade OSM: ${e.message}"
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

        // The next maneuver comes from Valhalla's explicit road geometry.
        // Its distance is measured along the same polyline used for the
        // existing destination-distance calculation, never as a straight
        // GPS-to-intersection shortcut.
        val upcoming = turnPlan.next(remainingMeters)
        if (upcoming != null) {
            val step = upcoming.action
            val instrText = if (step.roadName.isNotBlank())
                "${step.roadName}\n${step.instruction}" else step.instruction
            return snapshot.copy(
                maneuverName = step.type,
                maneuverID = step.index,
                distanceToManeuverM = upcoming.meters,
                roundaboutExit = step.roundaboutExit,
                instructionText = instrText
            )
        }

        // No actionable Valhalla maneuver remains. Do NOT resurrect the
        // deprecated three-vertex bearing heuristic from the OSRM era: it
        // invented turns at road bends and caused inaccurate ESP32 arrows.
        // The destination distance remains updated independently.
        return snapshot.copy(
            maneuverName = "continue",
            maneuverID = 0L,
            distanceToManeuverM = 0L,
            roundaboutExit = 0,
            instructionText = ""
        )
    }

    fun importValhallaGraph(context: Context, uri: Uri) {
        if (_valhallaImporting.value || _isNavigating.value) return
        val appContext = context.applicationContext
        valhallaCancel.set(false)
        valhallaJob = viewModelScope.launch {
            _valhallaImporting.value = true
            _valhallaStatus.value = "Importazione grafo: lettura archivio..."
            try {
                withContext(Dispatchers.IO) {
                    ValhallaGraphImporter.install(appContext, uri, { bytes ->
                        _valhallaStatus.value =
                            "Importazione grafo: " + (bytes / 1_000_000L) + " MB elaborati"
                    }, { valhallaCancel.get() || _isNavigating.value })
                }
                _valhallaInstalledBytes.value =
                    OfflineValhallaRouter.tileFile(appContext).length()
                _valhallaStatus.value = "Grafo Valhalla importato. Puoi provare la navigazione."
            } catch (e: Exception) {
                _valhallaStatus.value = "Importazione grafo fallita: " + e.message
            } finally {
                _valhallaImporting.value = false
                valhallaJob = null
            }
        }
    }

    fun cancelValhallaImport() {
        valhallaCancel.set(true)
        _valhallaStatus.value = "Annullamento richiesto..."
    }

    fun deleteValhallaGraph(context: Context) {
        if (_isNavigating.value || _valhallaImporting.value) return
        viewModelScope.launch(Dispatchers.IO) {
            OfflineValhallaRouter.close()
            val file = OfflineValhallaRouter.tileFile(context.applicationContext)
            val deleted = !file.exists() || file.delete()
            _valhallaInstalledBytes.value = if (deleted) 0L else file.length()
            _valhallaStatus.value = if (deleted) "Grafo Valhalla eliminato"
                else "Impossibile eliminare il grafo Valhalla"
        }
    }

    fun cancelMapDownload() {
        mapDownloadJob?.cancel()
        mapDownloadJob = null
        _isDownloadingMap.value = false
        _downloadProgress.value = 0f
        _downloadStatus.value = "Download annullato"
    }

    private fun getOrCreateRepo(context: Context): HybridMapRepository =
        HybridMapRepository(context.applicationContext)

    fun importOsmRoadPack(context: Context, uri: Uri) {
        if (_isDownloadingMap.value) {
            _errorMessage.value = "Un download mappe è già in corso"
            return
        }
        mapDownloadJob?.cancel()
        mapDownloadJob = viewModelScope.launch {
            _isDownloadingMap.value = true
            _downloadProgress.value = 0f
            _downloadStatus.value = "Importazione pacchetto strade OSM..."
            try {
                val tiles = withContext(Dispatchers.IO) {
                    MotomapPackImporter.install(context.applicationContext, uri) { count ->
                        _downloadStatus.value = "Importate $count tile OSM..."
                    }
                }
                _downloadProgress.value = 1f
                _downloadStatus.value = "Pacchetto OSM importato: $tiles tile disponibili"
                refreshSavedMaps(context)
                val (lat, lon) = currentLocation.value
                if (hasFirstFix) updateSpeedLimitAndMapScene(lat, lon, context.applicationContext)
            } catch (e: Exception) {
                _downloadStatus.value = "Errore importazione OSM: ${e.message}"
                _errorMessage.value = _downloadStatus.value
            } finally {
                _isDownloadingMap.value = false
            }
        }
    }

    fun refreshSavedMaps(context: Context) {
        _valhallaInstalledBytes.value = OfflineValhallaRouter.tileFile(context)
            .let { if (it.isFile) it.length() else 0L }
        val list = getOrCreateRepo(context).listSavedMaps()
        _savedMaps.value = list
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
        val now = System.currentTimeMillis()
        // Nearby parallel roads and short GPS jumps must not trigger rerouting.
        // Demand four consecutive, time-separated moving fixes at least 60 m
        // from the actual route, sustained for >= 6 seconds.
        if (distanceFromRoute <= 60.0 || lastGpsSpeedMs < 1.5f) {
            offRouteFixCount = 0
            offRouteFirstFixMs = 0L
            return
        }
        if (now - lastOffRouteFixMs > 5_000L) {
            offRouteFixCount = 0
            offRouteFirstFixMs = 0L
        }
        if (offRouteFixCount == 0) offRouteFirstFixMs = now
        offRouteFixCount++
        lastOffRouteFixMs = now
        if (offRouteFixCount < 4 || now - offRouteFirstFixMs < 6_000L) return

        if (now - lastRecalculationTime < 15_000L) return
        lastRecalculationTime = now
        offRouteFixCount = 0
        offRouteFirstFixMs = 0L
        rerouteInProgress = true
        viewModelScope.launch {
            try {
                val (snapshot, geometry, guidance) = withContext(Dispatchers.IO) {
                    performRoutingWithGeometry(dest, isReroute = true)
                }
                // The rider may reach the finish while a reroute was calculating.
                // Never replace an 'arrived' snapshot with stale guidance.
                if (_hasArrived.value || !_isNavigating.value) return@launch
                currentSnapshot = snapshot
                currentGeometry = geometry
                turnPlan = guidance
                val refreshed = updateManeuverAndDistance(latitude, longitude)
                    ?: snapshot
                currentSnapshot = refreshed
                _routingSource.value = lastRoutingProvider
                if (geometry != null) sendNearbyRouteGeometry(geometry)
                bleManager.sendNavigationSnapshot(refreshed)
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

    /** One routing policy for preview, initial turn-by-turn, and reroutes.
     * Local graph always gets first attempt; network is only a Valhalla fallback.
     */
    private fun resolveRoute(
        originLat: Double, originLon: Double, place: PlaceSearchResult,
        isReroute: Boolean = false, bearing: Float? = null,
        waypoints: List<PlaceSearchResult> = _routeWaypoints.value
    ): JSONObject {
        var localError = "nessuna regione Valhalla installata"
        val context = navigationMapsContext
        if (context != null && OfflineValhallaRouter.hasTiles(context)) {
            try {
                val result = OfflineValhallaRouter.route(
                    context, originLat, originLon, place.latitude, place.longitude,
                    isReroute, bearing, _routePreferences.value,
                    waypoints.map { it.latitude to it.longitude })
                if (result != null) {
                    Log.i("NavigationVM", "Routing: Valhalla offline")
                    return result
                }
                localError = "nessun percorso nel grafo locale"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                localError = e.message ?: "grafo locale non utilizzabile"
                Log.w("NavigationVM", "Local Valhalla unavailable: $localError")
            }
        }
        return try {
            val result = OnlineValhallaRouter.route(
                originLat, originLon, place.latitude, place.longitude, isReroute, bearing,
                _routePreferences.value, waypoints.map { it.latitude to it.longitude })
            Log.i("NavigationVM", "Routing: Valhalla online (local: $localError)")
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(
                "Grafo locale: $localError; servizio di routing: ${e.message}. " +
                    "Controlla connessione o scarica la regione.", e)
        }
    }

    private fun fetchRouteOptions(place: PlaceSearchResult): List<RouteOption> {
        if (!hasFirstFix)
            throw IllegalStateException("Attendi un fix GPS valido prima del calcolo")
        val (lat, lon) = currentLocation.value
        val response = resolveRoute(lat, lon, place)
        val route = response.getJSONObject("route")
        val maneuvers = route.optJSONArray("maneuvers")
        val instructions = (0 until (maneuvers?.length() ?: 0)).map { i ->
            maneuvers!!.getJSONObject(i).optString("instruction", "")
        }.filter { it.isNotBlank() }
        return listOf(RouteOption(
            "valhalla-0",
            "Percorso Valhalla",
            route.getLong("total_distance_m"), route.getLong("estimated_duration_s"),
            instructions, route))
    }

    private fun performRoutingWithGeometry(
        place: PlaceSearchResult, isReroute: Boolean = false
    ): PlannedNavigation {
        if (!hasFirstFix)
            throw IllegalStateException("Fix GPS assente: impossibile navigare")
        val (originLat, originLon) = currentLocation.value
        val bearing = if (isReroute && lastGpsSpeedMs >= 3f &&
            SystemClock.elapsedRealtime() - lastReliableGpsBearingMs in 0L..5_000L)
            lastReliableGpsBearing else null
        val pending = if (isReroute) activeWaypoints.drop(nextWaypointIndex)
            else _routeWaypoints.value
        val result = resolveRoute(originLat, originLon, place, isReroute, bearing, pending)
        lastRoutingProvider = "Valhalla"
        val (snapshot, geometry) = parseMotoRouteResponse(
            result, originLat, originLon, place)
        return PlannedNavigation(
            snapshot, geometry,
            ValhallaTurnGuidance.fromRoute(result.getJSONObject("route")))
    }

    private fun parseMotoRouteResponse(
        jsonObject: JSONObject, originLat: Double, originLon: Double, place: PlaceSearchResult
    ): Pair<MotoBLENavigationSnapshot, MotoBLERouteGeometry?> {
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

    // Keep ALL route vertices on Android; only the BLE display window is bounded.
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

    val fullRoutePoints = pointsE6.toList()
    val viewOrigin = fullRoutePoints.first()
    var geometryDistanceM = 0.0
    for (i in 1 until fullRoutePoints.size) {
        val from = fullRoutePoints[i - 1]
        val to = fullRoutePoints[i]
        geometryDistanceM += calculateDistanceMeters(
            from.latitudeE6 / 1e6, from.longitudeE6 / 1e6,
            to.latitudeE6 / 1e6, to.longitudeE6 / 1e6
        )
    }
    Log.i("NavigationVM", "Route source: vertices=${fullRoutePoints.size} geometryMeters=${geometryDistanceM.toInt()} providerMeters=$totalDistanceM")

    val routeGeometry = MotoBLERouteGeometry(
        coordinateSystem = 2,
        routeToken = routeToken,
        routeGeneration = 1L,
        chunkIndex = 0,
        chunkCount = 1,
        firstPointIndex = 0,
        totalPointCount = fullRoutePoints.size,
        viewOrigin = viewOrigin,
        points = fullRoutePoints
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
