package com.motogps.android

import com.motogps.android.routing.RouteGpxExporter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import java.io.StringReader

class RouteGpxExporterTest {
    private fun geometry(vararg pairs: Pair<Double, Double>): JSONArray = JSONArray().apply {
        for ((lat, lon) in pairs) {
            put(JSONObject().put("latitude_deg", lat).put("longitude_deg", lon))
        }
    }

    private fun parse(gpx: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(InputSource(StringReader(gpx)))

    @Test fun exportsWholeTrackWithoutShortening() {
        val points = (0..249).map { (45.0 + it * 0.0001) to (7.0 + it * 0.0001) }
        val route = JSONObject().put("geometry_gcj02",
            geometry(*points.toTypedArray()))
        val document = parse(RouteGpxExporter.encode(route, "Giro & natura"))
        val tracks = document.getElementsByTagNameNS(
            "http://www.topografix.com/GPX/1/1", "trkpt")
        assertEquals(250, tracks.length)
        assertEquals("45.000000", tracks.item(0).attributes.getNamedItem("lat").nodeValue)
        assertEquals("7.024900", tracks.item(249).attributes.getNamedItem("lon").nodeValue)
        val names = document.getElementsByTagNameNS(
            "http://www.topografix.com/GPX/1/1", "name")
        assertEquals("Giro & natura", names.item(0).textContent)
    }

    @Test fun handlesGeoJsonOrderAndDropsOnlyConsecutiveDuplicates() {
        val coords = JSONArray()
            .put(JSONArray().put(7.0).put(45.0))
            .put(JSONArray().put(7.0).put(45.0))
            .put(JSONArray().put(7.1).put(45.1))
            .put(JSONArray().put(7.0).put(45.0))
        val document = parse(RouteGpxExporter.encode(
            JSONObject().put("coordinates", coords), "Anello"))
        val tracks = document.getElementsByTagNameNS(
            "http://www.topografix.com/GPX/1/1", "trkpt")
        assertEquals(3, tracks.length) // Closing point of a loop must be kept.
        assertEquals("7.100000", tracks.item(1).attributes.getNamedItem("lon").nodeValue)
    }

    @Test fun xmlEscapesNameAndSanitizesSuggestedFilename() {
        val document = parse(RouteGpxExporter.encode(
            JSONObject().put("geometry_gcj02",
                geometry(45.0 to 7.0, 45.1 to 7.1)),
            "Castelli < lago \"d'oro\" & montagna"))
        assertEquals("Castelli < lago \"d'oro\" & montagna",
            document.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1",
                "name").item(0).textContent)
        val filename = RouteGpxExporter.suggestedFileName("../Giro: Lago/Maggiore?")
        assertTrue(filename.endsWith(".gpx"))
        assertFalse(filename.contains('/'))
        assertFalse(filename.contains(".."))
    }

    @Test fun missingGeometryIsNotReplacedWithStraightLine() {
        val route = JSONObject().put("route_id", "test-123")
        try {
            RouteGpxExporter.encode(route, "Percorso")
            fail("Route without geometry must not be exported")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("geometria"))
        }
    }

    @Test fun rejectsInvalidCoordinatesAndDegenerateTrack() {
        val invalid = JSONObject().put("geometry_gcj02",
            geometry(45.0 to 7.0, 95.0 to 7.1))
        try {
            RouteGpxExporter.encode(invalid, "Errato")
            fail("Expected rejection of invalid latitude")
        } catch (_: IllegalArgumentException) { }
        val single = JSONObject().put("geometry_gcj02",
            geometry(45.0 to 7.0, 45.0 to 7.0))
        try {
            RouteGpxExporter.encode(single, "Errato")
            fail("Expected rejection of single-point GPX")
        } catch (_: IllegalArgumentException) { }
    }
}
