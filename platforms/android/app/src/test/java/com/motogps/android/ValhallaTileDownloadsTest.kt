package com.motogps.android

import com.motogps.android.routing.ValhallaTileDownloads
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ValhallaTileDownloadsTest {
    private fun asset(name: String, digest: String) = JSONObject()
        .put("name", name)
        .put("size", 419_000_000L)
        .put("digest", digest)
        .put("browser_download_url",
            "https://github.com/librescoot/valhalla-tiles/releases/download/latest/" + name)

    @Test fun filtersCatalogToVerifiedTarArchives() {
        val sha = "sha256:" + "a".repeat(64)
        val catalog = JSONObject().put("assets", JSONArray()
            .put(asset("valhalla_tiles_italy-nord-ovest.tar", sha))
            .put(asset("valhalla_tiles_italy-nord-ovest.tar.zst", sha))
            .put(asset("valhalla_tiles_bremen.tar", ""))
            .put(asset("valhalla_tiles_hamburg.tar", sha)
                .put("browser_download_url", "https://example.com/fake.tar"))
        )
        val regions = ValhallaTileDownloads.parseCatalog(catalog)
        assertEquals(1, regions.size)
        assertEquals("italy-nord-ovest", regions[0].id)
        assertTrue(regions[0].label.contains("Lombardia"))
    }

    @Test fun rejectsEmptyAndNonRoutingArchives() {
        val file = File.createTempFile("valhalla-invalid", ".tar")
        try {
            file.writeBytes(ByteArray(1024))
            assertFalse(ValhallaTileDownloads.validateTar(file))
            val bytes = ByteArray(1024)
            "ustar".toByteArray().copyInto(bytes, 257)
            "no-graph.txt".toByteArray().copyInto(bytes, 0)
            "00000000000".toByteArray().copyInto(bytes, 124)
            file.writeBytes(bytes)
            assertFalse(ValhallaTileDownloads.validateTar(file))
        } finally {
            file.delete()
        }
    }

    @Test fun acceptsPlainTarContainingGraphTile() {
        val file = File.createTempFile("valhalla-good", ".tar")
        try {
            val bytes = ByteArray(1536)
            "2/000/001.gph".toByteArray().copyInto(bytes, 0)
            "00000000000".toByteArray().copyInto(bytes, 124)
            "ustar".toByteArray().copyInto(bytes, 257)
            file.writeBytes(bytes)
            assertTrue(ValhallaTileDownloads.validateTar(file))
        } finally {
            file.delete()
        }
    }
}
