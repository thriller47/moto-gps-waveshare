package com.motogps.android.viewmodel

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.min

/**
 * Private AES-GCM encrypted keys under noBackupFilesDir. Never put the keys in
 * SharedPreferences, Android backups, diagnostic ZIPs, repository or logs.
 * No request is made until the user taps a Road DNA online action.
 */
internal object RoadDnaApiKeys {
    private val providers = setOf("geoapify", "opentripmap")
    private fun check(provider: String) {
        require(provider in providers) { "Provider sconosciuto" }
    }
    private fun file(context: Context, provider: String) =
        File(context.noBackupFilesDir, "road_dna_${provider}.enc")

    private fun key(provider: String): SecretKey {
        check(provider)
        val alias = "moto_road_dna_$provider"
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }

    @Synchronized fun save(context: Context, provider: String, value: String) {
        check(provider)
        val trimmed = value.trim()
        require(trimmed.length in 1..512 && trimmed.none { it.isWhitespace() }) {
            "Chiave non valida"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(provider))
        val bytes = cipher.iv + cipher.doFinal(trimmed.toByteArray(StandardCharsets.UTF_8))
        val atomic = AtomicFile(file(context, provider))
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    @Synchronized fun read(context: Context, provider: String): String? {
        check(provider)
        val encrypted = file(context, provider)
        if (!encrypted.isFile) return null
        val bytes = AtomicFile(encrypted).openRead().use { it.readBytes() }
        if (bytes.size !in 29..2048) throw IOException("Chiave salvata non leggibile")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(provider), GCMParameterSpec(
            128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)),
            StandardCharsets.UTF_8)
    }

    @Synchronized fun present(context: Context, provider: String): Boolean =
        file(context, provider).isFile

    @Synchronized fun clear(context: Context, provider: String) {
        check(provider)
        AtomicFile(file(context, provider)).delete()
        // Also destroy the unused encryption key; deleting the ciphertext alone
        // does not fully implement the user's "Elimina chiave" action.
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.deleteEntry("moto_road_dna_$provider")
    }
}

data class RoadDnaOnlinePoi(
    val name: String,
    val category: String,
    val distanceMeters: Int,
    val source: String,
    val latitude: Double,
    val longitude: Double,
    val xid: String? = null
)

data class RoadDnaOnlineResult(
    val places: List<RoadDnaOnlinePoi>,
    val partial: Boolean,
    val message: String
)

internal object RoadDnaOnline {
    private const val GEO = "https://api.geoapify.com/v2/places"
    private const val OTM = "https://api.opentripmap.com/0.1/it/places"
    private const val BUFFER_METERS = 2000
    private const val MAX_GEO_CHUNKS = 3
    private const val GEO_VERTICES_PER_CHUNK = 800
    private const val MAX_RESPONSE = 2_000_000

    internal fun geoBody(points: List<Pair<Double, Double>>): JSONObject {
        require(points.size in 2..GEO_VERTICES_PER_CHUNK)
        val coordinates = JSONArray()
        points.forEach { (lat, lon) ->
            require(lat.isFinite() && lon.isFinite() &&
                lat in -90.0..90.0 && lon in -180.0..180.0)
            coordinates.put(JSONArray().put(lon).put(lat))
        }
        return JSONObject()
            .put("categories", JSONArray().put("tourism.attraction")
                .put("tourism.sights").put("entertainment.culture")
                .put("national_park"))
            .put("filter", JSONObject().put("type", "polyline")
                .put("geometry", JSONObject().put("type", "LineString")
                    .put("coordinates", coordinates))
                .put("buffer", BUFFER_METERS))
            .put("lang", "it")
            .put("limit", 100)
            .put("offset", 0)
    }

    internal fun tripRadiusUrl(lat: Double, lon: Double, apiKey: String): String =
        OTM + "/radius?radius=2000&lon=" + lon + "&lat=" + lat +
            "&rate=2&limit=25&format=json&apikey=" +
            URLEncoder.encode(apiKey, "UTF-8")

