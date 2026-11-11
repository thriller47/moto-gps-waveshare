package com.motogps.android

import com.motogps.android.map.GatewayRoadTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun tilesAtMercatorBoundariesStayInRange() {
        assertEquals(GatewayRoadTiles.Tile(0, 0),
            GatewayRoadTiles.tileFor(85.0, -180.0))
        assertEquals(GatewayRoadTiles.Tile(32767, 32767),
            GatewayRoadTiles.tileFor(-85.0, 180.0))
        assertEquals(4, GatewayRoadTiles.around(85.0, -180.0, 1).size)
    }
}
