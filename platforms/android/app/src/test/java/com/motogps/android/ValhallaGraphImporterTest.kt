package com.motogps.android

import com.github.luben.zstd.ZstdOutputStream
import com.motogps.android.routing.ValhallaGraphImporter
import com.motogps.android.routing.ValhallaTarValidator
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class ValhallaGraphImporterTest {
    private fun validTar(): ByteArray {
        val tar = ByteArray(1536)
        "2/000/001.gph".toByteArray().copyInto(tar, 0)
        "00000000000".toByteArray().copyInto(tar, 124)
        "ustar".toByteArray().copyInto(tar, 257)
        return tar
    }

    @Test fun supportsCompressedAndPlainArchives() {
        assertTrue(ValhallaGraphImporter.isSupportedName("Italy.tar.zst"))
        assertTrue(ValhallaGraphImporter.isSupportedName("valhalla_tiles.tar"))
        assertFalse(ValhallaGraphImporter.isSupportedName("Italy.zip"))
    }

    @Test fun zstdStreamExpandsToValidValhallaTar() {
        val expected = validTar()
        val encoded = ByteArrayOutputStream().also { stream ->
            ZstdOutputStream(stream).use { it.write(expected) }
        }.toByteArray()
        val expanded = ByteArrayOutputStream()
        var recorded = 0L
        ValhallaGraphImporter.copyTar(
            ByteArrayInputStream(encoded), expanded, true, { recorded = it }
        )
        assertEquals(expected.size.toLong(), recorded)
        assertArrayEquals(expected, expanded.toByteArray())
        val result = File.createTempFile("valhalla-zstd", ".tar")
        try {
            result.writeBytes(expanded.toByteArray())
            assertTrue(ValhallaTarValidator.validateTar(result))
        } finally {
            result.delete()
        }
    }

    @Test fun cancellationStopsBeforeWritingAnything() {
        val output = ByteArrayOutputStream()
        try {
            ValhallaGraphImporter.copyTar(
                ByteArrayInputStream(validTar()), output, false,
                cancelled = { true }
            )
            fail("Expected cancellation")
        } catch (_: IOException) {
            assertEquals(0, output.size())
        }
    }
}
