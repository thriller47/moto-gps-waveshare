package com.motogps.android.routing

import com.motogps.android.viewmodel.RoadDnaAnalysis
import com.motogps.android.viewmodel.RoadDnaElevation
import com.motogps.android.viewmodel.RoadDnaTerrainAnalysis
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Optional, user-initiated height lookup for Road DNA. Does not reroute, modify
 * navigation geometry, or make calls during GPS updates / BLE recalculation.
 * Public FOSSGIS is best effort: no retry or fallback on HTTP 429/503.
 */
internal object OnlineValhallaElevation {
    private const val ENDPOINT = "https://valhalla1.openstreetmap.de/height"
    private const val MAX_VERTICES = 4500
    private const val MAX_RESPONSE_BYTES = 3 * 1024 * 1024
    private const val EARTH_RADIUS = 6371008.8

    private fun distance(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val lat1 = Math.toRadians(a.first)
        val lat2 = Math.toRadians(b.first)
        val latDelta = lat2 - lat1
        val lonDelta = Math.toRadians(b.second - a.second)
        val hav = sin(latDelta / 2) * sin(latDelta / 2) +
            cos(lat1) * cos(lat2) * sin(lonDelta / 2) * sin(lonDelta / 2)
        return 2.0 * EARTH_RADIUS * atan2(sqrt(hav.coerceAtLeast(0.0)),
            sqrt((1.0 - hav).coerceAtLeast(0.0)))
    }

    /** A bounded, distance-sampled copy of the already calculated road geometry. */
    private fun sample(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (points.size < 2) return emptyList()
        val clean = ArrayList<Pair<Double, Double>>(points.size)
        val lengths = ArrayList<Double>(points.size)
        var total = 0.0
        for (point in points) {
            if (!point.first.isFinite() || !point.second.isFinite() ||
                point.first !in -90.0..90.0 || point.second !in -180.0..180.0) {
                return emptyList()
            }
            if (clean.isNotEmpty()) {
                val segment = distance(clean.last(), point)
                if (segment < 0.25) continue
                if (segment > 3000.0) return emptyList()
                total += segment
            }
            clean.add(point)
            lengths.add(total)
        }
        if (total < 150.0 || clean.size < 2) return emptyList()
        val spacing = max(100.0, total / (MAX_VERTICES - 1))
        val result = ArrayList<Pair<Double, Double>>(MAX_VERTICES + 1)
        result.add(clean.first())
        var at = spacing
        var index = 1
        while (at < total && result.size < MAX_VERTICES - 1) {
            while (index < lengths.lastIndex && lengths[index] < at) index++
            val a = clean[index - 1]
            val b = clean[index]
            val f = ((at - lengths[index - 1]) /
                (lengths[index] - lengths[index - 1])).coerceIn(0.0, 1.0)
            result.add((a.first + f * (b.first - a.first)) to
                (a.second + f * (b.second - a.second)))
            at += spacing
        }
        result.add(clean.last())
        return result
    }

    /** Polyline6 encoding uses (latitude, longitude) signed delta pairs. */
    private fun encode(points: List<Pair<Double, Double>>): String {
        val output = StringBuilder(points.size * 9)
        var oldLat = 0
        var oldLon = 0
        fun appendDiff(value: Int) {
            var v = (value shl 1) xor (value shr 31)
            while (v >= 0x20) {
                output.append(((0x20 or (v and 0x1f)) + 63).toChar())
                v = v ushr 5
            }
            output.append((v + 63).toChar())
        }
        for ((lat, lon) in points) {
            val ilat = (lat * 1_000_000.0).roundToInt()
            val ilon = (lon * 1_000_000.0).roundToInt()
            appendDiff(ilat - oldLat)
            appendDiff(ilon - oldLon)
            oldLat = ilat
            oldLon = ilon
        }
        return output.toString()
    }

    internal fun heightRequest(route: JSONObject): JSONObject? {
        val shape = sample(RoadDnaAnalysis.geometry(route))
        if (shape.size < 3) return null
        return JSONObject()
            .put("encoded_polyline", encode(shape))
            .put("shape_format", "polyline6")
            .put("range", true)
            .put("height_precision", 0)
    }

    /** Never interpret null heights as sea level or interpolate missing spans. */
    internal fun parseHeightResponse(response: JSONObject): RoadDnaElevation? {
        val pairs = response.optJSONArray("range_height") ?: return null
        if (pairs.length() !in 3..MAX_VERTICES) return null
        val profile = JSONArray()
        var previous = -1.0
        for (index in 0 until pairs.length()) {
            val pair = pairs.optJSONArray(index) ?: return null
            if (pair.length() != 2) return null
            val range = pair.optDouble(0, Double.NaN)
            if (!range.isFinite() || range < 0.0 || range <= previous) {
                // First sample is 0; all subsequent samples must advance.
                return null
            }
            val meters = pair.optDouble(1, Double.NaN)
            profile.put(JSONObject().put("distance_m", range).put("elevation_m",
                if (meters.isFinite() && meters in -500.0..9000.0)
                    meters else JSONObject.NULL))
            previous = range
        }
        return RoadDnaTerrainAnalysis.elevation(
            JSONObject().put("elevation_profile", profile))
    }

    fun fetch(route: JSONObject): RoadDnaElevation? {
        val request = heightRequest(route) ?: return null
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 4_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "Moto-GPS-Android")
            setRequestProperty("X-Client-Id", "moto-gps-waveshare")
        }
        try {
            conn.outputStream.use {
                it.write(request.toString().toByteArray(Charsets.UTF_8))
            }
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { input ->
                val result = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (result.size() + n > MAX_RESPONSE_BYTES)
                        throw IOException("Profilo altimetrico troppo grande")
                    result.write(buffer, 0, n)
                }
                result.toByteArray()
            }
            return parseHeightResponse(JSONObject(String(bytes, Charsets.UTF_8)))
        } finally {
            conn.disconnect()
        }
    }
}
