package com.motogps.android.routing

import android.content.Context
import com.valhalla.valhalla.Valhalla
import com.valhalla.valhalla.config.ValhallaConfigFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToLong

/**
 * Offline routing prototype. A Valhalla graph extract is NOT the same as a
 * .motomap (display tile) file. The native actor is retained between requests.
 *
 * No network calls are made from this class. Until a graph is installed, the
 * existing gateway can continue providing routes during migration.
 */
internal object OfflineValhallaRouter {
    private var engine: Valhalla? = null
    private var loadedTilePath: String? = null

    fun tileFile(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir,
            "routing/valhalla_tiles.tar")

    fun hasTiles(context: Context): Boolean =
        tileFile(context).let { it.isFile && it.length() > 0L }

    @Synchronized
    fun route(
        context: Context,
        originLat: Double,
        originLon: Double,
        destinationLat: Double,
        destinationLon: Double,
        isReroute: Boolean = false,
        travelBearingDegrees: Float? = null,
    ): JSONObject? {
        val tiles = tileFile(context)
        if (!tiles.isFile || tiles.length() == 0L) return null
        val path = tiles.absolutePath
        if (engine == null || loadedTilePath != path) {
            engine?.close()
            engine = null
            val config = ValhallaConfigFactory.usingTileExtract(path)
            engine = Valhalla(context.applicationContext, config)
            loadedTilePath = path
        }
        val departure = JSONObject()
            .put("lat", originLat)
            .put("lon", originLon)
            .put("type", "break")
        // Only a fresh GPS course is supplied by the caller; compass heading
        // is not a reliable travel bearing. Valhalla otherwise snaps freely.
        if (isReroute && travelBearingDegrees != null &&
            travelBearingDegrees.isFinite() && travelBearingDegrees in 0f..359.999f) {
            departure.put("heading", travelBearingDegrees.toDouble())
            departure.put("heading_tolerance", 45)
        }
        val request = JSONObject()
            .put("locations", JSONArray().put(departure).put(
                JSONObject().put("lat", destinationLat).put("lon", destinationLon).put("type", "break")))
            .put("costing", "auto")
            .put("shape_format", "polyline6")
            .put("directions_options", JSONObject().put("units", "kilometers").put("language", "it-IT"))
        val raw = requireNotNull(engine).routeRaw(request.toString())
        return ValhallaRouteAdapter.toMotoRoute(
            JSONObject(raw), originLat, originLon, destinationLat, destinationLon)
    }

    @Synchronized
    fun close() {
        engine?.close()
        engine = null
        loadedTilePath = null
    }
}

/**
 * Converts Valhalla's trip/legs/maneuvers JSON into the Moto-GPS gateway's
 * route schema, keeping the existing Android snapshot and BLE path unchanged.
 */
internal object ValhallaRouteAdapter {
    fun decodePolyline6(shape: String): List<Pair<Double, Double>> {
        var index = 0
        var lat = 0L
        var lon = 0L
        val points = ArrayList<Pair<Double, Double>>()
        fun number(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(index < shape.length && shift <= 35) { "Invalid polyline6 geometry" }
                val value = shape[index++].code - 63
                require(value in 0..63) { "Invalid polyline6 character" }
                result = result or ((value.toLong() and 31L) shl shift)
                shift += 5
                if (value < 32) break
            }
            return if (result and 1L == 1L) (result shr 1).inv() else result shr 1
        }
        while (index < shape.length) {
            lat += number()
            lon += number()
            require(lat in -90_000_000L..90_000_000L &&
                lon in -180_000_000L..180_000_000L) { "Invalid polyline6 coordinate" }
            points.add(Pair(lat / 1_000_000.0, lon / 1_000_000.0))
        }
        return points
    }

    fun toMotoRoute(
        response: JSONObject,
        originLat: Double,
        originLon: Double,
        destinationLat: Double,
        destinationLon: Double,
    ): JSONObject {
        val trip = response.getJSONObject("trip")
        val legs = trip.getJSONArray("legs")
        require(legs.length() > 0) { "Valhalla route has no legs" }
        val routePoints = JSONArray()
        val maneuvers = JSONArray()
        var pointOffset = 0
        var distanceBeforeLegM = 0L
        for (legIndex in 0 until legs.length()) {
            val leg = legs.getJSONObject(legIndex)
            val points = decodePolyline6(leg.getString("shape"))
            require(points.size >= 2) { "Valhalla leg has fewer than two points" }
            for (i in points.indices) {
                if (legIndex > 0 && i == 0) continue
                routePoints.put(JSONObject()
                    .put("latitude_deg", points[i].first)
                    .put("longitude_deg", points[i].second))
            }
            val legManeuvers = leg.optJSONArray("maneuvers") ?: JSONArray()
            val legStart = pointOffset
            for (j in 0 until legManeuvers.length()) {
                val maneuver = legManeuvers.getJSONObject(j)
                val index = maneuvers.length()
                val type = maneuver.optInt("type", 8)
                val names = maneuver.optJSONArray("street_names")
                val roadName = if (names != null && names.length() > 0)
                    names.optString(0, "") else ""
                maneuvers.put(JSONObject()
                    .put("index", index + 1)
                    .put("id", index + 1)
                    .put("route_point_index",
                        (legStart + maneuver.optInt("begin_shape_index", 0))
                            .coerceIn(0, routePoints.length() - 1))
                    .put("type", maneuverType(type))
                    .put("route_offset_m", distanceBeforeLegM)
                    .put("step_distance_m",
                        (maneuver.optDouble("length", 0.0) * 1000).roundToLong())
                    .put("road_name", roadName)
                    .put("instruction", maneuver.optString("instruction", "Prosegui"))
                    .put("roundabout_exit", maneuver.optInt("roundabout_exit_count", 0)))
            }
            pointOffset = routePoints.length() - 1
            distanceBeforeLegM += (leg.optJSONObject("summary")
                ?.optDouble("length", 0.0)?.times(1000) ?: 0.0).roundToLong()
        }
        val summary = trip.optJSONObject("summary") ?: JSONObject()
        val distanceMeters = (summary.optDouble("length", 0.0) * 1000).roundToLong()
        val durationSeconds = summary.optDouble("time", 0.0).roundToLong()
        require(routePoints.length() >= 2 && distanceMeters > 0) {
            "Valhalla route is incomplete"
        }
        val route = JSONObject()
            .put("route_id", "valhalla-${System.currentTimeMillis()}")
            .put("provider", "valhalla-local")
            .put("total_distance_m", distanceMeters)
            .put("estimated_duration_s", durationSeconds)
            .put("geometry_gcj02", routePoints)
            .put("maneuvers", maneuvers)
            .put("origin", JSONObject().put("latitude_deg", originLat).put("longitude_deg", originLon))
            .put("destination", JSONObject().put("latitude_deg", destinationLat).put("longitude_deg", destinationLon))
        return JSONObject().put("protocol_version", 1).put("route", route)
    }

    private fun maneuverType(type: Int): String = when (type) {
        4, 5, 6 -> "arrive"
        9, 10, 11, 18, 20, 23 -> "turn_right"
        14, 15, 16, 19, 21, 24 -> "turn_left"
        12, 13 -> "u_turn"
        26, 27 -> "roundabout"
        else -> "continue"
    }
}
