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
import java.net.URI
import java.net.URLDecoder
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.asin

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
    val xid: String? = null,
    // Geoapify Places API identity: use exact place details rather than a nearby guess.
    val geoPlaceId: String? = null,
    // Full classifications are necessary: the first category can be generic.
    val categoryTags: List<String> = emptyList()
)

data class RoadDnaOnlineResult(
    val places: List<RoadDnaOnlinePoi>,
    val partial: Boolean,
    val message: String,
    val categoryId: String = "consigliati"
)


/**
 * Road DNA modes are product-level categories, not a raw provider taxonomy.
 * Explicit selection changes the provider query itself: switching to beaches
 * must fetch beaches, not filter an old castles/museums response.
 */
internal object RoadDnaCategories {
    data class Option(val id: String, val title: String,
        val geo: List<String>, val trip: List<String>)
    val options = listOf(
        Option("consigliati", "Consigliati", listOf("tourism.attraction",
            "tourism.sights", "entertainment.culture", "national_park"), emptyList()),
        Option("spiagge", "Spiagge", listOf("beach"), listOf("beaches")),
        Option("panorami", "Panorami", listOf("tourism.attraction.viewpoint",
            "natural.mountain.peak"), listOf("view_points", "mountain_peaks")),
        Option("castelli", "Castelli e storia", listOf("tourism.sights.castle",
            "tourism.sights.fort", "tourism.sights.ruines",
            "tourism.sights.archaeological_site"),
            listOf("castles", "fortifications", "ruins", "archaeology")),
        Option("cultura", "Arte e cultura", listOf("entertainment.museum",
            "tourism.sights.place_of_worship", "tourism.sights.monastery"),
            listOf("museums", "religion")),
        Option("natura", "Natura", listOf("national_park", "natural",
            "leisure.park.nature_reserve"), listOf("natural")),
        Option("terme", "Terme", listOf("leisure.spa",
            "natural.water.hot_spring"), emptyList()),
        // Geoapify has exact dedicated category families for these services.
        // OpenTripMap is oriented to sightseeing and its broad "foods" /
        // "tourist_facilities" families do not reliably mean restaurant/hotel.
        Option("ristoranti", "Ristoranti", listOf("catering.restaurant"),
            emptyList()),
        Option("hotel", "Hotel", listOf("accommodation.hotel"),
            emptyList()),
        Option("divertimenti", "Divertimenti",
            listOf("entertainment.theme_park", "entertainment.zoo",
                "entertainment.aquarium", "entertainment.activity_park",
                "entertainment.cinema", "entertainment.bowling_alley"),
            emptyList())
    )
    fun get(id: String): Option = options.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("Categoria Road DNA sconosciuta")

    // For categories that do not map unambiguously to OpenTripMap kinds,
    // only use the more precise Geoapify category request.
    fun usesOpenTripMap(id: String): Boolean =
        id == "consigliati" || get(id).trip.isNotEmpty()

    internal fun matches(id: String, poi: RoadDnaOnlinePoi): Boolean {
        if (id == "consigliati") return true
        val tags = (poi.categoryTags + poi.category)
            .map { it.trim().lowercase(java.util.Locale.ROOT) }
        fun has(vararg keys: String): Boolean = keys.any { key ->
            tags.any { it == key || it.startsWith(key + ".") }
        }
        return when (id) {
            "spiagge" -> has("beach", "beaches", "sand_beaches",
                "natural_beaches")
            "panorami" -> has("tourism.attraction.viewpoint",
                "natural.mountain.peak", "view_points", "viewpoints",
                "mountain_peaks")
            "castelli" -> has("tourism.sights.castle", "tourism.sights.fort",
                "tourism.sights.ruines", "tourism.sights.archaeological_site",
                "castles", "fortifications", "ruins", "archaeology",
                "archaeological_sites", "fortresses")
            "cultura" -> has("entertainment.museum",
                "tourism.sights.place_of_worship.cathedral",
                "tourism.sights.place_of_worship.church",
                "tourism.sights.place_of_worship.shrine",
                "tourism.sights.monastery", "museums", "cathedrals",
                "monasteries", "abbeys", "churches", "religious_architecture")
            "natura" -> has("national_park", "natural", "leisure.park.nature_reserve",
                "nature_reserves", "natural_reserves", "lakes", "waterfalls",
                "mountain_peaks", "caves", "natural")
            "terme" -> has("leisure.spa", "natural.water.hot_spring",
                "thermal_baths", "hot_springs", "spas")
            "ristoranti" -> has("catering.restaurant")
            "hotel" -> has("accommodation.hotel")
            "divertimenti" -> has("entertainment.theme_park", "entertainment.zoo",
                "entertainment.aquarium", "entertainment.activity_park",
                "entertainment.cinema", "entertainment.bowling_alley")
            else -> false
        }
    }
}

