package com.motogps.android.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.motogps.android.ble.MotoBLEManager
import com.motogps.android.jni.MotoBLENavigationSnapshot
import com.motogps.android.jni.MotoBLEProtocolCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class PlaceSearchResult(
    val name: String,
    val address: String,
    val displayArea: String,
    val latitude: Double,
    val longitude: Double
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

    private val bleCodec = MotoBLEProtocolCodec()
    private var codecPtr: Long = 0

    init {
        codecPtr = bleCodec.nativeInit(512)
    }

    override fun onCleared() {
        super.onCleared()
        if (codecPtr != 0L) {
            bleCodec.nativeDestroy(codecPtr)
        }
    }

    fun searchDestination(query: String, gatewayUrl: String) {
        if (query.isBlank() || gatewayUrl.isBlank()) return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val results = withContext(Dispatchers.IO) {
                    performSearch(query, gatewayUrl)
                }
                _searchResults.value = results
            } catch (e: Exception) {
                _errorMessage.value = "Errore di connessione: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun startNavigation(place: PlaceSearchResult, gatewayUrl: String) {
        if (gatewayUrl.isBlank()) return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val snapshot = withContext(Dispatchers.IO) {
                    performRouting(place, gatewayUrl)
                }

                if (codecPtr != 0L) {
                    val frames = bleCodec.nativeEncodeNavigationSnapshot(codecPtr, snapshot)
                    bleManager.sendFrames(frames)
                }
            } catch (e: Exception) {
                _errorMessage.value = "Errore durante la navigazione: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun performRouting(place: PlaceSearchResult, gatewayBaseUrl: String): MotoBLENavigationSnapshot {
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

        // Build the RouteRequest payload per NavigationModels.swift
        val jsonPayload = JSONObject().apply {
            put("protocol_version", 1)
            put("request_id", System.currentTimeMillis().rem(Int.MAX_VALUE).toInt())
            put("routeMode", "driving")

            // Dummy origin for demonstration (since we don't have GPS fix yet in this scope)
            val originObj = JSONObject().apply {
                put("coordinate_system", "WGS84")
                put("longitude_deg", 116.397428) // Example origin
                put("latitude_deg", 39.90923)
            }
            put("origin", originObj)

            val destObj = JSONObject().apply {
                put("coordinate_system", "WGS84")
                put("longitude_deg", place.longitude)
                put("latitude_deg", place.latitude)
            }
            put("destination", destObj)
            put("isReroute", false)
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

                val protocolVersion = jsonObject.optInt("protocol_version", 0)
                if (protocolVersion != 1) {
                    throw Exception("Invalid protocol version")
                }

                val routeObj = jsonObject.getJSONObject("route")
                val totalDistanceM = routeObj.optDouble("totalDistanceM", 0.0).toLong()
                val totalDurationS = routeObj.optInt("totalDurationS", 0).toLong()
                val routeID = routeObj.optString("routeID", "route_${System.currentTimeMillis()}")

                // Parse first maneuver for UI representation
                var maneuverName = "continue"
                var instruction = "Segui il percorso"
                val maneuversArray = routeObj.optJSONArray("maneuvers")
                if (maneuversArray != null && maneuversArray.length() > 0) {
                    val firstManeuver = maneuversArray.getJSONObject(0)
                    // type is nested object enum in iOS, simplify for now
                    maneuverName = firstManeuver.optString("type", "continue")
                    instruction = firstManeuver.optString("instruction", "Segui il percorso")
                }

                // Return a fully constructed snapshot mapped to the route data
                return MotoBLENavigationSnapshot(
                    stateName = "navigating",
                    networkName = "online",
                    displayPageName = "navigation",
                    maneuverName = maneuverName,
                    trafficName = "free_flow",
                    hasDestination = true,
                    hasFix = true, // Force true to show UI updates
                    gnssStale = false,
                    offRoute = false,
                    routeRequestInFlight = false,
                    trafficRequestInFlight = false,
                    routeToken = (routeID.hashCode().toLong() and 0xFFFFFFFFL), // basic hash for token
                    routeGeneration = 1L,
                    maneuverID = 1L,
                    distanceToManeuverM = totalDistanceM, // simplified assumption
                    remainingDistanceM = totalDistanceM,
                    remainingDurationS = totalDurationS,
                    routeProgressM = 0L,
                    totalDistanceM = totalDistanceM,
                    speedDeciKPH = 0,
                    speedLimitKPH = 0,
                    headingCentiDegrees = 0,
                    accuracyDecimeters = 50,
                    crossTrackDecimeters = 0,
                    roundaboutExit = 0,
                    instructionText = instruction
                )
            } else {
                throw Exception("HTTP Error $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun performSearch(query: String, gatewayBaseUrl: String): List<PlaceSearchResult> {
        val baseUrl = if (gatewayBaseUrl.endsWith("/")) gatewayBaseUrl else "$gatewayBaseUrl/"
        val endpoint = "${baseUrl}v1/places?keywords=${URLEncoder.encode(query, "UTF-8")}"

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

                val protocolVersion = jsonObject.optInt("protocol_version", 0)
                if (protocolVersion != 1) {
                    throw Exception("Invalid protocol version")
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