    private fun fetch(url: String, key: String? = null, body: String? = null): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            if (key != null) conn.setRequestProperty("x-api-key", key)
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            // Never include conn.url, request headers or response bodies in errors.
            when (conn.responseCode) {
                in 200..299 -> Unit
                401, 403 -> throw IOException("Chiave API non autorizzata")
                429 -> throw IOException("Limite richieste del provider (429)")
                else -> throw IOException("Servizio temporaneamente non disponibile (HTTP ${conn.responseCode})")
            }
            conn.inputStream.use { stream ->
                val bytes = ByteArray(MAX_RESPONSE + 1)
                var read = 0
                while (read < bytes.size) {
                    val count = stream.read(bytes, read, bytes.size - read)
                    if (count < 0) break
                    if (count == 0) continue
                    read += count
                }
                if (read > MAX_RESPONSE) throw IOException("Risposta online troppo grande")
                return String(bytes, 0, read, StandardCharsets.UTF_8)
            }
        } finally {
            conn.disconnect()
        }
    }

    internal fun parseGeo(json: JSONObject): List<RoadDnaOnlinePoi> {
        val features = json.optJSONArray("features") ?: return emptyList()
        val result = ArrayList<RoadDnaOnlinePoi>()
        for (i in 0 until min(features.length(), 100)) {
            val item = features.optJSONObject(i) ?: continue
            val coord = item.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val lon = coord.optDouble(0, Double.NaN)
            val lat = coord.optDouble(1, Double.NaN)
            if (!valid(lat, lon)) continue
            val p = item.optJSONObject("properties") ?: continue
            val name = p.optString("name").trim()
            if (name.isBlank()) continue
            result.add(RoadDnaOnlinePoi(name.take(160),
                p.optJSONArray("categories")?.optString(0).orEmpty().ifBlank { "turismo" },
                0, "Geoapify", lat, lon))
        }
        return result
    }

    internal fun parseTrip(array: JSONArray): List<RoadDnaOnlinePoi> {
        val result = ArrayList<RoadDnaOnlinePoi>()
        for (i in 0 until min(array.length(), 25)) {
            val item = array.optJSONObject(i) ?: continue
            val point = item.optJSONObject("point") ?: continue
            val lat = point.optDouble("lat", Double.NaN)
            val lon = point.optDouble("lon", Double.NaN)
            if (!valid(lat, lon)) continue
            val name = item.optString("name").trim()
            val xid = item.optString("xid").trim()
            if (name.isEmpty() || xid.isEmpty()) continue
            result.add(RoadDnaOnlinePoi(name.take(160),
                item.optString("kinds").substringBefore(",").take(50),
                0, "OpenTripMap", lat, lon, xid))
        }
        return result
    }

    private fun valid(lat: Double, lon: Double) = lat.isFinite() && lon.isFinite() &&
        lat in -90.0..90.0 && lon in -180.0..180.0

    private fun metersToRoute(point: RoadDnaOnlinePoi, route: List<Pair<Double, Double>>): Double =
        (1 until route.size).minOf { i ->
            RoadDnaAnalysis.distanceMeters(
                point.latitude to point.longitude, route[i - 1], route[i])
        }

    private fun addCandidates(
        output: MutableMap<String, RoadDnaOnlinePoi>,
        candidates: List<RoadDnaOnlinePoi>,
        route: List<Pair<Double, Double>>
    ) {
        for (p in candidates) {
            val distance = metersToRoute(p, route)
            if (!distance.isFinite() || distance > BUFFER_METERS) continue
            val item = p.copy(distanceMeters = distance.toInt())
            val identity = p.source + "|" + (p.xid ?: (
                p.name.trim().lowercase(java.util.Locale.ROOT) + "|" +
                    (p.latitude * 10000).toInt() + "|" + (p.longitude * 10000).toInt()))
            if (!output.containsKey(identity)) output[identity] = item
        }
    }

    suspend fun search(context: Context, route: List<Pair<Double, Double>>): RoadDnaOnlineResult {
        require(route.size >= 2) { "Geometria percorso mancante" }
        val geoKey = RoadDnaApiKeys.read(context, "geoapify")
        val tripKey = RoadDnaApiKeys.read(context, "opentripmap")
        if (geoKey.isNullOrBlank() && tripKey.isNullOrBlank())
            throw IllegalStateException("Configura una chiave Geoapify o OpenTripMap nelle impostazioni")
        val places = LinkedHashMap<String, RoadDnaOnlinePoi>()
        val errors = ArrayList<String>()
        var partial = false
        var completed = false
        if (!geoKey.isNullOrBlank()) {
            val chunks = ArrayList<List<Pair<Double, Double>>>()
            var index = 0
            while (index < route.size - 1 && chunks.size < MAX_GEO_CHUNKS) {
                val end = min(index + GEO_VERTICES_PER_CHUNK, route.size)
                chunks.add(route.subList(index, end))
                index = end - 1
            }
            if (index < route.size - 1) partial = true
            for (chunk in chunks) {
                try {
                    currentCoroutineContext().ensureActive()
                    val body = geoBody(chunk).toString()
                    val data = JSONObject(fetch(GEO, geoKey, body))
                    val received = parseGeo(data)
                    addCandidates(places, received, route)
                    completed = true
                    if ((data.optJSONArray("features")?.length() ?: 0) >= 100)
                        partial = true // First page was full; do not pretend it is exhaustive.
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    errors.add("Geoapify non disponibile")
                    partial = true
                    break
                }
            }
        }
        if (!tripKey.isNullOrBlank()) {
            // A few radius queries, not a continuous scan of the user's itinerary.
            // Sparse sampling means OpenTripMap coverage is always labelled partial.
            val indices = (0 until min(5, route.size)).map { n ->
                n * (route.size - 1) / (min(5, route.size) - 1)
            }.distinct()
            partial = true
            for (i in indices) {
                try {
                    currentCoroutineContext().ensureActive()
                    val (lat, lon) = route[i]
                    val received = parseTrip(JSONArray(fetch(tripRadiusUrl(lat, lon, tripKey))))
                    addCandidates(places, received, route)
                    completed = true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    errors.add("OpenTripMap non disponibile")
                    break
                }
            }
        }
        if (!completed) throw IOException(
            if (errors.isNotEmpty()) errors.joinToString("; ") else "Nessun provider disponibile")
        return RoadDnaOnlineResult(places.values.sortedBy { it.distanceMeters }.take(60),
            partial, errors.joinToString("; ").ifBlank {
                if (partial) "Risultati parziali: limiti API o campionamento" else "Ricerca completata"
            })
    }

    fun details(context: Context, xid: String): String {
        require(xid.length in 1..120 && xid.all { it.isLetterOrDigit() || it in "_-." }) {
            "Identificativo POI non valido"
        }
        val key = RoadDnaApiKeys.read(context, "opentripmap")
            ?: throw IllegalStateException("Configura la chiave OpenTripMap")
        val url = OTM + "/xid/" + URLEncoder.encode(xid, "UTF-8") +
            "?apikey=" + URLEncoder.encode(key, "UTF-8")
        val data = JSONObject(fetch(url))
        return (data.optJSONObject("wikipedia_extracts")?.optString("text").orEmpty()
            .ifBlank { data.optJSONObject("info")?.optString("descr").orEmpty() }
            .ifBlank { data.optString("kinds").replace(",", ", ") }
            .ifBlank { "Nessuna descrizione disponibile." }).take(1200)
    }

    fun testKey(provider: String, value: String): Boolean {
        require(value.isNotBlank()) { "Chiave mancante" }
        val url = when (provider) {
            "geoapify" -> GEO + "?categories=tourism.attraction&filter=circle:12.4964,41.9028,1000&limit=1"
            "opentripmap" -> tripRadiusUrl(41.9028, 12.4964, value).replace("limit=25", "limit=1")
            else -> throw IllegalArgumentException("Provider sconosciuto")
        }
        if (provider == "geoapify") JSONObject(fetch(url, value))
        else JSONArray(fetch(url))
        return true
    }
}
