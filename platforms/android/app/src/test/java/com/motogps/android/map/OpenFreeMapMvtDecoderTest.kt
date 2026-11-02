package com.motogps.android.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedWriter
import java.io.StringWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

class OpenFreeMapMvtDecoderTest {
    private fun integer(n: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = n
        do {
            val b = (v and 127).toInt()
            v = v ushr 7
            out.write(if (v != 0L) b or 128 else b)
        } while (v != 0L)
        return out.toByteArray()
    }
    private fun tagged(field: Int, wire: Int, data: ByteArray): ByteArray =
        integer(((field shl 3) or wire).toLong()) +
            (if (wire == 2) integer(data.size.toLong()) else byteArrayOf()) + data
    private fun str(field: Int, value: String) = tagged(field, 2, value.toByteArray())
    private fun varField(field: Int, value: Long) = tagged(field, 0, integer(value))
    private fun pack(vararg words: Long) = words.fold(ByteArray(0)) { b, n -> b + integer(n) }
    private fun feature(classIndex: Long): ByteArray {
        val tags = pack(0, classIndex)
        val shape = pack(9, 2000, 2000, 18, 200, 0, 0, 200)
        return tagged(2, 2, tags) + varField(3, 2) + tagged(4, 2, shape)
    }
    private fun tile(): ByteArray {
        val layer = str(1, "transportation") +
            tagged(2, 2, feature(0)) +
            tagged(2, 2, feature(1)) +
            tagged(2, 2, feature(2)) +
            str(3, "class") +
            tagged(4, 2, str(1, "primary")) +
            tagged(4, 2, str(1, "minor")) +
            tagged(4, 2, str(1, "path")) +
            varField(5, 4096) + varField(15, 2)
        return tagged(3, 2, layer)
    }
    private fun decode(bytes: ByteArray): Pair<OpenFreeMapMvtDecoder.Stats, String> {
        val text = StringWriter()
        val writer = BufferedWriter(text)
        val stats = OpenFreeMapMvtDecoder.writeTile(15, 17200, 11700, bytes, writer)
        writer.flush()
        return stats to text.toString()
    }
    @Test fun convertsRoadsToMotomapAndExcludesPaths() {
        val (stats, text) = decode(tile())
        assertEquals(3, stats.features)
        assertEquals(2, stats.roads)
        assertEquals(2, stats.parts)
        assertEquals(6, stats.points)
        assertTrue(text.lines().any { it.startsWith("1,90|") })
        assertTrue(text.lines().any { it.startsWith("3,50|") })
        assertFalse(text.contains("4,20|"))
        assertTrue(text.contains(";"))
    }
    @Test fun supportsGzipCompressedMvt() {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(tile()) }
        val (stats, text) = decode(output.toByteArray())
        assertEquals(2, stats.parts)
        assertTrue(text.startsWith("1,90|"))
    }
    @Test fun rejectsTruncatedAndOversizedInput() {
        val truncated = byteArrayOf(26, 0x80.toByte(), 0x08)
        try { decode(truncated); throw AssertionError("truncated tile accepted") }
        catch (_: IOException) { }
        val tooLarge = ByteArray(OpenFreeMapMvtDecoder.MAX_TILE_BYTES + 1)
        try { decode(tooLarge); throw AssertionError("oversized tile accepted") }
        catch (_: IOException) { }
    }
    @Test fun skipsNonTransportationLayerEvenWhenItHasFeatures() {
        val unrelatedLayer = str(1, "waterway") +
            tagged(2, 2, feature(0)) + str(3, "class") +
            tagged(4, 2, str(1, "primary")) +
            varField(5, 4096) + varField(15, 2)
        val (stats, text) = decode(tagged(3, 2, unrelatedLayer))
        assertEquals(0, stats.parts)
        assertTrue(text.isEmpty())
    }
    @Test fun emptyTileDoesNotInventRoads() {
        val (stats, text) = decode(byteArrayOf())
        assertEquals(0, stats.parts)
        assertTrue(text.isEmpty())
    }
}
