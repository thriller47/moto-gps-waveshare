package com.motogps.android.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenFreeMapRouteCorridorTest {
    private val tile = OpenFreeMapRouteCorridor.Tile(8192, 5590)
    private val children = setOf(
        "tile_15_16384_11180.motomap",
        "tile_15_16384_11181.motomap",
        "tile_15_16385_11180.motomap",
        "tile_15_16385_11181.motomap"
    )

    @Test fun completeImportedCoverageSkipsDownload() {
        assertTrue(OpenFreeMapRouteCorridor.coveredByMotoMap(tile, children))
    }

    @Test fun missingOneChildKeepsDownloadFallback() {
        for (entry in children) {
            assertFalse(OpenFreeMapRouteCorridor.coveredByMotoMap(tile, children - entry))
        }
    }

    @Test fun unrelatedTilesDoNotCountAsCoverage() {
        assertFalse(OpenFreeMapRouteCorridor.coveredByMotoMap(
            tile, setOf("tile_15_16386_11180.motomap")
        ))
        assertFalse(OpenFreeMapRouteCorridor.coveredByMotoMap(tile, emptySet()))
    }
}
