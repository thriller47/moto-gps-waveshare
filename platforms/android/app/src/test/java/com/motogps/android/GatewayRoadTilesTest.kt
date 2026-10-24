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
    fun routeCorridorSamplesIntermediateTilesWithoutDuplicatingThem() {
        val route = listOf(
            44.4424 to 7.8913,
            44.4550 to 7.9000,
            44.4670 to 7.9100
        )
        val tiles = GatewayRoadTiles.corridor(route)
        assertTrue(tiles.contains(GatewayRoadTiles.tileFor(44.4424, 7.8913)))
        assertTrue(tiles.contains(GatewayRoadTiles.tileFor(44.4670, 7.9100)))
        assertEquals(tiles.size, tiles.distinct().size)
        assertTrue(tiles.size > 9)
    }
}
