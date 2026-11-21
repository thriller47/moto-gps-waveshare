package com.motogps.android.routing

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Export the selected calculated Valhalla route as a GPX 1.1 track.
 * Never synthesize a line between origin and destination.
 * Use the full Android route geometry, not the shortened BLE window.
 */
internal object RouteGpxExporter {
    private const val MAX_POINTS = 200_000

    private data class Point(val latitude: Double, val longitude: Double)

    fun encode(route: JSONObject, title: String): String {
        val geometry = route.optJSONArray("geometry_gcj02")
            ?: route.optJSONArray("polyline")
            ?: route.optJSONArray("coordinates")
            ?: throw IllegalArgumentException("La rotta Valhalla non contiene geometria esportabile")
        require(geometry.length() in 2..MAX_POINTS) {
            "Geometria GPX assente o troppo grande"
        }

        val points = ArrayList<Point>(geometry.length())
        for (i in 0 until geometry.length()) {
            val obj = geometry.optJSONObject(i)
            val array: JSONArray? = if (obj == null) geometry.optJSONArray(i) else null
            val latitude: Double
            val longitude: Double
            if (obj != null) {
                latitude = obj.optDouble("latitude_deg",
                    obj.optDouble("latitude", obj.optDouble("lat", Double.NaN)))
                longitude = obj.optDouble("longitude_deg",
                    obj.optDouble("longitude", obj.optDouble("lon", Double.NaN)))
            } else if (array != null && array.length() >= 2) {
                longitude = array.optDouble(0, Double.NaN)
                latitude = array.optDouble(1, Double.NaN)
            } else {
                throw IllegalArgumentException("Punto " + (i + 1) + " della rotta non valido")
            }
            require(latitude.isFinite() && latitude in -90.0..90.0 &&
                longitude.isFinite() && longitude in -180.0..180.0) {
                "Coordinate non valide al punto " + (i + 1)
            }
            val point = Point(latitude, longitude)
            if (points.lastOrNull() != point) points.add(point)
        }

        require(points.size >= 2) {
            "La rotta deve contenere almeno due punti distinti"
        }
        val label = title.trim().ifBlank { "Moto Chat GPS" }.take(160)
        val name = xmlEscape(label)
        return buildString(points.size * 53 + 300) {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<gpx version=\"1.1\" creator=\"Moto Chat GPS\" ")
            append("xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            append("  <metadata><name>").append(name).append("</name></metadata>\n")
            append("  <trk><name>").append(name).append("</name><trkseg>\n")
            for (point in points) {
                append("    <trkpt lat=\"")
                append(String.format(Locale.US, "%.6f", point.latitude))
                append("\" lon=\"")
                append(String.format(Locale.US, "%.6f", point.longitude))
                append("\" />\n")
            }
            append("  </trkseg></trk>\n</gpx>\n")
        }
    }

    /** Suggested filename for Android's document picker. */
    fun suggestedFileName(destinationName: String): String {
        val stem = destinationName.trim()
            .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
            .trim('_', '.', '-')
            .take(55)
            .ifEmpty { "percorso" }
        return "MotoChatGPS_$stem.gpx"
    }

    private fun xmlEscape(text: String): String = buildString {
        for (char in text) {
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (char == '\n' || char == '\t' || char == '\r' ||
                    char.code >= 0x20) append(char)
            }
        }
    }
}
