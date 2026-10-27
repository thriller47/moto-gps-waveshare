package com.motogps.android

import com.motogps.android.map.GatewayRoadTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringWriter
import java.io.BufferedWriter

class GatewayRoadTilesTest {
    @Test
    fun mercatorTileIndicesAreStableAndInBounds() {
        assertEquals(GatewayRoadTiles.Tile(16384, 16384), GatewayRoadTiles.tileFor(0.0, 0.0))
        assertEquals(GatewayRoadTiles.tileFor(44.4424, 7.8913),
            GatewayRoadTiles.tileFor(44.4425, 7.89135))
    }

    @Test
    fun nearbyWindowPrioritizesCurrentTile() {
        val center = GatewayRoadTiles.tileFor(44.4424, 7.8913)
        val tiles = GatewayRoadTiles.around(44.4424, 7.8913, 1)
        assertEquals(9, tiles.size)
        assertEquals(center, tiles.first())
        assertEquals(9, tiles.distinct().size)
    }

    @Test
    fun largerOfflineAreaUses81DistinctTilesWithCurrentTileFirst() {
        val center = GatewayRoadTiles.tileFor(44.4424, 7.8913)
        val tiles = GatewayRoadTiles.around(44.4424, 7.8913, 4)
        assertEquals(81, tiles.size)
        assertEquals(center, tiles.first())
        assertEquals(81, tiles.distinct().size)
    }

    @Test
    fun overpassQueryTargetsSingleTileNotRender() {
        val q = GatewayRoadTiles.query(GatewayRoadTiles.tileFor(45.62, 8.85))
        assertTrue(q.contains("way[highway"))
        assertTrue(q.contains("out geom"))
        assertTrue(q.contains("[timeout:12]"))
        assertTrue(!q.contains("onrender"))
    }

    @Test
    fun overpassGeometryProducesMotomapRoadSegments() {
        val way = JSONObject().put("type", "way")
            .put("tags", JSONObject().put("highway", "residential").put("maxspeed", "30"))
            .put("geometry", JSONArray()
                .put(JSONObject().put("lat", 45.62).put("lon", 8.85))
                .put(JSONObject().put("lat", 45.6201).put("lon", 8.8501)))
        val response = JSONObject().put("elements", JSONArray().put(way))
        val output = StringWriter()
        val writer = BufferedWriter(output)
        val count = GatewayRoadTiles.writeOsmWays(response, writer)
        writer.flush()
        assertEquals(1, count)
        assertTrue(output.toString().startsWith("3,30|"))
        assertTrue(output.toString().contains("45620000,8850000;45620100,8850100"))
    }

}
