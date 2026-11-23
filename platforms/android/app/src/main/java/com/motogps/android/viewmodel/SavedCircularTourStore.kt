package com.motogps.android.viewmodel

import android.content.Context
import com.motogps.android.routing.RoutePreferences
import com.motogps.android.routing.RouteStyle
import org.json.JSONArray
import org.json.JSONObject

/** Save a tour PLAN, not its obsolete route geometry, for later Valhalla recalculation. */
data class SavedCircularTour(
    val id: String,
    val title: String,
    val departure: Pair<Double, Double>,
    val desiredKm: Int,
    val orientationDegrees: Int,
    val mandatory: List<PlaceSearchResult>,
    val via: List<PlaceSearchResult>,
    val preferences: RoutePreferences
)

internal object SavedCircularTourStore {
    private const val PREFS = "moto_saved_circular_tours"
    private const val KEY = "tours"
    const val MAX_TOURS = 30

    @Synchronized fun read(context: Context): List<SavedCircularTour> = try {
        decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]").orEmpty())
    } catch (e: Exception) {
        android.util.Log.w("SavedCircularTourStore", "Errore lettura tour salvati", e)
        emptyList()
    }

    @Synchronized fun save(context: Context, tour: SavedCircularTour): List<SavedCircularTour> {
        val normalizedTitle = tour.title.trim()
        require(normalizedTitle.isNotEmpty()) { "Nome tour vuoto" }
        require(tour.departure.first.isFinite() && tour.departure.second.isFinite() &&
            tour.departure.first in -90.0..90.0 && tour.departure.second in -180.0..180.0)
        require(tour.desiredKm in com.motogps.android.routing.CircularTourPlanner.MIN_DISTANCE_KM..com.motogps.android.routing.CircularTourPlanner.MAX_DISTANCE_KM)
        require(tour.orientationDegrees in 0..359)
        require(tour.via.size <= com.motogps.android.routing.ValhallaRouteLocations.MAX_STOPS &&
            tour.mandatory.size <= com.motogps.android.routing.ValhallaRouteLocations.MAX_STOPS)
        val cleanTour = tour.copy(title = normalizedTitle.take(80))
        val existing = read(context)
        val updated = (listOf(cleanTour) + existing.filterNot {
            it.id == cleanTour.id || it.title.trim().equals(cleanTour.title, ignoreCase = true)
        }).take(MAX_TOURS)
        require(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, encode(updated)).commit()) {
            "Impossibile salvare il tour sul telefono"
        }
        return updated
    }

    @Synchronized fun remove(context: Context, id: String): List<SavedCircularTour> {
        val updated = read(context).filterNot { it.id == id }
        require(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, encode(updated)).commit()) {
            "Impossibile eliminare il tour"
        }
        return updated
    }

    internal fun encode(tours: List<SavedCircularTour>): String =
        JSONArray().apply { tours.forEach { put(toJson(it)) } }.toString()

    internal fun decode(raw: String): List<SavedCircularTour> {
        val array = JSONArray(raw)
        return (0 until minOf(array.length(), MAX_TOURS)).mapNotNull { index ->
            try { fromJson(array.getJSONObject(index)) } catch (_: Exception) { null }
        }
    }

    private fun point(place: PlaceSearchResult): JSONObject = JSONObject()
        .put("name", place.name)
        .put("address", place.address)
        .put("area", place.displayArea)
        .put("lat", place.latitude)
        .put("lon", place.longitude)

    private fun point(obj: JSONObject): PlaceSearchResult {
        val lat = obj.getDouble("lat")
        val lon = obj.getDouble("lon")
        require(lat.isFinite() && lon.isFinite() &&
            lat in -90.0..90.0 && lon in -180.0..180.0)
        return PlaceSearchResult(obj.optString("name"), obj.optString("address"),
            obj.optString("area"), lat, lon)
    }

    private fun toJson(tour: SavedCircularTour): JSONObject {
        val prefs = tour.preferences
        return JSONObject()
            .put("version", 1).put("id", tour.id).put("title", tour.title)
            .put("startLat", tour.departure.first).put("startLon", tour.departure.second)
            .put("desiredKm", tour.desiredKm)
            .put("orientation", tour.orientationDegrees)
            .put("mandatory", JSONArray().apply { tour.mandatory.forEach { put(point(it)) } })
            .put("via", JSONArray().apply { tour.via.forEach { put(point(it)) } })
            .put("preferences", JSONObject()
                .put("style", prefs.style.name)
                .put("highways", prefs.avoidHighways)
                .put("tolls", prefs.avoidTolls)
                .put("ferries", prefs.avoidFerries)
                .put("curves", prefs.curveLevel))
    }

    private fun fromJson(obj: JSONObject): SavedCircularTour {
        require(obj.getInt("version") == 1)
        val lat = obj.getDouble("startLat")
        val lon = obj.getDouble("startLon")
        require(lat.isFinite() && lon.isFinite() &&
            lat in -90.0..90.0 && lon in -180.0..180.0)
        val desired = obj.getInt("desiredKm")
        val orientation = obj.getInt("orientation")
        require(desired in 30..300 && orientation in 0..359)
        val pref = obj.getJSONObject("preferences")
        val style = RouteStyle.entries.firstOrNull {
            it.name == pref.optString("style")
        } ?: RouteStyle.PANORAMIC
        val mandatory = obj.getJSONArray("mandatory").let { arr ->
            (0 until arr.length()).map { point(arr.getJSONObject(it)) }
        }
        val via = obj.getJSONArray("via").let { arr ->
            (0 until arr.length()).map { point(arr.getJSONObject(it)) }
        }
        require(via.size <= 8 && mandatory.size <= 8)
        return SavedCircularTour(obj.getString("id"), obj.getString("title"),
            lat to lon, desired, orientation, mandatory, via,
            RoutePreferences(style,
                pref.optBoolean("highways"), pref.optBoolean("tolls"),
                pref.optBoolean("ferries"), pref.optInt("curves").coerceIn(0, 3)))
    }
}
