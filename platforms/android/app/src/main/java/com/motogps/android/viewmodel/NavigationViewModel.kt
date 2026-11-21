package com.motogps.android.viewmodel

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
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
import com.motogps.android.map.OpenFreeMapRoundaboutGeometry
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
import com.motogps.android.routing.CircularTourPlanner
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
    val longitude: Double,
    val distanceMeters: Double? = null,
    val requiresRoadAccess: Boolean = false
) {
    val distanceLabel: String
        get() = distanceMeters?.takeIf { it.isFinite() && it >= 0 }?.let {
            if (it < 1000.0) "${it.toInt()} m"
            else String.format(Locale.ITALY, "%.1f km", it / 1000.0)
        } ?: ""
}

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

    private val _savedCircularTours = MutableStateFlow<List<SavedCircularTour>>(emptyList())
    val savedCircularTours: StateFlow<List<SavedCircularTour>> = _savedCircularTours
    private var circularRequestedKm = 100
    private var circularOrientationDegrees = 0
    private var previewOriginOverride: Pair<Double, Double>? = null

    fun loadSavedCircularTours(context: Context) {
        _savedCircularTours.value = SavedCircularTourStore.read(context.applicationContext)
    }

    fun saveCurrentCircularTour(context: Context, title: String) {
        val preview = _routePreview.value
        if (_isNavigating.value || preview?.displayArea != "Tour circolare") {
            _errorMessage.value = "Apri un tour circolare prima di salvarlo"
            return
        }
        if (title.isBlank()) {
            _errorMessage.value = "Inserisci un nome per il tour"
            return
        }
        try {
            val via = _routeWaypoints.value.toList()
            // The preview editor can remove or reorder stops. Save the actual
            // route plan; only surviving user points remain compulsory.
            val mandatory = via.filter { waypoint ->
                _circularMandatoryStops.value.any { chosen ->
                    calculateDistanceMeters(waypoint.latitude, waypoint.longitude,
                        chosen.latitude, chosen.longitude) < 60.0
                }
            }
            val tour = SavedCircularTour(
                java.util.UUID.randomUUID().toString(), title.trim().take(80),
                previewOriginOverride ?: (preview.latitude to preview.longitude),
                circularRequestedKm, circularOrientationDegrees,
                mandatory, via, _routePreferences.value
            )
            _savedCircularTours.value =
                SavedCircularTourStore.save(context.applicationContext, tour)
            DiagnosticLog.event("CIRCULAR_TOUR_SAVED",
                "stops=${tour.via.size} mandatory=${tour.mandatory.size}")
        } catch (e: Exception) {
            _errorMessage.value = "Impossibile salvare il tour: ${e.message}"
        }
    }

    fun removeSavedCircularTour(context: Context, id: String) {
        try {
            _savedCircularTours.value =
                SavedCircularTourStore.remove(context.applicationContext, id)
        } catch (e: Exception) {
            _errorMessage.value = "Impossibile eliminare il tour: ${e.message}"
        }
    }

    fun openSavedCircularTour(tour: SavedCircularTour, fromCurrentPosition: Boolean) {
        if (_isNavigating.value) return
        if (fromCurrentPosition && !hasFirstFix) {
            _errorMessage.value = "Attendi un fix GPS per partire dalla posizione attuale"
            return
        }
        circularRequestedKm = tour.desiredKm
        circularOrientationDegrees = tour.orientationDegrees
        _circularMandatoryStops.value = tour.mandatory.toList()
        resumeOriginSession = null
        _routeWaypoints.value = tour.via.toList()
        _routePreferences.value = tour.preferences
        previewOriginOverride = if (fromCurrentPosition) null else tour.departure
        val destination = if (fromCurrentPosition) currentLocation.value else tour.departure
        DiagnosticLog.event("CIRCULAR_TOUR_LOADED",
            "mandatory=${tour.mandatory.size} stops=${tour.via.size} " +
                "origin=${if (fromCurrentPosition) "current" else "saved"}")
        previewRoute(PlaceSearchResult("Ritorno al punto di partenza", "",
            "Tour circolare", destination.first, destination.second))
    }

    private val _pausedTour = MutableStateFlow<TourSession?>(null)
    val pausedTour: StateFlow<TourSession?> = _pausedTour
    private var lastTourCheckpointMillis = 0L
    private var resumeOriginSession: TourSession? = null

    fun loadTourSession(context: Context) {
        // An active session from a previous process is resumable, but NEVER
        // auto-resume its BLE navigation or overwrite its completed stops.
        _pausedTour.value = TourSessionStore.read(context.applicationContext)
    }

    private fun saveTourCheckpoint(paused: Boolean) {
        val context = appContext ?: navigationMapsContext
            ?: throw IllegalStateException("Contesto Android non disponibile")
        val destination = activeDestination ?: return
        if (destination.displayArea != "Tour circolare") return
        val (lat, lon) = _currentLocation.value
        val checkpoint = TourSession(destination, activeWaypoints.toList(),
            nextWaypointIndex.coerceIn(0, activeWaypoints.size),
            _routePreferences.value, lat, lon,
            System.currentTimeMillis(), paused)
        TourSessionStore.write(context.applicationContext, checkpoint)
        _pausedTour.value = checkpoint
        lastTourCheckpointMillis = SystemClock.elapsedRealtime()
    }

    private fun discardTourCheckpoint() {
        val context = appContext ?: navigationMapsContext
        if (context != null) {
            TourSessionStore.clear(context.applicationContext)
        }
        _pausedTour.value = null
    }

    fun pauseTour() {
        if (!_isNavigating.value || activeDestination?.displayArea != "Tour circolare") return
        try {
            saveTourCheckpoint(paused = true)
        } catch (e: Exception) {
            _errorMessage.value = "Pausa non salvata: ${e.message}"
            return // Never terminate navigation if persistence failed.
        }
        DiagnosticLog.event("TOUR_PAUSED",
            "completed=$nextWaypointIndex total=${activeWaypoints.size}")
        stopNavigationInternal(preserveTourCheckpoint = true)
    }

    fun previewResumeTour() {
        if (_isNavigating.value || _isLoading.value) return
        val checkpoint = _pausedTour.value ?: return
        if (!hasFirstFix) {
            _errorMessage.value = "Attendi un fix GPS valido per riprendere il tour"
            return
        }
        _routePreferences.value = checkpoint.preferences
        resumeOriginSession = checkpoint
        _routeWaypoints.value = checkpoint.pendingStops
        previewOriginOverride = null
        DiagnosticLog.event("TOUR_RESUME_PREVIEW",
            "completed=${checkpoint.completedStops} pending=${checkpoint.pendingStops.size}")
        previewRoute(checkpoint.destination)
        // Do not start navigation without user confirmation.
    }

    fun discardPausedTour() {
        if (_isNavigating.value) return
        try {
            discardTourCheckpoint()
        } catch (e: Exception) {
            _errorMessage.value = "Impossibile eliminare il tour sospeso: ${e.message}"
        }
    }

    private val _routeWaypoints = MutableStateFlow<List<PlaceSearchResult>>(emptyList())
    val routeWaypoints: StateFlow<List<PlaceSearchResult>> = _routeWaypoints
    private val _circularMandatoryStops = MutableStateFlow<List<PlaceSearchResult>>(emptyList())
    val circularMandatoryStops: StateFlow<List<PlaceSearchResult>> = _circularMandatoryStops

    fun addCircularMandatoryStop(place: PlaceSearchResult) {
        if (_isNavigating.value || _circularMandatoryStops.value.size >= ValhallaRouteLocations.MAX_STOPS) return
        if (!place.latitude.isFinite() || !place.longitude.isFinite() ||
            place.latitude !in -90.0..90.0 || place.longitude !in -180.0..180.0) {
            _errorMessage.value = "Coordinate tappa non valide"
            return
        }
        val duplicate = _circularMandatoryStops.value.any {
            calculateDistanceMeters(it.latitude, it.longitude,
                place.latitude, place.longitude) < 60.0
        }
        if (duplicate) {
            _errorMessage.value = "Questo luogo è già fra le tappe obbligatorie"
            return
        }
        _circularMandatoryStops.value = _circularMandatoryStops.value + place
        _waypointSearchResults.value = emptyList()
        DiagnosticLog.event("CIRCULAR_POI", "action=add count=${_circularMandatoryStops.value.size}")
    }

    fun removeCircularMandatoryStop(index: Int) {
        if (_isNavigating.value || index !in _circularMandatoryStops.value.indices) return
        _circularMandatoryStops.value =
            _circularMandatoryStops.value.filterIndexed { i, _ -> i != index }
        DiagnosticLog.event("CIRCULAR_POI", "action=remove count=${_circularMandatoryStops.value.size}")
    }

    fun moveCircularMandatoryStop(index: Int, direction: Int) {
        if (_isNavigating.value || direction !in listOf(-1, 1)) return
        val updated = _circularMandatoryStops.value.toMutableList()
        if (index !in updated.indices || index + direction !in updated.indices) return
        java.util.Collections.swap(updated, index, index + direction)
        _circularMandatoryStops.value = updated
    }

    private val _waypointSearchResults = MutableStateFlow<List<PlaceSearchResult>>(emptyList())
    val waypointSearchResults: StateFlow<List<PlaceSearchResult>> = _waypointSearchResults
    private val _waypointSearching = MutableStateFlow(false)
    val waypointSearching: StateFlow<Boolean> = _waypointSearching
    private val _waypointSearchError = MutableStateFlow<String?>(null)
    val waypointSearchError: StateFlow<String?> = _waypointSearchError
    private var waypointSearchJob: Job? = null
    private var activeWaypoints: List<PlaceSearchResult> = emptyList()
    private var nextWaypointIndex = 0
    private val _nextWaypointToSkip = MutableStateFlow<PlaceSearchResult?>(null)
    val nextWaypointToSkip: StateFlow<PlaceSearchResult?> = _nextWaypointToSkip

    private fun publishPendingWaypoint() {
        _nextWaypointToSkip.value = activeWaypoints.getOrNull(nextWaypointIndex)
    }
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
    private val _roadDnaSummary = MutableStateFlow<RoadDnaSummary?>(null)
    val roadDnaSummary: StateFlow<RoadDnaSummary?> = _roadDnaSummary
    private val _roadDnaLoading = MutableStateFlow(false)
    val roadDnaLoading: StateFlow<Boolean> = _roadDnaLoading
    private val _roadDnaError = MutableStateFlow<String?>(null)
    val roadDnaError: StateFlow<String?> = _roadDnaError
    private var roadDnaJob: Job? = null

    /** Road DNA is optional and never alters routing, BLE, or ESP32 scene. */
    fun analyzeSelectedRoadDna() {
        val option = _routeOptions.value.getOrNull(_selectedRouteIndex.value) ?: return
        val context = appContext ?: return
        roadDnaJob?.cancel()
        _roadDnaSummary.value = null
        _roadDnaError.value = null
        roadDnaJob = viewModelScope.launch {
            _roadDnaLoading.value = true
            try {
                val report = withContext(Dispatchers.IO) {
                    RoadDnaAnalysis.analyze(context, option)
                }
                // A newly selected or recalculated route invalidates this report.
                if (_routeOptions.value.getOrNull(_selectedRouteIndex.value) === option)
                    _roadDnaSummary.value = report
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _roadDnaError.value = "Road DNA: " +
                    (e.message ?: "impossibile analizzare l'itinerario")
            } finally {
                _roadDnaLoading.value = false
            }
        }
    }

    private fun invalidateRoadDna() {
        roadDnaJob?.cancel()
        _roadDnaSummary.value = null
        _roadDnaError.value = null
        _roadDnaLoading.value = false
    }


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
    private val _roadDnaInstalledBytes = MutableStateFlow(0L)
    val roadDnaInstalledBytes: StateFlow<Long> = _roadDnaInstalledBytes
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
    // A bit in the existing 16-bit navigation flags tells the device whether
    // any intermediate stop remains. It is independent of the visible BLE
    // geometry window, so the circular >> button is available even kilometres
    // before a stop. Final destination is never skippable.
    private fun sendNavigationSnapshotWithStops(snapshot: MotoBLENavigationSnapshot) {
        val hasPendingStops = _isNavigating.value && !_hasArrived.value &&
            activeWaypoints.size > nextWaypointIndex
        bleManager.sendNavigationSnapshot(
            snapshot.copy(hasPendingWaypoints = hasPendingStops))
    }

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
        // Emit the full private route only once per recorded navigation;
        // the BLE payload remains the same 24-point moving window.
        ReplayTraceRecorder.fullRoute(route, turnPlan)
        ReplayTraceRecorder.route(window)
        // The arrival is marker index 0 (blue); intermediate stops are 1..8
        // (yellow). Both use the same snapped green route window; only the
        // former can appear even on an itinerary without intermediate stops.
        val markers = WaypointMarkerWindow.visible(
            window, activeWaypoints.drop(nextWaypointIndex), activeDestination)
        bleManager.sendRouteGeometry(window.copy(waypointMarkers = markers))
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
    private val routeProgressStallPolicy = RouteProgressStallPolicy()
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

    /** Attach straight-line distance to search items for both result lists.
     * Do not show distance when the GPS fix is not available. */
    private fun withSearchDistances(places: List<PlaceSearchResult>,
                                    gps: Pair<Double, Double>?): List<PlaceSearchResult> {
        val fix = gps?.takeIf {
            it.first.isFinite() && it.second.isFinite() &&
                it.first in -90.0..90.0 && it.second in -180.0..180.0
        } ?: return places
        val lat1 = Math.toRadians(fix.first)
        val lon1 = Math.toRadians(fix.second)
        return places.map { place ->
            val dLat = Math.toRadians(place.latitude) - lat1
            val dLon = Math.toRadians(place.longitude) - lon1
            val a = kotlin.math.sin(dLat / 2).let { it * it } +
                kotlin.math.cos(lat1) * kotlin.math.cos(Math.toRadians(place.latitude)) *
                kotlin.math.sin(dLon / 2).let { it * it }
            val distance = 6371000.0 * 2.0 *
                kotlin.math.atan2(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)),
                    kotlin.math.sqrt((1.0 - a).coerceAtLeast(0.0)))
            place.copy(distanceMeters = distance)
        }
    }

    /** Category words are NOT place-name queries: offline and online both use
     * OSM classification + GPS proximity. Never fall back to text "trattoria".
     * Unqualified addresses and actual business names keep normal Photon FTS.
     */
    private fun searchPhotonPlaces(query: String,
                                   signal: CancellationSignal? = null): List<PlaceSearchResult> {
        val gps = if (hasFirstFix) currentLocation.value else null
        val context = appContext
        val categories = OfflinePoiCategorySearch.categories(query)
        if (categories.isNotEmpty()) {
            if (gps == null) {
                Log.i("MotoGPS_Photon", "Categoria POI: attendo fix GPS")
                return emptyList()
            }
            if (context != null && OfflinePoiCategorySearch.available(context)) {
                try {
                    val local = OfflinePoiCategorySearch.search(context, query, gps)
                    if (local.isNotEmpty()) return withSearchDistances(local, gps)
                } catch (e: Exception) {
                    Log.w("MotoGPS_Photon", "OSM POI offline index unavailable", e)
                }
            }
            return try {
                withSearchDistances(PhotonPlaceSearch.searchCategories(categories, gps), gps)
            } catch (e: Exception) {
                // A failed category filter must NOT quietly turn into a textual
                // search and return distant businesses whose name matches.
                Log.w("MotoGPS_Photon", "Online OSM POI category search failed", e)
                emptyList()
            }
        }
        val touristHits = if (context != null) try {
            OfflineTouristPlaceSearch.searchNames(context, query, gps)
        } catch (e: Exception) {
            Log.w("MotoGPS_Photon", "Tourist name search unavailable", e)
            emptyList()
        } else emptyList()
        if (context != null && OfflinePhotonPlaceSearch.installed(context)) {
            try {
                val local = OfflinePhotonPlaceSearch.search(context, query, gps, signal)
                if (local.isNotEmpty() || touristHits.isNotEmpty()) {
                    // Exact city/place names always lead; a nearby partial street
                    // must not hide an exact named lake or destination.
                    val q = PhotonOfflineRanking.normalize(query)
                    val exactLocal = local.filter {
                        PhotonOfflineRanking.normalize(it.name) == q &&
                            !it.name.startsWith("Via ", ignoreCase = true) &&
                            !it.name.startsWith("Viale ", ignoreCase = true)
                    }
                    val merged = exactLocal + touristHits +
                        local.filterNot { it in exactLocal }
                    return withSearchDistances(merged.distinctBy {
                        PhotonOfflineRanking.normalize(it.name) + "|" +
                            it.latitude.toString() + "|" + it.longitude.toString()
                    }.take(8), gps)
                }
            } catch (e: Exception) {
                Log.w("MotoGPS_Photon", "Offline Photon v2 search failed; using online", e)
            }
        }
        if (touristHits.isNotEmpty()) return withSearchDistances(touristHits, gps)
        return try {
            withSearchDistances(PhotonPlaceSearch.search(query, gps), gps)
        } catch (e: Exception) {
            Log.w("MotoGPS_Photon", "Online Photon fallback unavailable", e)
            emptyList()
        }
    }

    private var destinationSearchJob: Job? = null
    private var activeSearchSignal: CancellationSignal? = null

    fun searchDestination(query: String) {
        destinationSearchJob?.cancel()
        activeSearchSignal?.cancel()
        activeSearchSignal = null
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

        val signal = CancellationSignal()
        activeSearchSignal = signal
        destinationSearchJob = viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _routePreview.value = null

            try {
                val results = withContext(Dispatchers.IO) {
                    searchPhotonPlaces(query, signal)
                }
                if (activeSearchSignal !== signal) return@launch
                _searchResults.value = results
                if (results.isEmpty()) {
                    _errorMessage.value = "Nessun risultato trovato per '$query'"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (activeSearchSignal === signal) {
                    _errorMessage.value = "Errore durante la ricerca: " +
                        (e.message ?: "query non disponibile")
                }
            } finally {
                if (activeSearchSignal === signal) {
                    _isLoading.value = false
                    activeSearchSignal = null
                }
            }
        }
    }

    /** Plan a circular tour while preserving all user-selected obligatory POIs.
     * Valhalla still calculates the real road route and can reject impossible legs.
     */
    fun previewCircularTour(distanceKm: Int, orientationDegrees: Int = 0) {
        if (_isNavigating.value || _isLoading.value) return
        if (!hasFirstFix) {
            _errorMessage.value = "Attendi un fix GPS valido per creare il tour circolare"
            return
        }
        try {
            previewOriginOverride = null
            circularRequestedKm = distanceKm
            circularOrientationDegrees = orientationDegrees
            val (lat, lon) = currentLocation.value
            val mandatory = _circularMandatoryStops.value.toList()
            val coordinates = mandatory.map { it.latitude to it.longitude }
            val lowerBoundKm = CircularTourPlanner.straightLineMinimumKm(lat, lon, coordinates)
            // If even straight-line travel exceeds the requested budget, don't
            // silently generate a much longer loop. The user can raise the target.
            if (lowerBoundKm > distanceKm) {
                _errorMessage.value = "Le tappe obbligatorie richiedono almeno " +
                    "${kotlin.math.ceil(lowerBoundKm).toInt()} km in linea d'aria " +
                    "(su strada saranno di più). Aumenta la distanza del tour."
                return
            }
            val stops = CircularTourPlanner.planStops(
                lat, lon, distanceKm, orientationDegrees, coordinates)
            _routeWaypoints.value = stops.mapIndexed { index, point ->
                mandatory.getOrNull(index) ?: PlaceSearchResult(
                    "Tappa automatica ${index + 1}", "", "Tour automatico",
                    point.first, point.second)
            }
            val start = PlaceSearchResult("Ritorno al punto di partenza", "",
                "Tour circolare", lat, lon)
            if (_routePreferences.value.style == RouteStyle.FASTEST) {
                _routePreferences.value = _routePreferences.value.copy(
                    style = RouteStyle.PANORAMIC)
            }
            DiagnosticLog.event("CIRCULAR_TOUR",
                "requested_km=$distanceKm orientation=$orientationDegrees " +
                    "mandatory=${mandatory.size} automatic=${stops.size - mandatory.size}")
            previewRoute(start)
        } catch (e: IllegalArgumentException) {
            _errorMessage.value = "Tour circolare non valido: ${e.message}"
        }
    }

    fun previewRoute(place: PlaceSearchResult) {
        invalidateRoadDna()
        if (place.requiresRoadAccess) {
            _errorMessage.value = "Road DNA: posizione indicativa. L'accesso stradale " +
                "non è verificato: cerca una strada o località vicina per navigare."
            return
        }
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
            invalidateRoadDna()
            _selectedRouteIndex.value = index
        }
    }

    fun clearRoutePreview() {
        invalidateRoadDna()
        resumeOriginSession = null
        _hasArrived.value = false
        arrivalDetector.reset()
        routePreviewJob?.cancel()
        waypointSearchJob?.cancel()
        previewOriginOverride = null
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
        routeProgressStallPolicy.reset()
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
                val resumed = resumeOriginSession
                activeWaypoints = if (resumed != null &&
                    resumed.destination.latitude == place.latitude &&
                    resumed.destination.longitude == place.longitude) {
                    resumed.stops.take(resumed.completedStops) + _routeWaypoints.value
                } else _routeWaypoints.value.toList()
                nextWaypointIndex = if (resumed != null &&
                    resumed.destination.latitude == place.latitude &&
                    resumed.destination.longitude == place.longitude)
                    resumed.completedStops else 0
                resumeOriginSession = null
                publishPendingWaypoint()
                if (place.displayArea == "Tour circolare") {
                    // Persist before the first BLE frame, so a sudden app
                    // termination still leaves a complete resumable itinerary.
                    saveTourCheckpoint(paused = false)
                } else if (_pausedTour.value != null) {
                    // Ordinary navigation must not silently erase a paused tour.
                    DiagnosticLog.event("TOUR_CHECKPOINT", "preserved_during_other_route")
                }
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
                routeProgressStallPolicy.onRouteChanged()
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
                sendNavigationSnapshotWithStops(requireNotNull(currentSnapshot))

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
                    if (activeDestination?.displayArea == "Tour circolare" &&
                        SystemClock.elapsedRealtime() - lastTourCheckpointMillis >= 30_000L) {
                        try { saveTourCheckpoint(paused = false) } catch (e: Exception) {
                            DiagnosticLog.event("TOUR_CHECKPOINT_ERROR", e.javaClass.simpleName)
                        }
                    }
                    if (loopCount == 1 || loopCount % 4 == 0) {
                        DiagnosticLog.event("NAV_SNAPSHOT",
                            "maneuver=${activeSnap.maneuverName} id=${activeSnap.maneuverID} " +
                            "turn_m=${activeSnap.distanceToManeuverM} remain_m=${activeSnap.remainingDistanceM} " +
                            "speed_dkph=${activeSnap.speedDeciKPH} heading_cdeg=${activeSnap.headingCentiDegrees} " +
                            "fix_age_ms=$ageMs received_ago_ms=${SystemClock.elapsedRealtime() - lastAcceptedFixArrivalMs} " +
                            "gnss_stale=$gnssStale position=accepted_gps")
                    }
                    ReplayTraceRecorder.snapshot(activeSnap, lat, lon)
                    sendNavigationSnapshotWithStops(activeSnap)

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
                            val nearTurn = activeSnap.maneuverName == "roundabout" ||
                                (activeSnap.maneuverName !in
                                    setOf("continue", "none", "arrive") &&
                                    activeSnap.distanceToManeuverM <= 100L)
                            val winding = !nearTurn && elapsed >= 1_400L &&
                                RouteGeometryWindow.isWinding(
                                    RouteGeometryWindow.around(activeGeom, lat, lon)
                                )
                            if (nearTurn || winding || elapsed >= 1_500L) {
                                sendNearbyRouteGeometry(activeGeom)
                                lastGeometryTransmissionMs = now
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the previous checkpoint when route calculation fails;
                // an interrupted multi-day tour must remain recoverable.
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

    /**
     * Skip ONLY the next intermediate stop. The return destination is never
     * skippable. Do not mutate the itinerary or checkpoint until Valhalla
     * produces a valid replacement geometry.
     */
    fun skipNextWaypoint() {
        if (!_isNavigating.value || _hasArrived.value ||
            _isLoading.value || rerouteInProgress) return
        val destination = activeDestination ?: return
        val index = nextWaypointIndex
        val skip = WaypointSkipPlan.create(activeWaypoints, index) ?: return
        val skipped = skip.skipped
        val remaining = skip.pending
        rerouteInProgress = true
        _isLoading.value = true
        DiagnosticLog.event("WAYPOINT_SKIP", "phase=request remaining=${remaining.size}")
        viewModelScope.launch {
            try {
                val (snapshot, geometry, guidance) = withContext(Dispatchers.IO) {
                    performRoutingWithGeometry(destination, isReroute = true,
                        pendingOverride = remaining)
                }
                // If the rider reached the stop while Valhalla was working,
                // or the tour was stopped, the result is stale: discard it.
                if (!_isNavigating.value || _hasArrived.value ||
                    activeDestination != destination ||
                    nextWaypointIndex != index ||
                    activeWaypoints.getOrNull(index) != skipped) {
                    DiagnosticLog.event("WAYPOINT_SKIP", "phase=stale")
                    return@launch
                }
                val revised = skip.revised
                // Commit the new checkpoint before changing the active plan,
                // so a storage failure never loses the abandoned waypoint.
                if (destination.displayArea == "Tour circolare") {
                    val context = appContext ?: navigationMapsContext
                        ?: throw IllegalStateException("Contesto Android non disponibile")
                    val (lat, lon) = currentLocation.value
                    val checkpoint = TourSession(destination, revised, index,
                        _routePreferences.value, lat, lon,
                        System.currentTimeMillis(), paused = false)
                    TourSessionStore.write(context.applicationContext, checkpoint)
                    _pausedTour.value = checkpoint
                    lastTourCheckpointMillis = SystemClock.elapsedRealtime()
                }
                activeWaypoints = revised
                publishPendingWaypoint()
                currentSnapshot = snapshot
                currentGeometry = geometry
                turnPlan = guidance
                routeDistanceGuard.reset()
                routeProgressMatcher.reset(geometry?.points.orEmpty())
                routeProgressStallPolicy.onRouteChanged()
                offRouteFixCount = 0
                offRouteFirstFixMs = 0L
                val (lat, lon) = currentLocation.value
                currentSnapshot = updateManeuverAndDistance(lat, lon) ?: snapshot
                rerouteStability.accepted(SystemClock.elapsedRealtime(), lat, lon)
                _routingSource.value = lastRoutingProvider
                if (geometry != null) sendNearbyRouteGeometry(geometry)
                sendNavigationSnapshotWithStops(requireNotNull(currentSnapshot))
                DiagnosticLog.event("WAYPOINT_SKIP",
                    "phase=success remaining=${activeWaypoints.size - nextWaypointIndex}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = "Tappa non saltata: ${e.message}"
                DiagnosticLog.event("WAYPOINT_SKIP",
                    "phase=failed type=${e.javaClass.simpleName}")
            } finally {
                rerouteInProgress = false
                _isLoading.value = false
            }
        }
    }

    fun stopNavigation() = stopNavigationInternal(preserveTourCheckpoint = false)

    private fun stopNavigationInternal(preserveTourCheckpoint: Boolean) {
        if (!preserveTourCheckpoint && activeDestination?.displayArea == "Tour circolare") {
            try { discardTourCheckpoint() } catch (e: Exception) {
                _errorMessage.value = "Impossibile cancellare lo stato del tour: ${e.message}"
            }
        }
        _hasArrived.value = false
        arrivalDetector.reset()
        _isNavigating.value = false
        navigationJob?.cancel()
        navigationJob = null
        activeDestination = null
        activeWaypoints = emptyList()
        nextWaypointIndex = 0
        publishPendingWaypoint()
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
        routeProgressStallPolicy.reset()
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
        sendNavigationSnapshotWithStops(stopSnapshot)

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
            bearingAccuracyDeg = if (android.os.Build.VERSION.SDK_INT >= 26 &&
                location.hasBearingAccuracy()) location.bearingAccuracyDegrees else null,
            context = context
        )
    }

    private fun updateAcceptedDeviceLocation(
        latitude: Double,
        longitude: Double,
        speedMs: Float,
        gpsBearingDeg: Float?,
        bearingAccuracyDeg: Float?,
        context: Context?
    ) {
        if (context != null) navigationMapsContext = context.applicationContext
        hasFirstFix = true
        _currentLocation.value = Pair(latitude, longitude)
        val now = System.currentTimeMillis()
        lastLocationTimeMs = now

        lastGpsSpeedMs = speedMs
        val speedZero = speedMs < 0.8f
        if (speedZero) {
            currentSpeedDeciKph = 0
        } else {
            currentSpeedDeciKph = (speedMs * 36f).toInt().coerceAtLeast(0)
        }

        val displacementM = if (prevLat != null && prevLon != null)
            calculateDistanceMeters(prevLat!!, prevLon!!, latitude, longitude)
        else 0.0
        val displacementCourse = if (displacementM >= 9.0)
            calculateBearingDeg(prevLat!!, prevLon!!, latitude, longitude)
        else null
        val travelCourse = NavigationHeadingPolicy.movingHeading(
            speedMs, gpsBearingDeg, displacementM, displacementCourse,
            bearingAccuracyDeg, lastFixAccuracyM)
        if (travelCourse != null) {
            currentHeadingCentiDeg = (travelCourse * 100f).toInt().coerceIn(0, 35999)
            // When the GNSS provider has no bearing, two real fixes still
            // establish a reliable travel direction for the next reroute.
            if (speedMs >= 3f) {
                lastReliableGpsBearing = travelCourse
                lastReliableGpsBearingMs = SystemClock.elapsedRealtime()
            }
        }
        rerouteStability.observeCourse(
            SystemClock.elapsedRealtime(), travelCourse, speedMs)
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
                publishPendingWaypoint()
                waypointNoticeIndex = nextWaypointIndex
                waypointNoticeTotal = activeWaypoints.size
                waypointNoticeStartedMs = SystemClock.elapsedRealtime()
                Log.i("NavigationVM", "Tappa completata: $nextWaypointIndex/${activeWaypoints.size}")
                DiagnosticLog.event("WAYPOINT_REACHED",
                    "index=$nextWaypointIndex total=${activeWaypoints.size}")
                ReplayTraceRecorder.waypointReached(nextWaypointIndex, activeWaypoints.size)
                try { saveTourCheckpoint(paused = false) } catch (e: Exception) {
                    DiagnosticLog.event("TOUR_CHECKPOINT_ERROR", e.javaClass.simpleName)
                }
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
                    sendNavigationSnapshotWithStops(arrived)
                }
                if (destination.displayArea == "Tour circolare") {
                    try { discardTourCheckpoint() } catch (e: Exception) {
                        DiagnosticLog.event("TOUR_CHECKPOINT_ERROR",
                            "arrival " + e.javaClass.simpleName)
                    }
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
                    // MotoMap's genuine OSM topology always wins. The
                    // OpenFreeMap fallback only uses matching geometry endpoints,
                    // never invented OSM identifiers or arbitrary crossings.
                    val ofm = if (analysis.focus == null &&
                        contextRoads.any { it.osmWayId == null && it.nodeIds.isEmpty() }) {
                        OpenFreeMapRoundaboutGeometry.analyze(
                            contextRoads, lat, lon, upcoming)
                    } else null
                    val focus = analysis.focus ?: ofm?.focus
                    if (ofm != null) {
                        DiagnosticLog.event("OFM_RING",
                            "edges=${ofm.edges} loops=${ofm.circuits} rejected=${ofm.rejected} " +
                            "focus=${if (ofm.focus != null) 1 else 0} " +
                            "exits=${ofm.focus?.exits?.size ?: 0}")
                    }
                    val sceneSelection = RoadContextSceneSelector.select(
                        contextRoads, focus, lat, lon, upcoming, nearbyRoute)
                    sceneSelection.drawn.forEach { drawn ->
                        roadPolylines.add(MotoBLEMapRoadPolyline(
                            roadClass = drawn.road.roadClass, points = drawn.points))
                    }
                    DiagnosticLog.event("OSM_EXIT_CONT",
                        "ways=" + sceneSelection.continuedExitWays +
                        " branches=" + sceneSelection.continuedExitBranches +
                        " exits_sent=" +
                        sceneSelection.count(RoadContextSceneSelector.Role.EXIT) +
                        " slots=" + sceneSelection.slots +
                        " points=" + sceneSelection.points)
                    DiagnosticLog.event("OSM_RING",
                        "candidates=" + (analysis.candidates + (ofm?.circuits ?: 0)) +
                        " qualified=" + (analysis.qualified + (if (ofm?.focus != null) 1 else 0)) +
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
        val rawRemainingMeters = routeProgressMatcher.remaining(
            lat, lon, travelHeading, lastGpsSpeedMs)
            ?: baseSnapshot.remainingDistanceM
        // Do not let a nearest-segment snap at an intersection consume
        // 20-30m instantaneously and make the next turn disappear early.
        val remainingMeters = routeDistanceGuard.stabilize(
            rawRemainingMeters, lat, lon)
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
        // Private replay-only evidence. Same accepted fix, no reroute/
        // matching/heading change; inert when GPS recording is OFF.
        ReplayTraceRecorder.progress(
            lat, lon, baseSnapshot.routeToken, baseSnapshot.routeGeneration,
            lastGpsSpeedMs, travelHeading, rawRemainingMeters, remainingMeters,
            routeProgressMatcher.lastMatchEvidence, upcoming,
            turnPlan.totalShapeMeters)
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

    fun importUnifiedOfflinePack(context: Context, uri: Uri) {
        if (_isNavigating.value || _isDownloadingMap.value ||
            _photonImporting.value || _valhallaImporting.value) {
            _errorMessage.value = "Arrestare navigazione e altre importazioni"
            return
        }
        mapDownloadJob = viewModelScope.launch {
            _isDownloadingMap.value = true
            _photonImporting.value = true
            _valhallaImporting.value = true
            _downloadProgress.value = 0f
            _downloadStatus.value = "Verifica pacchetto offline..."
            try {
                withContext(Dispatchers.IO) {
                    val installerJob = coroutineContext[Job]
                    UnifiedOfflineMapImporter.install(
                        context.applicationContext, uri,
                        { message -> _downloadStatus.value = message },
                        { installerJob?.isActive == false }
                    )
                }
                refreshSavedMaps(context)
                val graphInstalled = OfflineValhallaRouter.hasTiles(context.applicationContext)
                val photonInstalled = OfflinePhotonPlaceSearch.installed(context.applicationContext)
                val touristInstalled = withContext(Dispatchers.IO) {
                    OfflineTouristPlaceSearch.installed(context.applicationContext)
                }
                if (!graphInstalled || !photonInstalled || !touristInstalled) {
                    throw java.io.IOException(
                        "Verifica finale: Valhalla, Photon o Road DNA non disponibile")
                }
                _roadDnaInstalledBytes.value =
                    OfflineTouristPlaceSearch.file(context.applicationContext).length()
                invalidateRoadDna()
                _valhallaStatus.value = "Grafo Valhalla installato dal pacchetto unificato"
                _photonStatus.value = "Indice Photon installato dal pacchetto unificato"
                _downloadProgress.value = 1f
                _downloadStatus.value = "MotoMap, Photon, Road DNA e Valhalla installati"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _downloadStatus.value = "Pacchetto non installato completamente: ${e.message}"
                _errorMessage.value = _downloadStatus.value
                refreshSavedMaps(context)
            } finally {
                _isDownloadingMap.value = false
                _photonImporting.value = false
                _valhallaImporting.value = false
            }
        }
    }

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
        // A legacy v1 file is not a usable offline index in the v2 reader.
        _photonInstalledBytes.value = if (OfflinePhotonPlaceSearch.installed(context))
            OfflinePhotonPlaceSearch.file(context).length() else 0L
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
        // A rider can follow a later leg of this same route while the
        // progress matcher stays pinned to its former leg. Whole-route
        // proximity cannot detect that mismatch; require sustained, good
        // GNSS motion before considering a new Valhalla route.
        val stalledOnRoute = routeProgressStallPolicy.observe(
            currentSnapshot?.routeToken ?: 0L, now,
            latitude, longitude,
            routeProgressMatcher.matchedProgressMeters(),
            lastGpsSpeedMs,
            if (bearingIsFresh) lastReliableGpsBearing else null,
            lastFixAccuracyM, distanceFromRoute,
            routeProgressMatcher.distanceToMatchedCorridorMeters(
                latitude, longitude))
        // No arbitrary segment jump: reuse the established de-bounced
        // reroute path, mandatory stops and heading-aware route request.
        val farFromRoute = distanceFromRoute > 45.0
        if ((!farFromRoute && !reverseCourse && !stalledOnRoute) ||
            lastGpsSpeedMs < 1.5f ||
            rerouteStability.shouldDeferWhileTurning(
                now, distanceFromRoute, lastFixAccuracyM)) {
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
                now, latitude, longitude, distanceFromRoute,
                !farFromRoute && !stalledOnRoute)) {
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
            "trigger off_route_m=${distanceFromRoute.toInt()} reverse=$reverseCourse " +
                "stalled=$stalledOnRoute speed_mps=$lastGpsSpeedMs " +
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
                routeProgressStallPolicy.onRouteChanged()
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
                sendNavigationSnapshotWithStops(refreshed)
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
        if (!hasFirstFix && previewOriginOverride == null)
            throw IllegalStateException("Attendi un fix GPS valido prima del calcolo")
        val (lat, lon) = previewOriginOverride ?: currentLocation.value
        val response = resolveRoute(lat, lon, place)
        val route = response.getJSONObject("route")
        val maneuvers = route.optJSONArray("maneuvers")
        val instructions = (0 until (maneuvers?.length() ?: 0)).map { i ->
            maneuvers!!.getJSONObject(i).optString("instruction", "")
        }.filter { it.isNotBlank() }
        val displayName = if (_routeWaypoints.value.firstOrNull()?.displayArea == "Tour circolare")
            "Tour circolare · distanza effettiva"
        else "Percorso Valhalla"
        return listOf(RouteOption(
            "valhalla-0",
            displayName,
            route.getLong("total_distance_m"), route.getLong("estimated_duration_s"),
            instructions, route))
    }

    private fun performRoutingWithGeometry(
        place: PlaceSearchResult, isReroute: Boolean = false,
        pendingOverride: List<PlaceSearchResult>? = null
    ): PlannedNavigation {
        if (!hasFirstFix)
            throw IllegalStateException("Fix GPS assente: impossibile navigare")
        val (originLat, originLon) = currentLocation.value
        // A moving route departure needs the same road-direction hint as a
        // reroute. Do not substitute the phone's magnetic compass at rest.
        val bearing = if (lastGpsSpeedMs >= 3f &&
            SystemClock.elapsedRealtime() - lastReliableGpsBearingMs in 0L..5_000L)
            lastReliableGpsBearing else null
        val pending = pendingOverride ?: if (isReroute)
            activeWaypoints.drop(nextWaypointIndex) else _routeWaypoints.value
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
                    currentSnapshot?.let { sendNavigationSnapshotWithStops(it) }
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
                    sendNavigationSnapshotWithStops(idleSnapshot)
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
