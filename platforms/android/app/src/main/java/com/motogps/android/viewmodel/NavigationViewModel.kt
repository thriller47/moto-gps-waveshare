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
import com.motogps.android.diagnostics.DiagnosticLog
import com.motogps.android.diagnostics.ReplayTraceRecorder
import com.motogps.android.jni.GeoPointE6
import com.motogps.android.jni.MotoBLEMapRoadPolyline
import com.motogps.android.jni.MotoBLEMapScene
import com.motogps.android.jni.MotoBLENavigationSnapshot
import com.motogps.android.jni.MotoBLERouteGeometry
import com.motogps.android.map.HybridMapRepository
import com.motogps.android.map.RoadContextJunctions
import com.motogps.android.map.OpenFreeMapTileProbe
import com.motogps.android.map.OpenFreeMapRouteCorridor
import com.motogps.android.map.SavedMapInfo
import com.motogps.android.location.LocationFixFilter
import com.motogps.android.location.NavigationFixFreshness
import com.motogps.android.routing.OfflineValhallaRouter
import com.motogps.android.routing.OnlineValhallaRouter
import com.motogps.android.routing.ValhallaGraphImporter
import com.motogps.android.routing.RoutePreferences
import com.motogps.android.routing.RouteStyle
import com.motogps.android.routing.ValhallaTurnGuidance
import com.motogps.android.routing.ValhallaRouteLocations
import com.motogps.android.routing.RerouteDepartureDirection
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

