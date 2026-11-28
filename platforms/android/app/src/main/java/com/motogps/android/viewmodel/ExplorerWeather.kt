package com.motogps.android.viewmodel

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToLong

internal object ExplorerWeather {
    private const val MAX_POINTS = 21
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_CACHE_AGE = 6 * 3600L
    private const val FRESH_CACHE_AGE = 30 * 60L
    private const val VARIABLES = "temperature_2m,precipitation_probability,precipitation,wind_speed_10m,wind_gusts_10m,wind_direction_10m,weather_code"

    data class Sample(val lat: Double, val lon: Double, val km: Double, val elapsed: Long)
    data class Plan(val name: String, val samples: List<Sample>)
    data class Reading(val temperature: Double, val probability: Double, val precipitation: Double,
                       val wind: Double, val gust: Double, val direction: Double, val code: Int)
    data class Point(val sample: Sample, val eta: Long, val reading: Reading?)
    data class Profile(val name: String, val points: List<Point>) {
        val readings get() = points.mapNotNull { it.reading }
        val complete get() = points.isNotEmpty() && readings.size == points.size
        val maxProbability get() = readings.maxOfOrNull { it.probability }
        val maxGust get() = readings.maxOfOrNull { it.gust }
    }
    data class Forecast(val plans: List<Plan>, val locations: JSONArray, val fetchedAt: Long, val cached: Boolean) {
        fun profiles(departure: Long): List<Profile> {
            var offset = 0
            return plans.map { plan ->
                Profile(plan.name, plan.samples.map { sample ->
                    val eta = departure + sample.elapsed
                    Point(sample, eta, reading(locations.optJSONObject(offset++), eta))
                })
            }
        }
    }

    /** Sample the actual geometry, including the return to origin of a circular tour.
     * ETA uses total Valhalla driving time apportioned by distance; no stop time or traffic. */
    fun sample(shape: List<Pair<Double, Double>>, duration: Long): List<Sample> {
        if (shape.size < 2 || duration <= 0 || duration > 86400 ||
            shape.any { !it.first.isFinite() || !it.second.isFinite() ||
                it.first !in -90.0..90.0 || it.second !in -180.0..180.0 }) return emptyList()
        val chain = DoubleArray(shape.size)
        for (i in 1 until shape.size) {
            val d = RoadDnaTerrainAnalysis.distance(shape[i - 1], shape[i])
            if (!d.isFinite() || d > 50000) return emptyList()
            chain[i] = chain[i - 1] + d
        }
        val total = chain.last()
        if (total < 1) return emptyList()
        val intervals = maxOf(ceil(total / 15000).toInt(), ceil(duration / 1800.0).toInt())
            .coerceIn(1, MAX_POINTS - 1)
        var segment = 1
        return (0..intervals).map { i ->
            val fraction = i.toDouble() / intervals
            val at = total * fraction
            while (segment < chain.lastIndex && chain[segment] < at) segment++
            val a = shape[segment - 1]
            val b = shape[segment]
            val span = chain[segment] - chain[segment - 1]
            val f = if (span > 0) ((at - chain[segment - 1]) / span).coerceIn(0.0, 1.0) else 0.0
            Sample(a.first + (b.first - a.first) * f, a.second + (b.second - a.second) * f,
                at / 1000, (duration * fraction).roundToLong())
        }
    }

    fun plans(options: List<RouteOption>): List<Plan> = options.take(3).map {
        Plan(it.name, sample(RoadDnaAnalysis.geometry(it.rawRouteJson), it.durationSeconds))
    }

    /** Precipitation and gust refer to the preceding hour: choose the hour ending
     * at or immediately after ETA. Never substitute missing data or extrapolate. */
    fun reading(location: JSONObject?, eta: Long): Reading? {
        val hourly = location?.optJSONObject("hourly") ?: return null
        val time = hourly.optJSONArray("time") ?: return null
        val target = Math.floorDiv(eta, 3600L) * 3600L + if (eta % 3600L == 0L) 0L else 3600L
        val i = (0 until time.length()).firstOrNull { time.optLong(it, Long.MIN_VALUE) == target } ?: return null
        fun value(key: String, range: ClosedFloatingPointRange<Double>): Double? {
            val v = hourly.optJSONArray(key)?.optDouble(i, Double.NaN) ?: return null
            return v.takeIf { it.isFinite() && it in range }
        }
        val temperature = value("temperature_2m", -100.0..70.0) ?: return null
        val probability = value("precipitation_probability", 0.0..100.0) ?: return null
        val precipitation = value("precipitation", 0.0..500.0) ?: return null
        val wind = value("wind_speed_10m", 0.0..400.0) ?: return null
        val gust = value("wind_gusts_10m", 0.0..500.0) ?: return null
        val direction = value("wind_direction_10m", 0.0..360.0) ?: return null
        val code = value("weather_code", 0.0..99.0)?.toInt() ?: return null
        return Reading(temperature, probability, precipitation, wind, gust, direction, code)
    }