/** HTTP status only: never include a provider URL, which may contain an API key. */
internal class RoadDnaProviderHttpException(val statusCode: Int) :
    IOException("Provider HTTP $statusCode")

internal object RoadDnaOnline {
    private const val GEO = "https://api.geoapify.com/v2/places"
    private const val GEO_DETAILS = "https://api.geoapify.com/v2/place-details"
    // Use the documented OpenTripMap language endpoint. /it is not documented
    // for the Places API and can reject even a valid key.
    private const val OTM = "https://api.opentripmap.com/0.1/en/places"
    private const val BUFFER_METERS = 2000
    private const val MAX_GEO_CHUNKS = 3
    private const val GEO_VERTICES_PER_CHUNK = 800
    private const val MAX_RESPONSE = 2_000_000
    // Wikimedia requires an identifiable User-Agent.
    private const val WIKIMEDIA_UA =
        "MotoChatGPS/0.2 (https://github.com/thriller47/moto-gps-waveshare)"

    internal fun geoBody(points: List<Pair<Double, Double>>,
                         categoryId: String = "consigliati"): JSONObject {
        val category = RoadDnaCategories.get(categoryId)
        require(points.size in 2..GEO_VERTICES_PER_CHUNK)
        val coordinates = JSONArray()
        points.forEach { (lat, lon) ->
            require(lat.isFinite() && lon.isFinite() &&
                lat in -90.0..90.0 && lon in -180.0..180.0)
            coordinates.put(JSONArray().put(lon).put(lat))
        }
        return JSONObject()
            .put("categories", JSONArray().apply {
                category.geo.forEach { put(it) }
            })
            .put("filter", JSONObject().put("type", "polyline")
                .put("geometry", JSONObject().put("type", "LineString")
                    .put("coordinates", coordinates))
                .put("buffer", BUFFER_METERS))
            .put("lang", "it")
            .put("limit", 100)
            .put("offset", 0)
    }

    /** User-selected circle; distinct from the 2 km route corridor. */
    internal fun zoneBody(lat: Double, lon: Double, radiusKm: Int,
                          categoryId: String): JSONObject {
        require(valid(lat, lon) && radiusKm in 1..20)
        val category = RoadDnaCategories.get(categoryId)
        return JSONObject()
            .put("categories", JSONArray().apply { category.geo.forEach { put(it) } })
            .put("filter", JSONObject().put("type", "circle")
                .put("lat", lat).put("lon", lon).put("radius", radiusKm * 1000))
            .put("bias", JSONObject().put("type", "proximity")
                .put("lat", lat).put("lon", lon))
            .put("lang", "it").put("limit", 200).put("offset", 0)
    }