class NavigationViewModel(internal val bleManager: MotoBLEManager, private val appContext: Context? = null) : ViewModel() {
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
    // Short-lived display-only notice; does not change route progress or maneuvers.
    private var waypointNoticeIndex = 0
    private var waypointNoticeTotal = 0
    private var waypointNoticeStartedMs = 0L

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
                    searchPhotonPlaces(query)
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
        DiagnosticLog.event("WAYPOINT_EDIT", "action=add count=${_routeWaypoints.value.size}")
        _waypointSearchResults.value = emptyList()
        _waypointSearchError.value = null
        previewRoute(destination)
    }

    fun removeRouteWaypoint(index: Int) {
        val destination = _routePreview.value ?: return
        if (_isNavigating.value || index !in _routeWaypoints.value.indices) return
        _routeWaypoints.value = _routeWaypoints.value.filterIndexed { i, _ -> i != index }
        DiagnosticLog.event("WAYPOINT_EDIT", "action=remove count=${_routeWaypoints.value.size}")
        previewRoute(destination)
    }

    fun moveRouteWaypoint(index: Int, direction: Int) {
        val destination = _routePreview.value ?: return
        if (_isNavigating.value || direction !in listOf(-1, 1)) return
        val list = _routeWaypoints.value.toMutableList()
        if (index !in list.indices || index + direction !in list.indices) return
        java.util.Collections.swap(list, index, index + direction)
        _routeWaypoints.value = list
        DiagnosticLog.event("WAYPOINT_EDIT", "action=move count=${_routeWaypoints.value.size}")
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
    private val _photonInstalledBytes = MutableStateFlow(0L)
    val photonInstalledBytes: StateFlow<Long> = _photonInstalledBytes
    private val _photonImporting = MutableStateFlow(false)
    val photonImporting: StateFlow<Boolean> = _photonImporting
    private val _photonStatus = MutableStateFlow<String?>(null)
    val photonStatus: StateFlow<String?> = _photonStatus

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
    private var lastWindowGapDiagnosticMs = 0L

    private fun sendNearbyRouteGeometry(route: MotoBLERouteGeometry) {
        val (lat, lon) = currentLocation.value
        val window = RouteGeometryWindow.around(
            route, lat, lon, routeProgressMatcher.matchedProgressMeters())
        val first = window.points.firstOrNull()
        if (first != null) {
            val gapMeters = calculateDistanceMeters(
                lat, lon, first.latitudeE6 / 1e6, first.longitudeE6 / 1e6)
            val nowMs = SystemClock.elapsedRealtime()
            if (gapMeters >= 35.0 && nowMs - lastWindowGapDiagnosticMs >= 5_000L) {
                lastWindowGapDiagnosticMs = nowMs
                DiagnosticLog.event("ROUTE_VIEW_GAP",
                    "start_gap_m=${gapMeters.toInt()} points=${window.points.size} " +
                        "route_token=${route.routeToken}")
            }
        }
        ReplayTraceRecorder.route(window)
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
    private val routeDistanceGuard = RouteDistanceGuard()
    private val routeProgressMatcher = RouteProgressMatcher()
    private val rerouteStability = RerouteStabilityPolicy()
    private var lastLocationTimeMs: Long = 0L
    private var lastFixElapsedMs: Long = 0L
    private var lastFixAccuracyM: Float = Float.POSITIVE_INFINITY
    private var lastFixCourseDeg: Float? = null
    private var lastDiagnosticFixMs: Long = 0L
    private var lastDiagnosticLatencyMs: Long = 0L
    private var lastAcceptedFixArrivalMs: Long = 0L
    private var lastDiagnosticHeadingMs: Long = 0L
    private var lastMapSceneSendTimeMs: Long = 0L
    private var lastMapSceneLat: Double = 0.0
    private var lastMapSceneLon: Double = 0.0
    private var mapSceneUpdateJob: Job? = null
    private var lastMapSceneRevision: Long = 0L
    private var navigationMapsContext: Context? = null

    init {
        bleManager.onDeviceCommandReceived = { cmd ->
            if (cmd.kind == 0) { // PageSelected
                _currentDevicePage.value = cmd.page
            }
        }
    }

    /** Installed Photon index takes precedence, with no network fallback on a miss. */
    private fun searchPhotonPlaces(query: String): List<PlaceSearchResult> {
        val gps = if (hasFirstFix) currentLocation.value else null
        val context = appContext
        return if (context != null && OfflinePhotonPlaceSearch.installed(context))
            OfflinePhotonPlaceSearch.search(context, query, gps)
        else PhotonPlaceSearch.search(query, gps)
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
                    searchPhotonPlaces(query)
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
        DiagnosticLog.event("ROUTE_PREVIEW", "begin stops=${_routeWaypoints.value.size}")
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
                DiagnosticLog.event("ROUTE_PREVIEW",
                    "ready stops=${_routeWaypoints.value.size} options=${options.size}")
                _routingSource.value = if (options.isNotEmpty()) "Valhalla" else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _routePreviewError.value = e.message ?: "Impossibile calcolare il percorso Valhalla"
                DiagnosticLog.event("ROUTE_PREVIEW_ERROR",
                    "stops=${_routeWaypoints.value.size} exception=${e.javaClass.simpleName}")
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
        rerouteStability.reset()
        lastRecalculationTime = 0L
        offRouteFixCount = 0
        offRouteFirstFixMs = 0L

        navigationJob?.cancel()
        DiagnosticLog.event("ROUTE_START", "begin stops=${_routeWaypoints.value.size}")
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
                waypointNoticeIndex = 0
                waypointNoticeTotal = 0
                waypointNoticeStartedMs = 0L
                _isNavigating.value = true
                _currentDevicePage.value = 0 // Reset to navigation page on start
                currentSnapshot = snapshot
                currentGeometry = geometry
                turnPlan = guidance
                DiagnosticLog.event("ROUTE_START",
                    "ready stops=${activeWaypoints.size} route_m=${snapshot.totalDistanceM} " +
                        "points=${geometry?.points?.size ?: 0} provider=${lastRoutingProvider.orEmpty()}")
                routeDistanceGuard.reset()
                routeProgressMatcher.reset(geometry?.points.orEmpty())
                val (startLat, startLon) = currentLocation.value
                currentSnapshot = updateManeuverAndDistance(startLat, startLon)
                    ?: snapshot
                // Keep the source of the ACTUAL navigation route visible.
                _routingSource.value = lastRoutingProvider
                // Road context is loaded locally from MotoMap/previously downloaded
                // OpenFreeMap tiles; no implicit network requests are made.
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
                    delay(if (turnImminent) 200L else 250L)
                    if (!_isNavigating.value) break
                    loopCount++

                    if (System.currentTimeMillis() - lastLocationTimeMs > 3000L) {
                        currentSpeedDeciKph = 0
                    }

                    val pageName = "navigation"

                    val (lat, lon) = currentLocation.value
                    val ageMs = SystemClock.elapsedRealtime() - lastFixElapsedMs
                    val gnssStale = NavigationFixFreshness.isStale(hasFirstFix, ageMs)
                    // GPS accepted by LocationFixFilter is authoritative for
                    // route progress, distances, maneuvers and recorded replay.
                    // Prediction belongs exclusively to the ESP32 map renderer.
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
                            else WaypointReachedNotice.message(
                                waypointNoticeIndex, waypointNoticeTotal,
                                waypointNoticeStartedMs, SystemClock.elapsedRealtime())
                                ?: updatedSnap.instructionText,
                        displayPageName = pageName,
                        speedLimitKPH = lastSpeedLimitKph,
                        speedDeciKPH = currentSpeedDeciKph,
                        headingCentiDegrees = currentHeadingCentiDeg,
                        hasFix = hasFirstFix,
                        gnssStale = gnssStale
                    )
                    currentSnapshot = activeSnap
                    if (loopCount == 1 || loopCount % 4 == 0) {
                        DiagnosticLog.event("NAV_SNAPSHOT",
                            "maneuver=${activeSnap.maneuverName} id=${activeSnap.maneuverID} " +
                            "turn_m=${activeSnap.distanceToManeuverM} remain_m=${activeSnap.remainingDistanceM} " +
                            "speed_dkph=${activeSnap.speedDeciKPH} heading_cdeg=${activeSnap.headingCentiDegrees} " +
                            "fix_age_ms=$ageMs received_ago_ms=${SystemClock.elapsedRealtime() - lastAcceptedFixArrivalMs} " +
                            "gnss_stale=$gnssStale position=accepted_gps")
                    }
                    ReplayTraceRecorder.snapshot(activeSnap, lat, lon)
                    bleManager.sendNavigationSnapshot(activeSnap)

                    // A stationary GPS provider may stop emitting fixes. Keep
                    // offline map lookup / online tile retry alive independently
                    // of location callbacks during an active route.
                    if (loopCount == 1 ||
                        System.currentTimeMillis() - lastMapSceneSendTimeMs >= 20_000L) {
                        val mapContext = navigationMapsContext
                        if (mapContext != null) {
                            val now = System.currentTimeMillis()
                            if (loopCount == 1 || now - lastMapSceneSendTimeMs >= 15_000L) {
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
                DiagnosticLog.event("ROUTE_START_ERROR",
                    "stops=${activeWaypoints.size} exception=${e.javaClass.simpleName}")
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
        waypointNoticeIndex = 0
        waypointNoticeTotal = 0
        waypointNoticeStartedMs = 0L
        _routeWaypoints.value = emptyList()
        _routingSource.value = null
        currentSnapshot = null
        currentGeometry = null
        turnPlan = ValhallaTurnGuidance.Plan(0.0, emptyList())
        routeDistanceGuard.reset()
        routeProgressMatcher.reset()
        rerouteStability.reset()
        lastRecalculationTime = 0L
        offRouteFixCount = 0
        offRouteFirstFixMs = 0L

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
        // A phone lying on a car seat is not aligned with the direction of
        // travel. After the first accepted GPS fix, never let its magnetic
        // azimuth overwrite the course used to rotate the ESP32 route.
        val applySensor = NavigationHeadingPolicy.usePhoneSensor(
            hasFirstFix = hasFirstFix, isNavigating = _isNavigating.value)
        val normalized = NavigationHeadingPolicy.normalized(headingDeg)
        if (applySensor && normalized != null) {
            currentHeadingCentiDeg = (normalized * 100f).toInt().coerceIn(0, 35999)
        }
        val now = System.currentTimeMillis()
        if (now - lastDiagnosticHeadingMs >= 3000L) {
            DiagnosticLog.event(
                if (applySensor) "PHONE_SENSOR_HEADING" else "PHONE_SENSOR_IGNORED",
                "sensor_cdeg=${normalized?.times(100f)?.toInt() ?: -1} " +
                    "selected_cdeg=$currentHeadingCentiDeg"
            )
            lastDiagnosticHeadingMs = now
        }
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
        val receivedAtMs = SystemClock.elapsedRealtime()
        val decision = locationFixFilter.evaluate(fix, receivedAtMs)
        if (!decision.accepted) {
            Log.w("NavigationVM", "Location rejected: ${decision.reason} provider=${fix.provider} " +
                "accuracy=${fix.accuracyMeters.toInt()}m jump=${decision.distanceMeters.toInt()}m")
            return
        }
        lastAcceptedFixArrivalMs = receivedAtMs
        lastFixElapsedMs = fix.elapsedRealtimeMs
        lastFixAccuracyM = fix.accuracyMeters
        lastFixCourseDeg = if (location.hasBearing()) location.bearing else null
        if (receivedAtMs - lastDiagnosticLatencyMs >= 1000L) {
            lastDiagnosticLatencyMs = receivedAtMs
            DiagnosticLog.event("GPS_LATENCY",
                "age_ms=${receivedAtMs - fix.elapsedRealtimeMs} provider=${fix.provider} " +
                    "accuracy_m=${fix.accuracyMeters} speed_mps=${fix.speedMetersPerSecond}")
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

        val displacementM = if (prevLat != null && prevLon != null)
            calculateDistanceMeters(prevLat!!, prevLon!!, latitude, longitude)
        else 0.0
        val displacementCourse = if (displacementM >= 4.0)
            calculateBearingDeg(prevLat!!, prevLon!!, latitude, longitude)
        else null
        val travelCourse = NavigationHeadingPolicy.movingHeading(
            speedMs, gpsBearingDeg, displacementM, displacementCourse)
        if (travelCourse != null) {
            currentHeadingCentiDeg = (travelCourse * 100f).toInt().coerceIn(0, 35999)
            // When the GNSS provider has no bearing, two real fixes still
            // establish a reliable travel direction for the next reroute.
            if (speedMs >= 3f && displacementM >= 6.0) {
                lastReliableGpsBearing = travelCourse
                lastReliableGpsBearingMs = SystemClock.elapsedRealtime()
            }
        }
        // Log the course AFTER arbitration, not the previously selected
        // compass heading from before this GPS callback.
        if (now - lastDiagnosticFixMs >= 1000L) {
            DiagnosticLog.event("GPS_FIX",
                "provider_course=${gpsBearingDeg ?: "none"} speed_mps=$speedMs " +
                    "heading_cdeg=$currentHeadingCentiDeg source=${if (travelCourse != null) "movement" else "hold"}")
            lastDiagnosticFixMs = now
        }

        ReplayTraceRecorder.fix(latitude, longitude, speedMs, gpsBearingDeg, lastFixAccuracyM)
        prevLat = latitude
        prevLon = longitude

        if (context != null) {
            val distFromLastScene = calculateDistanceMeters(lastMapSceneLat, lastMapSceneLon, latitude, longitude)
            // Refresh the existing local MotoMap context earlier near a turn,
            // but never at the 200 ms snapshot cadence: map scenes are large.
            val refresh = MapSceneRefreshPolicy.limits(
                currentSnapshot?.maneuverName,
                currentSnapshot?.distanceToManeuverM ?: Long.MAX_VALUE,
                currentSnapshot?.roundaboutExit ?: 0)
            val sinceSceneMs = now - lastMapSceneSendTimeMs
            // Near an identified roundabout, refresh sooner as the motorcycle
            // enters the junction. Keep the ordinary rate limits elsewhere.
            if (sinceSceneMs >= refresh.minimumIntervalMs &&
                (distFromLastScene >= refresh.movementThresholdM ||
                    sinceSceneMs >= 25_000L)) {
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
                waypointNoticeIndex = nextWaypointIndex
                waypointNoticeTotal = activeWaypoints.size
                waypointNoticeStartedMs = SystemClock.elapsedRealtime()
                Log.i("NavigationVM", "Tappa completata: $nextWaypointIndex/${activeWaypoints.size}")
                DiagnosticLog.event("WAYPOINT_REACHED",
                    "index=$nextWaypointIndex total=${activeWaypoints.size}")
                ReplayTraceRecorder.waypointReached(nextWaypointIndex, activeWaypoints.size)
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
                DiagnosticLog.event("OSM_CONTEXT", "segments=${segments.size} usable=${usable.size} " +
                    "nearest_m=${if (closestMeters.isFinite()) closestMeters.toInt() else -1}")

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

                    // This trial deliberately leaves layer/grade filtering OFF.
                    // Read the whole local graph even after leaving the route.
                    val contextRoads = segments.filter { it.roadClass in 0..3 && it.points.size >= 2 }
                    val nearbyRoute = currentGeometry?.let { geometry ->
                        RouteGeometryWindow.around(geometry, lat, lon,
                            routeProgressMatcher.matchedProgressMeters()).points
                    }.orEmpty()
                    val upcoming = RoadContextUpcoming.ahead(
                        nearbyRoute, lat, lastGpsSpeedMs, lastFixAccuracyM)
                    // Multi-way OSM detection and scene allocation are independent.
                    // Neither can remove the GPS-local reserve.
                    val analysis = RoadContextJunctions.analyze(
                        contextRoads, lat, lon, upcoming, nearbyRoute)
                    val sceneSelection = RoadContextSceneSelector.select(
                        contextRoads, analysis.focus, lat, lon, upcoming, nearbyRoute)
                    sceneSelection.drawn.forEach { drawn ->
                        roadPolylines.add(MotoBLEMapRoadPolyline(
                            roadClass = drawn.road.roadClass, points = drawn.points))
                    }
                    val focus = analysis.focus
                    DiagnosticLog.event("OSM_EXIT_CONT",
                        "ways=" + sceneSelection.continuedExitWays +
                        " branches=" + sceneSelection.continuedExitBranches +
                        " exits_sent=" +
                        sceneSelection.count(RoadContextSceneSelector.Role.EXIT) +
                        " slots=" + sceneSelection.slots +
                        " points=" + sceneSelection.points)
                    DiagnosticLog.event("OSM_RING",
                        "candidates=" + analysis.candidates +
                        " qualified=" + analysis.qualified +
                        " osm_tagged=" + (analysis.focus?.osmTagged ?: false) +
                        " focus=" + (focus?.key ?: "none") +
                        " score=" + (focus?.confidence ?: 0) +
                        " circularity_pct=" + (focus?.circularity?.times(100)?.toInt() ?: -1) +
                        " perimeter_m=" + (focus?.perimeterM?.toInt() ?: -1) +
                        " gps_m=" + (focus?.gpsDistanceM?.toInt() ?: -1) +
                        " ahead_m=" + (focus?.upcomingDistanceM?.takeIf { it.isFinite() }?.toInt() ?: -1) +
                        " route_gap_m=" + (focus?.forwardRouteDistanceM?.takeIf { it.isFinite() }?.toInt() ?: -1) +
                        " route_overlap_m=" + (focus?.forwardRouteOverlapM?.toInt() ?: 0) +
                        " route_along_m=" + (focus?.forwardRouteAlongM?.takeIf { it.isFinite() }?.toInt() ?: -1) +
                        " exit_ways=" + (focus?.rawExitWays ?: 0) +
                        " branches=" + (focus?.distinctExitBranches ?: 0) +
                        " prioritized=" + (focus?.exits?.size ?: 0) +
                        " excluded=" + analysis.excluded.entries.joinToString(",") {
                            it.key + ":" + it.value
                        })
                    // DiagnosticLog truncates each entry to 240 characters.
                    // Keep search budgets and rejection causes in short,
                    // separate events rather than appending to OSM_RING.
                    DiagnosticLog.event("OSM_RING_SEARCH",
                        "roads=${contextRoads.size} " +
                        "osm_ways=${contextRoads.count { it.junctionKind in 1..2 }} " +
                        "edges=${analysis.graphSelectedEdges}/${analysis.graphEligibleEdges} " +
                        "starts=${analysis.dfsStartEdges} " +
                        "states=${analysis.dfsStates} " +
                        "candidates=${analysis.candidates} " +
                        "qualified=${analysis.qualified} " +
                        "cap_edges=${analysis.graphEligibleEdges > analysis.graphSelectedEdges} " +
                        "cap_dfs=${analysis.searchLimitHit} " +
                        "cap_candidates=${analysis.candidateLimitHit}")
                    DiagnosticLog.event("OSM_RING_REJECT",
                        "shape=${analysis.excluded["shape"] ?: 0} " +
                        "closure=${analysis.excluded["shape_closure"] ?: 0} " +
                        "length=${analysis.excluded["shape_length"] ?: 0} " +
                        "diameter=${analysis.excluded["shape_diameter"] ?: 0} " +
                        "circularity=${analysis.excluded["shape_circularity"] ?: 0} " +
                        "far=${analysis.excluded["far"] ?: 0} " +
                        "confidence=${analysis.excluded["confidence"] ?: 0} " +
                        "no_exits=${analysis.excluded["confidence_no_exits"] ?: 0} " +
                        "class0=${analysis.excluded["confidence_class0"] ?: 0}")
                    DiagnosticLog.event("OSM_SCENE_POLICY",
                        "focus=" + (sceneSelection.count(RoadContextSceneSelector.Role.RING) +
                            sceneSelection.count(RoadContextSceneSelector.Role.EXIT)) +
                        " exits_sent=" + sceneSelection.count(RoadContextSceneSelector.Role.EXIT) +
                        " gps=" + sceneSelection.count(RoadContextSceneSelector.Role.GPS) +
                        " upcoming=" + sceneSelection.count(RoadContextSceneSelector.Role.UPCOMING) +
                        " extra=" + sceneSelection.count(RoadContextSceneSelector.Role.EXTRA) +
                        " offroute_gps=" + sceneSelection.offRouteGpsRoads +
                        " focus_pts=" + ((sceneSelection.pointCounts[RoadContextSceneSelector.Role.RING] ?: 0) +
                            (sceneSelection.pointCounts[RoadContextSceneSelector.Role.EXIT] ?: 0)) +
                        " gps_pts=" + (sceneSelection.pointCounts[RoadContextSceneSelector.Role.GPS] ?: 0) +
                        " route_local=" + sceneSelection.nearRouteRoadsDrawn +
                        " secondary_rings_closed=" + sceneSelection.secondaryClosedRings +
                        " upcoming_pts=" + (sceneSelection.pointCounts[RoadContextSceneSelector.Role.UPCOMING] ?: 0) +
                        " omitted=" + sceneSelection.roadsNotDrawn +
                        " total=" + sceneSelection.slots + "/" + sceneSelection.points)

                }

                // An unavailable map tile must not erase a valid scene that
                // is already being displayed. Wait for real OSM data.
                if (roadPolylines.isEmpty()) {
                    DiagnosticLog.event("OSM_EMPTY", "segments=${segments.size}")
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

                Log.i("NavigationVM", "Context BLE TRIAL(20/160): roads=${roadPolylines.size} points=${roadPolylines.sumOf { it.points.size }} sourceSegments=${segments.size}")
                DiagnosticLog.event("BLE_MAP_SCENE",
                    "revision=$lastMapSceneRevision roads=${roadPolylines.size} points=${roadPolylines.sumOf { it.points.size }} " +
                        "candidates=${segments.size} capacity=20/160")
                ReplayTraceRecorder.scene(scene)
                bleManager.sendMapScene(scene)
            } catch (e: Exception) {
                Log.w("NavigationVM", "Error updating map scene and speed limit: ${e.message}")
            }
        }
    }

    /** Only route-preview user action may begin this download. */
    fun corridorTileCount(context: Context): Int {
        val selected = _routeOptions.value.getOrNull(_selectedRouteIndex.value)
            ?: return 0
        return try {
            val plan = OpenFreeMapRouteCorridor.plan(selected.rawRouteJson)
            if (plan.isEmpty()) -1
            else OpenFreeMapRouteCorridor.missing(context.applicationContext, plan).size
        } catch (e: Exception) {
            DiagnosticLog.event("OFM_CORRIDOR_ERROR", "plan: " + e.message)
            -1
        }
    }

    fun downloadOpenFreeMapCorridor(context: Context) {
        if (_isDownloadingMap.value || mapDownloadJob?.isActive == true) return
        val selected = _routeOptions.value.getOrNull(_selectedRouteIndex.value) ?: return
        val appContext = context.applicationContext
        mapDownloadJob = viewModelScope.launch {
            _isDownloadingMap.value = true
            _downloadProgress.value = 0f
            try {
                val tiles = withContext(Dispatchers.IO) {
                    OpenFreeMapRouteCorridor.missing(
                        appContext, OpenFreeMapRouteCorridor.plan(selected.rawRouteJson)
                    )
                }
                _downloadStatus.value = "Corridoio: ${tiles.size} tile da scaricare"
                DiagnosticLog.event("OFM_CORRIDOR", "start missing=${tiles.size}")
                withContext(Dispatchers.IO) {
                    OpenFreeMapRouteCorridor.download(appContext, tiles) { done, total, bytes ->
                        _downloadProgress.value = done.toFloat() / total.coerceAtLeast(1)
                        _downloadStatus.value = "Corridoio ${done}/${total} tile, ${bytes / 1024} KiB"
                    }
                }
                _downloadProgress.value = 1f
                _downloadStatus.value = "Corridoio salvato: ${tiles.size} nuove tile"
                DiagnosticLog.event("OFM_CORRIDOR", "complete tiles=${tiles.size}")
                refreshSavedMaps(appContext)
                if (hasFirstFix) {
                    val (lat, lon) = currentLocation.value
                    updateSpeedLimitAndMapScene(lat, lon, appContext)
                }
            } catch (e: CancellationException) {
                _downloadStatus.value = "Download corridoio interrotto"
                throw e
            } catch (e: Exception) {
                _downloadStatus.value = "Errore corridoio: ${e.message}"
                DiagnosticLog.event("OFM_CORRIDOR_ERROR", e.message.orEmpty())
            } finally {
                _isDownloadingMap.value = false
                mapDownloadJob = null
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
        // Keep progress on the current route monotonic across parallel roads
        // and roundabouts. The old closest-segment search could jump to a
        // previously traversed edge and make both distances increase.
        val travelHeading = if (lastGpsSpeedMs >= 3f &&
            SystemClock.elapsedRealtime() - lastReliableGpsBearingMs in 0L..3_000L)
            lastReliableGpsBearing else null
        var remainingMeters = routeProgressMatcher.remaining(
            lat, lon, travelHeading, lastGpsSpeedMs)
            ?: baseSnapshot.remainingDistanceM
        // Do not let a nearest-segment snap at an intersection consume
        // 20-30m instantaneously and make the next turn disappear early.
        remainingMeters = routeDistanceGuard.stabilize(remainingMeters, lat, lon)
        val snapshot = baseSnapshot.copy(
            remainingDistanceM = remainingMeters,
            routeProgressM = (turnPlan.totalShapeMeters - remainingMeters)
                .coerceAtLeast(0.0).toLong()
        )

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

    fun importPhotonIndex(context: Context, uri: Uri) {
        if (_photonImporting.value || _isNavigating.value) return
        viewModelScope.launch {
            _photonImporting.value = true
            _photonStatus.value = "Importazione Photon offline..."
            try {
                val installed = withContext(Dispatchers.IO) {
                    OfflinePhotonPlaceSearch.install(context.applicationContext, uri) { copied ->
                        _photonStatus.value = "Photon: copiati " + (copied / 1048576L) + " MiB"
                    }
                }
                _photonInstalledBytes.value = installed
                _photonStatus.value = "Indice Photon offline installato"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _photonStatus.value = "Importazione Photon fallita: " + e.message
            } finally {
                _photonImporting.value = false
            }
        }
    }

    fun deletePhotonIndex(context: Context) {
        if (_photonImporting.value || _isNavigating.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val removed = OfflinePhotonPlaceSearch.delete(context.applicationContext)
            _photonInstalledBytes.value = if (removed) 0L
                else OfflinePhotonPlaceSearch.file(context).length()
            _photonStatus.value = if (removed) "Indice Photon eliminato"
                else "Impossibile eliminare l'indice Photon"
        }
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
                com.motogps.android.diagnostics.DiagnosticLog.event("OSM_IMPORT_ERROR", "${e.javaClass.simpleName}: ${e.message}")
                _downloadStatus.value = "Errore importazione OSM: ${e.message}"
                _errorMessage.value = _downloadStatus.value
            } finally {
                _isDownloadingMap.value = false
            }
        }
    }

    fun refreshSavedMaps(context: Context) {
        _photonInstalledBytes.value = OfflinePhotonPlaceSearch.file(context)
            .let { if (it.isFile) it.length() else 0L }
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
            (-(ax * dx + ay * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
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
            val distance = distanceToSegmentMeters(
                latitude, longitude, points[i - 1], points[i])
            if (distance < distanceFromRoute) {
                distanceFromRoute = distance
            }
        }
        val now = SystemClock.elapsedRealtime()
        val bearingIsFresh = now - lastReliableGpsBearingMs in 0L..3_000L
        // Unlike the closest edge of the entire polyline, this candidate
        // stays near the matched route progress and rejects ambiguous parallel
        // or crossing edges that also match the current travel direction.
        val reverseCandidate = routeProgressMatcher.reverseCandidate(
            latitude, longitude,
            if (bearingIsFresh) lastReliableGpsBearing else null,
            lastGpsSpeedMs)
        val reverseCourse = reverseCandidate != null
        // Moving in the opposite direction on the same road is not the same
        // as following the route. Require repeated fixes before rerouting,
        // to reject brief compass/GNSS errors on sharp bends.
        val farFromRoute = distanceFromRoute > 45.0
        if ((!farFromRoute && !reverseCourse) || lastGpsSpeedMs < 1.5f) {
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
        val minimumFixes = if (farFromRoute) 2 else 3
        val minimumDurationMs = if (farFromRoute) 1_500L else 2_000L
        if (offRouteFixCount < minimumFixes ||
            now - offRouteFirstFixMs < minimumDurationMs) return

        // New routes may initially snap onto the wrong nearby OSM edge.
        // Hold reverse-only triggers briefly after accepting a reroute;
        // substantial (>=100m) departures are never blocked.
        if (rerouteStability.shouldDefer(
                now, latitude, longitude, distanceFromRoute, !farFromRoute)) {
            offRouteFixCount = 0
            offRouteFirstFixMs = 0L
            return
        }

        // The public FOSSGIS online endpoint is a fair-use demo service:
        // leave more breathing room than the local embedded Valhalla graph.
        val usesOnline = lastRoutingProvider == "valhalla-online" ||
            navigationMapsContext?.let { !OfflineValhallaRouter.hasTiles(it) } == true
        val cooldownMs = if (usesOnline) 15_000L else 8_000L
        if (now - lastRecalculationTime < cooldownMs) return
        DiagnosticLog.event("REROUTE",
            "trigger off_route_m=${distanceFromRoute.toInt()} reverse=$reverseCourse speed_mps=$lastGpsSpeedMs " +
                "match_delta_m=${reverseCandidate?.progressDeltaMeters?.toInt() ?: -999}")
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
                DiagnosticLog.event("REROUTE",
                    "success provider=${lastRoutingProvider.orEmpty()} route_m=${snapshot.totalDistanceM}")
                routeDistanceGuard.reset()
                routeProgressMatcher.reset(geometry?.points.orEmpty())
                // The IO reroute may have taken seconds. Never initialise a
                // new geometry using the old trigger fix.
                val (acceptedLat, acceptedLon) = currentLocation.value
                val refreshed = updateManeuverAndDistance(acceptedLat, acceptedLon)
                    ?: snapshot
                currentSnapshot = refreshed
                val matchM = routeProgressMatcher.matchedProgressMeters()
                val firstRoutePoint = geometry?.points?.firstOrNull()
                val originGapM = firstRoutePoint?.let {
                    calculateDistanceMeters(acceptedLat, acceptedLon,
                        it.latitudeE6 / 1e6, it.longitudeE6 / 1e6)
                }
                // Numeric diagnostics only: no GPS coordinates or route points.
                // Distinguish a fresh match near 0m from a premature loop snap.
                DiagnosticLog.event("REROUTE_INITIAL_MATCH",
                    "progress_m=${matchM?.toInt() ?: -1} " +
                        "origin_gap_m=${originGapM?.toInt() ?: -1} " +
                        "route_m=${snapshot.totalDistanceM} " +
                        "points=${geometry?.points?.size ?: 0}")
                rerouteStability.accepted(
                    SystemClock.elapsedRealtime(), acceptedLat, acceptedLon)
                _routingSource.value = lastRoutingProvider
                if (geometry != null) sendNearbyRouteGeometry(geometry)
                bleManager.sendNavigationSnapshot(refreshed)
            } catch (e: Exception) {
                DiagnosticLog.event("REROUTE_ERROR", e.message.orEmpty())
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
        waypoints: List<PlaceSearchResult> = _routeWaypoints.value,
        strictBearing: Boolean = false
    ): JSONObject {
        var localError = "nessuna regione Valhalla installata"
        val context = navigationMapsContext
        if (context != null && OfflineValhallaRouter.hasTiles(context)) {
            try {
                val result = OfflineValhallaRouter.route(
                    context, originLat, originLon, place.latitude, place.longitude,
                    isReroute, bearing, _routePreferences.value,
                    waypoints.map { it.latitude to it.longitude }, strictBearing)
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
        // The second attempt is deliberately offline-only. Never turn an
        // anomalous local reroute into a long public-server request.
        if (strictBearing) throw IllegalStateException(
            "Nessuna rotta offline disponibile con partenza orientata")
        return try {
            val result = OnlineValhallaRouter.route(
                originLat, originLon, place.latitude, place.longitude, isReroute, bearing,
                _routePreferences.value, waypoints.map { it.latitude to it.longitude },
                strictBearing)
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
        // A moving route departure needs the same road-direction hint as a
        // reroute. Do not substitute the phone's magnetic compass at rest.
        val bearing = if (lastGpsSpeedMs >= 3f &&
            SystemClock.elapsedRealtime() - lastReliableGpsBearingMs in 0L..5_000L)
            lastReliableGpsBearing else null
        val pending = if (isReroute) activeWaypoints.drop(nextWaypointIndex)
            else _routeWaypoints.value
        val initial = resolveRoute(originLat, originLon, place, isReroute, bearing, pending)
        // Only inspect an *actual* moving reroute. Route geometry can legitimately
        // lead backwards when a mandatory stop is behind the rider, so this is
        // a bounded candidate retry, never a forced waypoint skip or fabricated
        // green segment.
        val initialDirection = if (isReroute && bearing != null)
            RerouteDepartureDirection.inspect(initial, originLat, originLon, bearing)
        else null
        val result = if (initialDirection?.isOpposite == true) {
            DiagnosticLog.event("REROUTE_START_DIRECTION",
                "phase=original course_deg=${bearing?.toInt()} " +
                    "route_deg=${initialDirection.departureBearingDegrees.toInt()} " +
                    "error_deg=${initialDirection.differenceDegrees.toInt()} " +
                    "snap_m=${initialDirection.snapDistanceMeters.toInt()} " +
                    "style=${_routePreferences.value.style} " +
                    "route_m=${initial.optJSONObject("route")?.optLong("total_distance_m") ?: -1} " +
                    "stops_pending=${pending.size}")
            val initialProvider = initial.optJSONObject("route")
                ?.optString("provider", "").orEmpty()
            // A second request to the public demo server would add potentially
            // tens of seconds to a reroute; retry only on the offline graph.
            val retry = if (initialProvider == "valhalla-local") {
                try {
                    resolveRoute(originLat, originLon, place, isReroute,
                        bearing, pending, strictBearing = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DiagnosticLog.event("REROUTE_START_DIRECTION",
                        "phase=retry_error type=${e.javaClass.simpleName}")
                    null
                }
            } else null
            val retryDirection = retry?.let {
                RerouteDepartureDirection.inspect(it, originLat, originLon, bearing)
            }
            val sameProvider = retry?.optJSONObject("route")
                ?.optString("provider", "") == initialProvider
            val useRetry = sameProvider && retryDirection != null &&
                RerouteDepartureDirection.preferRetry(initialDirection, retryDirection)
            DiagnosticLog.event("REROUTE_START_DIRECTION",
                "phase=decision choice=${if (useRetry) "retry" else "original"} " +
                    "retry_deg=${retryDirection?.departureBearingDegrees?.toInt() ?: -1} " +
                    "retry_error_deg=${retryDirection?.differenceDegrees?.toInt() ?: -1} " +
                    "retry_snap_m=${retryDirection?.snapDistanceMeters?.toInt() ?: -1} " +
                    "provider=$initialProvider stops_pending=${pending.size}")
            if (useRetry) requireNotNull(retry) else initial
        } else {
            initial
        }
        lastRoutingProvider = result.optJSONObject("route")
            ?.optString("provider", "Valhalla") ?: "Valhalla"
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
        val isConnected = NavigationReconnectPolicy.isReady(state)
        if (isConnected) {
            // A fresh BLE handshake resets ESP32-side session state. Replaying only
            // the periodic maneuver snapshots can leave the display without its
            // route window after a reconnect (especially with intermediate stops).
            val replay = NavigationReconnectPolicy.actions(
                _isNavigating.value, _hasArrived.value,
                currentSnapshot != null, currentGeometry != null)
            if (replay.snapshot) {
                DiagnosticLog.event("BLE_NAV_RESYNC",
                    "session=${bleManager.getCurrentSessionId()} geometry=${replay.geometry} " +
                        "stops_pending=${activeWaypoints.size - nextWaypointIndex}")
                try {
                    if (replay.geometry) currentGeometry?.let { sendNearbyRouteGeometry(it) }
                    currentSnapshot?.let { bleManager.sendNavigationSnapshot(it) }
                } catch (e: Exception) {
                    DiagnosticLog.event("BLE_NAV_RESYNC_ERROR",
                        "exception=${e.javaClass.simpleName}")
                    Log.w("NavigationVM", "Unable to restore BLE navigation session", e)
                }
            }
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
                if (!_isNavigating.value &&
                    NavigationReconnectPolicy.isReady(_connectionState.value)) {
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

    override fun onCleared() {
        // A configuration change retains this ViewModel and its BLE session.
        // Close GATT/codec only when the navigation owner truly goes away.
        bleManager.close()
        super.onCleared()
    }
}

class NavigationViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NavigationViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return NavigationViewModel(MotoBLEManager(context.applicationContext), context.applicationContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
