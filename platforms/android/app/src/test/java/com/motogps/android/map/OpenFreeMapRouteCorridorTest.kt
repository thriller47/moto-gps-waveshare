package com.motogps.android.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import android.content.Context
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OpenFreeMapRouteCorridorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun pack(name: String, manifest: String, coverage: String?, children: Set<String> = emptySet()) {
        val dir = File(tmp.root, "maps/.osm_packages")
        dir.mkdirs()
        ZipOutputStream(File(dir, name).outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(
                if (name.contains("v3")) "motomap_v3.json" else "motomap_v4.json"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
            if (coverage != null) {
                zip.putNextEntry(ZipEntry("coverage_z14.txt"))
                zip.write(coverage.toByteArray())
                zip.closeEntry()
            }
            for (child in children) {
                zip.putNextEntry(ZipEntry(child))
                zip.write("test".toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun missing(): List<OpenFreeMapRouteCorridor.Tile> {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(tmp.root)
        return OpenFreeMapRouteCorridor.missing(context, listOf(tile))
    }

    @Test fun v4CertifiedEmptyCellNeedsNoDownload() {
        pack("italy-v4.motomap.zip",
            """{"format":"motomap","version":4,"zoom":15,"topology":"osm-node-ids-base36","junction":"osm-junction-way-v4","coverage":"poly-full-z14"}""",
            "8192,5590\n")
        assertTrue(missing().isEmpty())
    }

    @Test fun legacyV3CannotCertifyOrClaimOnlineRoadCoverage() {
        pack("italy-v3.motomap.zip",
            """{"format":"motomap","version":3,"zoom":15,"coverage":"poly-full-z14"}""",
            "8192,5590\n", children)
        assertTrue(missing() == listOf(tile))
    }

    @Test fun v4WithoutCertifiedPolygonAndMissingChildrenNeedsDownload() {
        pack("italy-v4.motomap.zip",
            """{"format":"motomap","version":4,"zoom":15,"topology":"osm-node-ids-base36","junction":"osm-junction-way-v4","coverage":"unknown"}""",
            null, children - children.first())
        assertTrue(missing() == listOf(tile))
    }

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