    /** Search centered on the map, not near the itinerary. Only one explicit
     * Geoapify request; larger areas can be truncated by provider pagination.
     * This is intentionally separate from Road DNA corridor suggestions.
     */
    suspend fun searchZone(context: Context, lat: Double, lon: Double,
                           radiusKm: Int, categoryId: String): RoadDnaOnlineResult {
        val body = zoneBody(lat, lon, radiusKm, categoryId)
        val key = RoadDnaApiKeys.read(context, "geoapify")
            ?: throw IllegalStateException("Configura la chiave Geoapify")
        currentCoroutineContext().ensureActive()
        val data = JSONObject(fetch(GEO, key, body.toString()))
        currentCoroutineContext().ensureActive()
        val received = parseGeo(data).filter { RoadDnaCategories.matches(categoryId, it) }
        val ranked = rankPlaces(received.map { place ->
            place.copy(distanceMeters = RoadDnaAnalysis.distanceMeters(
                place.latitude to place.longitude, lat to lon, lat to lon).toInt())
        })
        val partial = (data.optJSONArray("features")?.length() ?: 0) >= 200 ||
            ranked.size > 60
        val curated = recommendedPlaces(ranked)
        val visible = if (categoryId == "consigliati")
            if (curated.isEmpty()) ranked.take(15) else curated.take(15)
        else ranked.take(60)
        val fallbackNote = if (categoryId == "consigliati" &&
            curated.isEmpty() && ranked.isNotEmpty())
            "Non ci sono consigliati di priorità alta: mostro i luoghi " +
                "disponibili, ordinati per interesse. " else ""
        return RoadDnaOnlineResult(visible, partial, fallbackNote +
            if (partial) "Risultati parziali: massimo 200 POI per richiesta; " +
                "riduci il raggio per approfondire la zona."
            else "Ricerca nella zona completata", categoryId)
    }

    internal fun tripRadiusUrl(lat: Double, lon: Double, apiKey: String,
                               radius: Int = 2000, limit: Int = 25,
                               rate: Int = 2,
                               categoryId: String = "consigliati"): String {
        val kinds = RoadDnaCategories.get(categoryId).trip
        require(valid(lat, lon) && radius in 1..2000 && limit in 1..25 &&
            rate in 1..2)
        return OTM + "/radius?radius=" + radius + "&lon=" + lon + "&lat=" + lat +
            "&rate=" + rate + "&limit=" + limit + "&format=json" +
            (if (kinds.isNotEmpty()) "&kinds=" +
                URLEncoder.encode(kinds.joinToString(","), "UTF-8") else "") +
            "&apikey=" + URLEncoder.encode(apiKey, "UTF-8")
    }

