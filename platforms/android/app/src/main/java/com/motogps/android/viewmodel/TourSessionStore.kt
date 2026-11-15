package com.motogps.android.viewmodel

import android.content.Context
import com.motogps.android.routing.RoutePreferences
import com.motogps.android.routing.RouteStyle
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small crash-safe navigation checkpoint. Never store obsolete route geometry:
 * resume calculates fresh Valhalla legs from the current accepted GPS fix.
 */
data class TourSession(
    val destination: PlaceSearchResult,
    val stops: List<PlaceSearchResult>,
    val completedStops: Int,
    val preferences: RoutePreferences,
    val pauseLatitude: Double,
    val pauseLongitude: Double,
    val updatedAtMillis: Long,
    val paused: Boolean
) {
    val pendingStops: List<PlaceSearchResult> get() = stops.drop(completedStops)
}

internal object TourSessionStore {
    private const val KEY = "session"
    private const val PREFS = "moto_tour_session"
    // Additional user edits on later legs can grow the lifetime visit history,
    // although each new Valhalla request still sends no more than 8 pending via points.
    private const val MAX_HISTORY_STOPS = 32

    private fun placeToJson(p: PlaceSearchResult) = JSONObject()
        .put("name", p.name).put("address", p.address).put("area", p.displayArea)
        .put("lat", p.latitude).put("lon", p.longitude)

    private fun placeFromJson(p: JSONObject): PlaceSearchResult {
        val lat = p.getDouble("lat")
        val lon = p.getDouble("lon")
        require(lat.isFinite() && lon.isFinite() &&
            lat in -90.0..90.0 && lon in -180.0..180.0)
        return PlaceSearchResult(p.optString("name"), p.optString("address"),
            p.optString("area"), lat, lon)
    }

    internal fun encode(session: TourSession): String {
        require(session.completedStops in 0..session.stops.size)
        require(session.stops.size <= MAX_HISTORY_STOPS)
        val prefs = session.preferences
        return JSONObject().put("version", 1)
            .put("destination", placeToJson(session.destination))
            .put("stops", JSONArray().apply {
                session.stops.forEach { put(placeToJson(it)) }
            })
            .put("completed", session.completedStops)
            .put("pauseLat", session.pauseLatitude)
            .put("pauseLon", session.pauseLongitude)
            .put("updatedAt", session.updatedAtMillis)
            .put("paused", session.paused)
            .put("preferences", JSONObject()
                .put("style", prefs.style.name)
                .put("highways", prefs.avoidHighways)
                .put("tolls", prefs.avoidTolls)
                .put("ferries", prefs.avoidFerries)
                .put("curves", prefs.curveLevel)).toString()
    }

    internal fun decode(raw: String): TourSession {
        val obj = JSONObject(raw)
        require(obj.getInt("version") == 1)
        val items = obj.getJSONArray("stops")
        require(items.length() <= MAX_HISTORY_STOPS)
        val stops = (0 until items.length()).map {
            placeFromJson(items.getJSONObject(it))
        }
        val completed = obj.getInt("completed")
        require(completed in 0..stops.size)
        val lat = obj.getDouble("pauseLat")
        val lon = obj.getDouble("pauseLon")
        require(lat.isFinite() && lon.isFinite() &&
            lat in -90.0..90.0 && lon in -180.0..180.0)
        val prefs = obj.getJSONObject("preferences")
        return TourSession(
            placeFromJson(obj.getJSONObject("destination")),
            stops, completed,
            RoutePreferences(
                style = RouteStyle.entries.firstOrNull {
                    it.name == prefs.optString("style")
                } ?: RouteStyle.FASTEST,
                avoidHighways = prefs.optBoolean("highways"),
                avoidTolls = prefs.optBoolean("tolls"),
                avoidFerries = prefs.optBoolean("ferries"),
                curveLevel = prefs.optInt("curves").coerceIn(0, 3)),
            lat, lon, obj.getLong("updatedAt"), obj.getBoolean("paused"))
    }

    fun read(context: Context): TourSession? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY, null) ?: return null
        return try { decode(stored) } catch (_: Exception) { null }
    }

    fun write(context: Context, session: TourSession) {
        require(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, encode(session)).commit()) {
            "Impossibile salvare lo stato del tour"
        }
    }

    fun clear(context: Context) {
        require(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).commit()) {
            "Impossibile cancellare lo stato del tour"
        }
    }
}
