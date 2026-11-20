package com.motogps.android

import android.content.Context
import com.motogps.android.map.OpenFreeMapRouteCorridor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Offline Italy + route outside its coverage: never treat an installed pack
 * as proof that the entire corridor is available. */
class OpenFreeMapMixedCoverageTest {
    @get:Rule val temporaryFiles = TemporaryFolder()

    private fun route(): JSONObject {
        val geometry = JSONArray()
            .put(JSONObject().put("latitude_deg", 45.0).put("longitude_deg", 7.65))
            .put(JSONObject().put("latitude_deg", 45.0).put("longitude_deg", 6.90))
        return JSONObject().put("geometry_gcj02", geometry)
    }

    @Test fun italianPackCoversOnlyItsOwnTilesAndLeavesFrenchCorridorDownloadable() {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporaryFiles.root)
        val tiles = OpenFreeMapRouteCorridor.plan(route())
        assertTrue(tiles.size > 8)
        val offline = tiles.take(tiles.size / 2).toSet()
        val packages = File(temporaryFiles.root, "maps/.osm_packages")
        assertTrue(packages.mkdirs())
        val pack = File(packages, "italy-test.motomap.zip")
        ZipOutputStream(pack.outputStream()).use { zip ->
            fun entry(name: String, data: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("motomap_v4.json",
                """{"format":"motomap","version":4,"zoom":15,"topology":"osm-node-ids-base36","junction":"osm-junction-way-v4","coverage":"poly-full-z14"}""")
            entry("coverage_z14.txt",
                offline.joinToString("\n") { "${it.x},${it.y}" } + "\n")
        }
        val missing = OpenFreeMapRouteCorridor.missing(context, tiles)
        assertEquals(tiles.size - offline.size, missing.size)
        assertTrue(missing.none { it in offline })
        assertTrue(missing.all { it in tiles })

        // Previously cached French tile must also be subtracted, without
        // requiring a second MotoMap and without redownloading Italian tiles.
        val downloaded = missing.first()
        val cache = File(temporaryFiles.root, "maps/${downloaded.filename}")
        cache.writeText("3,50|45000000,6900000,45000100,6900100|45000000,6900000;45000100,6900100\n")
        val remaining = OpenFreeMapRouteCorridor.missing(context, tiles)
        assertEquals(missing.size - 1, remaining.size)
        assertFalse(downloaded in remaining)
    }

    @Test fun allCachedTilesSuppressDownloadPromptEvenWithoutMotoMap() {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporaryFiles.root)
        val tiles = OpenFreeMapRouteCorridor.plan(route()).take(3)
        assertTrue(tiles.isNotEmpty())
        val dir = File(temporaryFiles.root, "maps")
        assertTrue(dir.mkdirs())
        for (tile in tiles) File(dir, tile.filename).writeText("roads\n")
        assertTrue(OpenFreeMapRouteCorridor.missing(context, tiles).isEmpty())
    }
}