    private fun fetch(url: String, key: String? = null, body: String? = null,
                      userAgent: String? = null): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent)
            if (key != null) conn.setRequestProperty("x-api-key", key)
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            // Never include conn.url, request headers or response bodies in errors.
            val httpStatus = conn.responseCode
            if (httpStatus !in 200..299) throw RoadDnaProviderHttpException(httpStatus)
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

    /** Places documented by Geoapify, not guessed entrances or path endpoints. */
    internal fun accessBody(lat: Double, lon: Double): JSONObject {
        require(valid(lat, lon))
        return JSONObject()
            .put("categories", JSONArray().put("parking").put("tourism.information"))
            .put("filter", JSONObject().put("type", "circle")
                .put("lon", lon).put("lat", lat).put("radius", 2500))
            .put("lang", "it").put("limit", 40)
    }

    internal fun parseAccessPlaces(json: JSONObject, lat: Double, lon: Double):
        List<RoadDnaOnlinePoi> {
        require(valid(lat, lon))
        val features = json.optJSONArray("features") ?: return emptyList()
        val results = ArrayList<RoadDnaOnlinePoi>()
        for (i in 0 until min(features.length(), 40)) {
            val feature = features.optJSONObject(i) ?: continue
            val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates")
                ?: continue
            val pLon = coords.optDouble(0, Double.NaN)
            val pLat = coords.optDouble(1, Double.NaN)
            if (!valid(pLat, pLon)) continue
            val properties = feature.optJSONObject("properties") ?: continue
            val tags = properties.optJSONArray("categories")?.let { a ->
                (0 until a.length()).mapNotNull { n ->
                    a.optString(n).takeIf { it.isNotBlank() }
                }
            } ?: emptyList()
            val parking = tags.any { it == "parking" || it.startsWith("parking.") }
            val information = tags.any {
                it == "tourism.information" || it.startsWith("tourism.information.")
            }
            if (!parking && !information) continue
            val fallback = if (parking) "Parcheggio" else "Informazioni turistiche"
            val name = properties.optString("name").trim().ifBlank { fallback }
            val distance = RoadDnaAnalysis.distanceMeters(pLat to pLon,
                lat to lon, lat to lon).toInt().coerceAtLeast(0)
            results.add(RoadDnaOnlinePoi(
                name.take(160),
                if (parking) "Parcheggio" else "Informazioni turistiche",
                distance, "Geoapify", pLat, pLon,
                geoPlaceId = properties.optString("place_id").trim()
                    .takeIf { it.isNotEmpty() },
                categoryTags = tags
            ))
        }
        return results.sortedWith(compareBy<RoadDnaOnlinePoi> {
            if (it.category == "Parcheggio") 0 else 1
        }.thenBy { it.distanceMeters })
            .distinctBy { it.geoPlaceId ?: it.name + "|" +
                it.latitude.toString() + "|" + it.longitude.toString() }
            .take(20)
    }

    /** Single on-demand online request; authenticated with the existing keystore key. */
    fun findAreaAccesses(context: Context, latitude: Double, longitude: Double):
        List<RoadDnaOnlinePoi> {
        val key = RoadDnaApiKeys.read(context, "geoapify")
            ?: throw IllegalStateException("Configura prima la chiave Geoapify")
        val data = JSONObject(fetch(GEO, key,
            accessBody(latitude, longitude).toString()))
        return parseAccessPlaces(data, latitude, longitude)
    }

    internal fun parseGeo(json: JSONObject): List<RoadDnaOnlinePoi> {
        val features = json.optJSONArray("features") ?: return emptyList()
        val result = ArrayList<RoadDnaOnlinePoi>()
        for (i in 0 until min(features.length(), 200)) {
            val item = features.optJSONObject(i) ?: continue
            val coord = item.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val lon = coord.optDouble(0, Double.NaN)
            val lat = coord.optDouble(1, Double.NaN)
            if (!valid(lat, lon)) continue
            val p = item.optJSONObject("properties") ?: continue
            val name = p.optString("name").trim()
            if (name.isBlank()) continue
            val allCategories = p.optJSONArray("categories")
            val tags = if (allCategories == null) emptyList() else
                (0 until allCategories.length()).mapNotNull { j ->
                    allCategories.optString(j).trim().takeIf { it.isNotEmpty() }
                }
            // Prefer the most specific type over a generic parent category.
            val category = tags.maxByOrNull { it.count { c -> c == '.' } }
                ?: "turismo"
            result.add(RoadDnaOnlinePoi(name.take(160), category,
                0, "Geoapify", lat, lon,
                geoPlaceId = p.optString("place_id").trim().takeIf { it.isNotEmpty() },
                categoryTags = tags))
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
            val kinds = item.optString("kinds").split(',')
                .map { it.trim() }.filter { it.isNotEmpty() }
            result.add(RoadDnaOnlinePoi(name.take(160),
                kinds.firstOrNull().orEmpty().ifBlank { "turismo" }.take(50),
                0, "OpenTripMap", lat, lon, xid, categoryTags = kinds))
        }
        return result
    }

    /**
     * Editorial motorcycle-touring relevance, computed offline from API
     * categories. It is NOT a provider star rating or a verified detour length.
     * Small memorials, plaques, individual statues and fountains stay accessible
     * under "Tutti", not in the recommended shortlist.
     */
    internal fun interestScore(place: RoadDnaOnlinePoi): Int {
        val tags = (place.categoryTags + place.category)
            .map { it.trim().lowercase(java.util.Locale.ROOT) }
            .filter { it.isNotEmpty() }
        fun has(vararg keywords: String): Boolean = keywords.any { key ->
            tags.any { it == key || it.startsWith(key + ".") }
        }

        // Highly specific low-value labels override generic "tourism.sights".
        if (has("tourism.sights.memorial", "tourism.attraction.artwork",
                "tourism.attraction.fountain", "tourism.attraction.clock",
                "tourism.sights.place_of_worship.chapel",
                "memorials", "monuments_and_memorials", "statues",
                "sculptures", "artwork", "fountains", "wayside_crosses",
                "wayside_shrines", "commemorative_plaques",
                "plaques", "small_monuments", "historic.memorial")) return 0

        val priority = when {
            has("tourism.attraction.viewpoint", "view_points", "viewpoints",
                "mountain_peaks", "peaks", "waterfalls", "lakes",
                "geological_formations", "canyons", "natural_springs") -> 100
            has("tourism.sights.castle", "tourism.sights.fort",
                "tourism.sights.ruines", "tourism.sights.archaeological_site",
                "castles", "fortifications", "ruins", "archaeology",
                "archaeological_sites", "fortresses") -> 95
            has("national_park", "national_parks", "nature_reserves",
                "natural_reserves", "caves", "caves_and_grottos",
                "mountain_passes", "scenic_roads") -> 90
            has("museums", "cultural_museums", "tourism.sights.monastery",
                "tourism.sights.place_of_worship.cathedral",
                "cathedrals", "monasteries", "abbeys",
                "tourism.sights.lighthouse", "lighthouses") -> 80
            has("tourism.sights.tower", "tourism.sights.city_gate",
                "tourism.sights.manor", "palaces", "historic_districts",
                "historic_settlements", "gardens_and_parks") -> 70
            else -> 0
        }
        if (priority > 0) return priority +
            (if (place.source == "OpenTripMap") 5 else 0)
        // OpenTripMap currently queries named POIs at rate=2: they are useful
        // fallback suggestions, but generic Geoapify results are not.
        return if (place.source == "OpenTripMap") 65 else 10
    }

    internal fun recommendedPlaces(places: List<RoadDnaOnlinePoi>): List<RoadDnaOnlinePoi> =
        places.filter { interestScore(it) >= 65 }

    internal fun rankPlaces(places: List<RoadDnaOnlinePoi>): List<RoadDnaOnlinePoi> =
        places.sortedWith(compareByDescending<RoadDnaOnlinePoi> { interestScore(it) }
            .thenBy { it.distanceMeters }
            .thenBy { it.name })

    private fun valid(lat: Double, lon: Double) = lat.isFinite() && lon.isFinite() &&
        lat in -90.0..90.0 && lon in -180.0..180.0

    /** Five distance-distributed samples, not five vertex indices (which can
     * cluster on dense switchback geometry). Full LineString is retained for Geoapify.
     */
    internal fun tripSampleIndices(points: List<Pair<Double, Double>>,
                                   limit: Int = 5): List<Int> {
        if (points.size <= limit) return points.indices.toList()
        if (limit < 2) return listOf(0)
        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dLat = Math.toRadians(b.first - a.first)
            val dLon = Math.toRadians(b.second - a.second)
            val x = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(a.first)) * cos(Math.toRadians(b.first)) *
                sin(dLon / 2) * sin(dLon / 2)
            val meters = 12742000.0 * asin(sqrt(x.coerceIn(0.0, 1.0)))
            cumulative[i] = cumulative[i - 1] + meters
        }
        val total = cumulative.last()
        if (total <= 1.0) return listOf(0)
        var cursor = 0
        return (0 until limit).map { k ->
            val target = total * k / (limit - 1)
            while (cursor < points.lastIndex && cumulative[cursor] < target) cursor++
            if (cursor > 0 && cumulative[cursor] - target > target - cumulative[cursor - 1])
                cursor - 1 else cursor
        }.distinct()
    }

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

    suspend fun search(context: Context, route: List<Pair<Double, Double>>,
                       categoryId: String = "consigliati"): RoadDnaOnlineResult {
        RoadDnaCategories.get(categoryId)
        require(route.size >= 2) { "Geometria percorso mancante" }
        val geoKey = RoadDnaApiKeys.read(context, "geoapify")
        val tripKey = RoadDnaApiKeys.read(context, "opentripmap")
        if (geoKey.isNullOrBlank() && tripKey.isNullOrBlank())
            throw IllegalStateException("Configura una chiave Geoapify o OpenTripMap nelle impostazioni")
        if (!RoadDnaCategories.usesOpenTripMap(categoryId) && geoKey.isNullOrBlank())
            throw IllegalStateException("Questa categoria richiede una chiave Geoapify")
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
                    val body = geoBody(chunk, categoryId).toString()
                    val data = JSONObject(fetch(GEO, geoKey, body))
                    val received = parseGeo(data).filter {
                        RoadDnaCategories.matches(categoryId, it)
                    }
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
        if (!tripKey.isNullOrBlank() && RoadDnaCategories.usesOpenTripMap(categoryId)) {
            // A few radius queries, not a continuous scan of the user's itinerary.
            // Sparse sampling means OpenTripMap coverage is always labelled partial.
            val indices = tripSampleIndices(route, 5)
            partial = true
            for (i in indices) {
                try {
                    currentCoroutineContext().ensureActive()
                    val (lat, lon) = route[i]
                    val received = parseTrip(JSONArray(fetch(tripRadiusUrl(lat, lon, tripKey,
                        rate = if (categoryId == "consigliati") 2 else 1,
                        categoryId = categoryId)))).filter {
                        RoadDnaCategories.matches(categoryId, it)
                    }
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
        val ranked = rankPlaces(places.values.toList())
        if (ranked.size > 60) partial = true
        return RoadDnaOnlineResult(ranked.take(60),
            partial, errors.joinToString("; ").ifBlank {
                if (partial) "Risultati parziali: limiti API o campionamento" else "Ricerca completata"
            }, categoryId)
    }

    /**
     * For Geoapify places use their own exact place_id lookup; an OpenTripMap
     * object at a similar location is not guaranteed to represent the same POI.
     * One detail request is made only after the user selects a specific place.
     */
    fun detailsForPoi(context: Context, place: RoadDnaOnlinePoi): String {
        if (place.source == "OpenTripMap") {
            return details(context, place.xid
                ?: throw IllegalArgumentException("Identificativo OpenTripMap mancante"))
        }
        require(place.source == "Geoapify") { "Origine del luogo non supportata" }
        val id = place.geoPlaceId
            ?: return "Geoapify non ha fornito un identificativo per la scheda di questo luogo."
        require(id.length in 1..256 && id.all { it.isLetterOrDigit() || it in "-_" }) {
            "Identificativo Geoapify non valido"
        }
        val key = RoadDnaApiKeys.read(context, "geoapify")
            ?: throw IllegalStateException("Chiave Geoapify non configurata")
        val url = GEO_DETAILS + "?id=" + URLEncoder.encode(id, "UTF-8")
        // Geoapify accepts x-api-key; do not insert the key in the URL.
        val data = JSONObject(fetch(url, key))
        val props = geoDetailProperties(data)
        // Only for this selected POI and only when Geoapify has no text.
        // The place is linked by the provider, never matched by its name.
        val wikiExtract = if (props != null && geoDescription(props).isBlank()) {
            wikipediaExtractForLinkedPlace(props)
        } else null
        return parseGeoDetails(data, wikiExtract?.first, wikiExtract?.second,
            wikiExtract?.third ?: "Wikipedia")
    }

    private fun geoDetailProperties(data: JSONObject): JSONObject? {
        val features = data.optJSONArray("features") ?: return null
        return (0 until features.length()).asSequence()
            .mapNotNull { features.optJSONObject(it)?.optJSONObject("properties") }
            .firstOrNull { it.optString("feature_type") == "details" }
    }

    private fun geoDescription(props: JSONObject): String {
        val international = props.optJSONObject("description_international")
        return sequenceOf(
            props.optString("description"),
            international?.optString("it"),
            international?.optString("en"),
            props.optJSONObject("heritage")?.optString("description")
        ).map { it.orEmpty().trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    internal fun parseGeoDetails(data: JSONObject, linkedText: String? = null,
                                 linkedPageUrl: String? = null,
                                 linkedSource: String = "Wikipedia"): String {
        val props = geoDetailProperties(data)
            ?: return "Geoapify non ha restituito una scheda per questo luogo."
        val lines = ArrayList<String>()
        val description = geoDescription(props)
        lines.add(when {
            description.isNotEmpty() -> description.take(1200)
            !linkedText.isNullOrBlank() ->
                (if (linkedSource == "Wikidata") "Da Wikidata (descrizione breve): "
                    else "Da Wikipedia (estratto): ") + linkedText.take(1200)
            else -> "Descrizione turistica non disponibile per questo luogo."
        })
        props.optString("formatted").trim().takeIf { it.isNotEmpty() }
            ?.let { lines.add("Indirizzo: " + it.take(300)) }
        props.optString("website").trim().takeIf {
            it.startsWith("https://") || it.startsWith("http://")
        }?.let { lines.add("Sito web: " + it.take(500)) }
        if (!linkedText.isNullOrBlank() && linkedPageUrl != null) {
            val attribution = if (linkedSource == "Wikidata")
                "Wikidata (CC0)" else "Wikipedia (CC BY-SA 4.0)"
            lines.add("Fonte: " + attribution + " · " + linkedPageUrl)
        }
        val wiki = props.optJSONObject("wiki_and_media")
        wiki?.optString("wikipedia")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { lines.add("Riferimento Wikipedia: " + it.take(250)) }
        wiki?.optString("wikidata")?.trim()
            ?.takeIf { Regex("^Q[1-9][0-9]{0,14}$").matches(it) }
            ?.let { lines.add("Scheda Wikidata: https://www.wikidata.org/wiki/" + it) }
        return lines.joinToString("\n\n")
    }

    /**
     * Only linked OSM references may become Wikipedia URLs.
     * Rebuild the URL on a whitelisted host; never use an arbitrary source URL.
     */
    internal fun wikipediaSummaryUrl(reference: String): String? {
        val value = reference.trim()
        if (value.isEmpty() || value.length > 320) return null
        val (lang, title) = if (value.startsWith("https://")) {
            val uri = try { URI(value) } catch (_: Exception) { return null }
            val host = uri.host?.lowercase() ?: return null
            val l = host.substringBefore(".wikipedia.org")
            if (host != l + ".wikipedia.org" || uri.rawQuery != null ||
                uri.rawFragment != null || uri.userInfo != null ||
                !uri.rawPath.startsWith("/wiki/")) return null
            val rawTitle = uri.rawPath.removePrefix("/wiki/")
            l to try {
                URLDecoder.decode(rawTitle.replace("+", "%2B"), "UTF-8")
            } catch (_: Exception) { return null }
        } else {
            val sep = value.indexOf(':')
            if (sep <= 0) return null
            value.substring(0, sep) to value.substring(sep + 1)
        }
        if (lang !in setOf("it", "en", "fr", "de", "es")) return null
        val article = title.trim().replace('_', ' ')
        if (article.isEmpty() || article.length > 200 ||
            article.any { it.isISOControl() || it in "#?[]{}|<>" } ||
            article.startsWith("/") || article.startsWith(".")) return null
        val encoded = URLEncoder.encode(article.replace(' ', '_'), "UTF-8")
            .replace("+", "%20")
        return "https://" + lang + ".wikipedia.org/api/rest_v1/page/summary/" + encoded
    }

    /** Choose an exact Italian Wikipedia sitelink, then English; no name search. */
    internal fun wikidataWikipediaReference(data: JSONObject, itemId: String): String? {
        val sitelinks = data.optJSONObject("entities")
            ?.optJSONObject(itemId)?.optJSONObject("sitelinks") ?: return null
        for (language in listOf("it", "en")) {
            val title = sitelinks.optJSONObject(language + "wiki")
                ?.optString("title").orEmpty().trim()
            if (title.isNotEmpty()) return language + ":" + title
        }
        return null
    }

    /** Get a short description from an already-linked Wikidata item. */
    internal fun wikidataShortDescription(data: JSONObject, itemId: String): String? {
        val descriptions = data.optJSONObject("entities")
            ?.optJSONObject(itemId)?.optJSONObject("descriptions") ?: return null
        for (language in listOf("it", "en")) {
            val value = descriptions.optJSONObject(language)
                ?.optString("value").orEmpty().trim()
            if (value.isNotEmpty()) return value.take(500)
        }
        return null
    }

    private fun linkedWikipediaSummary(url: String): Triple<String, String, String>? {
        return try {
            val summary = JSONObject(fetch(url, userAgent = WIKIMEDIA_UA))
            if (summary.optString("type") == "disambiguation") return null
            val excerpt = summary.optString("extract").trim()
                .takeIf { it.isNotEmpty() }?.take(1200) ?: return null
            val pageUrl = url.replace("/api/rest_v1/page/summary/", "/wiki/")
            Triple(excerpt, pageUrl, "Wikipedia")
        } catch (_: Exception) {
            null
        }
    }

    /**
     * A Wikidata QID identifies a real entity, but it may have no Wikipedia
     * sitelink. In that case use the item's short description, not the bare QID.
     * No name matching and no untrusted hosts.
     */
    private fun wikipediaExtractForLinkedPlace(props: JSONObject):
        Triple<String, String, String>? {
        val wiki = props.optJSONObject("wiki_and_media") ?: return null
        wikipediaSummaryUrl(wiki.optString("wikipedia"))?.let { url ->
            linkedWikipediaSummary(url)?.let { return it }
        }
        val qid = wiki.optString("wikidata").trim()
        if (!Regex("^Q[1-9][0-9]{0,14}$").matches(qid)) return null
        return try {
            val url = "https://www.wikidata.org/w/api.php?" +
                "action=wbgetentities&ids=" + qid +
                "&props=sitelinks%7Cdescriptions&languages=it%7Cen" +
                "&sitefilter=itwiki%7Cenwiki&format=json"
            val entity = JSONObject(fetch(url, userAgent = WIKIMEDIA_UA))
            val reference = wikidataWikipediaReference(entity, qid)
            if (reference != null) {
                wikipediaSummaryUrl(reference)?.let { article ->
                    linkedWikipediaSummary(article)?.let { return it }
                }
            }
            val short = wikidataShortDescription(entity, qid) ?: return null
            Triple(short, "https://www.wikidata.org/wiki/" + qid, "Wikidata")
        } catch (_: Exception) {
            // Keep Geoapify's address intact if Wikidata is unavailable.
            null
        }
    }

    internal fun parseTripDetails(data: JSONObject): String {
        val description = data.optJSONObject("wikipedia_extracts")
            ?.optString("text").orEmpty().trim().ifBlank {
                data.optJSONObject("info")?.optString("descr").orEmpty().trim()
            }
        val lines = ArrayList<String>()
        lines.add(if (description.isNotEmpty()) description.take(1200)
            else "Descrizione non disponibile in OpenTripMap per questo luogo.")
        data.optString("url").trim().takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?.let { lines.add("Sito web: " + it.take(500)) }
        data.optString("wikipedia").trim().takeIf {
            it.startsWith("https://") || it.startsWith("http://")
        }?.let { lines.add("Wikipedia: " + it.take(500)) }
        data.optString("otm").trim().takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?.let { lines.add("OpenTripMap: " + it.take(500)) }
        return lines.joinToString("\n\n")
    }

    fun details(context: Context, xid: String): String {
        require(xid.length in 1..120 && xid.all { it.isLetterOrDigit() || it in "_-." }) {
            "Identificativo POI non valido"
        }
        val key = RoadDnaApiKeys.read(context, "opentripmap")
            ?: throw IllegalStateException("Configura la chiave OpenTripMap")
        val url = OTM + "/xid/" + URLEncoder.encode(xid, "UTF-8") +
            "?apikey=" + URLEncoder.encode(key, "UTF-8")
        return parseTripDetails(JSONObject(fetch(url)))
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