    /** Only compare complete forecasts; no recommendation when tied. Not a safety score. */
    fun leastPrecipitation(profiles: List<Profile>): Int? {
        if (profiles.size < 2 || profiles.any { !it.complete }) return null
        val sorted = profiles.indices.sortedBy { profiles[it].maxProbability ?: 101.0 }
        val best = sorted.first()
        return best.takeIf { profiles[best].maxProbability!! < profiles[sorted[1]].maxProbability!! }
    }

    private fun coordinates(points: List<Sample>, latitude: Boolean): String =
        points.joinToString(",") { String.format(Locale.US, "%.4f", if (latitude) it.lat else it.lon) }

    /** Explicit preview only. One batched request for at most 63 samples.
     * One bounded cache entry, keyed to the exact ordered query coordinates.
     * Cache is not used across different routes or beyond its validity. */
    fun fetch(context: Context, plans: List<Plan>, force: Boolean = false): Forecast {
        val points = plans.flatMap { it.samples }
        require(plans.isNotEmpty() && plans.all { it.samples.isNotEmpty() } && points.size <= 63)
        val lat = coordinates(points, true)
        val lon = coordinates(points, false)
        val key = MessageDigest.getInstance("SHA-256").digest(("v1|" + lat + "|" + lon).toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val file = AtomicFile(File(context.cacheDir, "explorer-weather-v1.json"))
        val now = System.currentTimeMillis() / 1000
        val cached = try {
            if (file.baseFile.length() > MAX_BYTES + 4096) null else {
                val json = JSONObject(file.readFully().toString(Charsets.UTF_8))
                val stamp = json.getLong("fetched_at")
                val data = json.getJSONArray("locations")
                if (json.getString("key") == key && now - stamp in 0..MAX_CACHE_AGE &&
                    data.length() == points.size) Forecast(plans, data, stamp, true) else null
            }
        } catch (_: Exception) { null }
        if (!force && cached != null && now - cached.fetchedAt < FRESH_CACHE_AGE) return cached
        try {
            val url = URL("https://api.open-meteo.com/v1/forecast?latitude=" + lat +
                "&longitude=" + lon + "&hourly=" + VARIABLES +
                "&timezone=GMT&timeformat=unixtime&forecast_days=3&wind_speed_unit=kmh")
            val conn = url.openConnection() as HttpURLConnection
            val data = try {
                conn.connectTimeout = 5000
                conn.readTimeout = 10000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode !in 200..299) throw IOException("HTTP " + conn.responseCode)
                val bytes = conn.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (out.size() + n > MAX_BYTES) throw IOException("Risposta meteo troppo grande")
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
                JSONArray(bytes.toString(Charsets.UTF_8))
            } finally { conn.disconnect() }
            if (data.length() != points.size || (0 until data.length()).any {
                data.optJSONObject(it)?.optJSONObject("hourly")?.optJSONArray("time") == null
            }) throw IOException("Risposta meteo incompleta")
            val stamp = System.currentTimeMillis() / 1000
            try {
                val bytes = JSONObject().put("key", key).put("fetched_at", stamp)
                    .put("locations", data).toString().toByteArray(Charsets.UTF_8)
                val stream = file.startWrite()
                try { stream.write(bytes); file.finishWrite(stream) }
                catch (e: Exception) { file.failWrite(stream) }
            } catch (_: Exception) { /* Network result remains usable. */ }
            return Forecast(plans, data, stamp, false)
        } catch (e: Exception) {
            return cached ?: throw IOException("Previsioni non disponibili", e)
        }
    }
}
