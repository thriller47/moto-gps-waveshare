package com.motogps.android.map

import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.sinh

/**
 * Mapbox Vector Tile (protobuf) road decoder for an explicitly requested
 * OpenFreeMap experiment. Does not connect to the network or touch BLE.
 * Reads real lines from the OpenMapTiles transportation layer only.
 */
internal object OpenFreeMapMvtDecoder {
    const val MAX_TILE_BYTES = 2 * 1024 * 1024
    private const val MAX_UNCOMPRESSED_BYTES = 6 * 1024 * 1024
    private const val MAX_FEATURES = 12000
    private const val MAX_GEOMETRY_WORDS = 100000
    private const val MAX_ROAD_PARTS = 3000
    private const val MAX_ROAD_POINTS = 36000
    private const val MAX_ROW_POINTS = 512

    data class Stats(
        val features: Int, val roads: Int, val parts: Int, val points: Int,
        val layers: String = "", val roadClasses: String = ""
    )
    private data class Point(val latE6: Int, val lonE6: Int)
    private data class Feature(val type: Int, val tags: IntArray, val geometry: ByteArray)

    private class Wire(private val bytes: ByteArray) {
        private var pos = 0
        fun hasNext() = pos < bytes.size
        private fun skipBytes(n: Int) {
            if (n < 0 || n > bytes.size - pos) throw IOException("MVT: truncated field")
            pos += n
        }
        fun varint(): Long {
            var value = 0L
            repeat(10) { index ->
                if (!hasNext()) throw IOException("MVT: truncated varint")
                val b = bytes[pos++].toInt() and 0xff
                if (index == 9 && (b and 0xfe) != 0) throw IOException("MVT: varint overflow")
                value = value or ((b and 0x7f).toLong() shl (index * 7))
                if ((b and 0x80) == 0) return value
            }
            throw IOException("MVT: invalid varint")
        }
        fun number(): Int {
            val n = varint()
            if (n < 0L || n > Int.MAX_VALUE) throw IOException("MVT: number out of range")
            return n.toInt()
        }
        fun bytes(): ByteArray {
            val n = number()
            if (n > bytes.size - pos) throw IOException("MVT: bad field length")
            val result = bytes.copyOfRange(pos, pos + n)
            pos += n
            return result
        }
        fun skip(wireType: Int) {
            when (wireType) {
                0 -> varint()
                1 -> skipBytes(8)
                2 -> skipBytes(number())
                5 -> skipBytes(4)
                else -> throw IOException("MVT: unsupported wire type")
            }
        }
    }

    private fun unpack(bytes: ByteArray, max: Int): IntArray {
        val wire = Wire(bytes)
        val out = ArrayList<Int>()
        while (wire.hasNext()) {
            if (out.size >= max) throw IOException("MVT: too many packed integers")
            out.add(wire.number())
        }
        return out.toIntArray()
    }

    private fun feature(bytes: ByteArray): Feature {
        val wire = Wire(bytes)
        var type = 0
        var geometry = ByteArray(0)
        val tags = ArrayList<Int>()
        while (wire.hasNext()) {
            val field = wire.number()
            when (field) {
                18 -> tags.addAll(unpack(wire.bytes(), 4096).toList())
                24 -> type = wire.number()
                34 -> geometry = wire.bytes()
                else -> wire.skip(field and 7)
            }
        }
        return Feature(type, tags.toIntArray(), geometry)
    }

    private fun stringValue(raw: ByteArray): String {
        val wire = Wire(raw)
        var value = ""
        while (wire.hasNext()) {
            val field = wire.number()
            if (field == 10) value = wire.bytes().toString(Charsets.UTF_8)
            else wire.skip(field and 7)
        }
        return value
    }

    private fun roadClass(value: String): Int? = when (value) {
        "motorway", "trunk" -> 0
        "primary" -> 1
        "secondary", "tertiary" -> 2
        "minor", "service" -> 3
        else -> null // not paths, railways, tracks or construction
    }

    private fun decodeLines(raw: ByteArray): List<List<Pair<Long, Long>>> {
        val wire = Wire(raw)
        val parts = ArrayList<List<Pair<Long, Long>>>()
        var current = ArrayList<Pair<Long, Long>>()
        var x = 0L
        var y = 0L
        var words = 0
        while (wire.hasNext()) {
            val command = wire.number()
            words++
            val id = command and 7
            val count = command ushr 3
            if (count < 1 || count > MAX_GEOMETRY_WORDS)
                throw IOException("MVT: invalid geometry repetition")
            if (id == 7) continue // ClosePath: ignored for LineString
            if (id != 1 && id != 2) throw IOException("MVT: invalid LineString command")
            repeat(count) {
                fun signed(v: Long): Long = (v ushr 1) xor -(v and 1L)
                x += signed(wire.varint())
                y += signed(wire.varint())
                words += 2
                if (words > MAX_GEOMETRY_WORDS || x !in -100000L..100000L ||
                    y !in -100000L..100000L) throw IOException("MVT: oversized geometry")
                if (id == 1) {
                    if (current.size >= 2) parts.add(current)
                    current = ArrayList()
                } else if (current.isEmpty()) throw IOException("MVT: LineTo without MoveTo")
                current.add(x to y)
            }
        }
        if (current.size >= 2) parts.add(current)
        return parts
    }

    private fun unpackTile(input: ByteArray): ByteArray {
        if (input.size > MAX_TILE_BYTES) throw IOException("MVT: tile larger than 2 MiB")
        if (input.size < 2 || input[0] != 0x1f.toByte() ||
            input[1] != 0x8b.toByte()) return input
        GZIPInputStream(ByteArrayInputStream(input)).use { gzip ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = gzip.read(buffer)
                if (n < 0) break
                if (n > MAX_UNCOMPRESSED_BYTES - out.size())
                    throw IOException("MVT: decompressed tile larger than 6 MiB")
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }

    /**
     * Translate a z/x/y tile to bounded .motomap rows used by the existing
     * Android map cache. Speed is only a generic visual fallback: OpenMapTiles
     * does not provide the exact original maxspeed tag.
     */
    fun writeTile(z: Int, x: Int, y: Int, input: ByteArray, writer: BufferedWriter): Stats {
        if (z !in 0..18 || x !in 0 until (1 shl z) || y !in 0 until (1 shl z))
            throw IOException("MVT: tile coordinates out of range")
        val bytes = unpackTile(input)
        if (bytes.size > MAX_UNCOMPRESSED_BYTES) throw IOException("MVT: tile too large")
        val tile = Wire(bytes)
        var seenFeatures = 0
        var roadFeatures = 0
        var totalParts = 0
        var totalPoints = 0
        val layerNames = ArrayList<String>()
        val classes = LinkedHashMap<String, Int>()
        while (tile.hasNext()) {
            val field = tile.number()
            if (field != 26) {
                tile.skip(field and 7)
                continue
            }
            val layer = Wire(tile.bytes())
            var name = ""
            var extent = 4096
            val features = ArrayList<ByteArray>()
            val keys = ArrayList<String>()
            val values = ArrayList<String>()
            while (layer.hasNext()) {
                when (val key = layer.number()) {
                    10 -> name = layer.bytes().toString(Charsets.UTF_8)
                    18 -> {
                        if (features.size >= MAX_FEATURES) throw IOException("MVT: too many features")
                        features.add(layer.bytes())
                    }
                    26 -> keys.add(layer.bytes().toString(Charsets.UTF_8))
                    34 -> values.add(stringValue(layer.bytes()))
                    40 -> extent = layer.number()
                    else -> layer.skip(key and 7)
                }
            }
            if (layerNames.size < 20) layerNames.add(name)
            if (name != "transportation") continue
            if (extent !in 256..8192) throw IOException("MVT: invalid extent")
            seenFeatures += features.size
            if (seenFeatures > MAX_FEATURES) throw IOException("MVT: too many features")
            for (raw in features) {
                val f = feature(raw)
                if (f.type != 2 || f.tags.size % 2 != 0) continue
                var clazz = ""
                var subclass = ""
                for (i in f.tags.indices step 2) {
                    val ki = f.tags[i]
                    val vi = f.tags[i + 1]
                    if (ki !in keys.indices || vi !in values.indices) continue
                    if (keys[ki] == "class") clazz = values[vi]
                    if (keys[ki] == "subclass") subclass = values[vi]
                }
                if (classes.size < 30 || classes.containsKey(clazz)) {
                    classes[clazz] = (classes[clazz] ?: 0) + 1
                }
                val roadType = roadClass(clazz) ?: continue
                if (subclass in setOf("pedestrian", "footway", "cycleway",
                        "steps", "path", "living_street")) continue
                roadFeatures++
                for (part in decodeLines(f.geometry)) {
                    var offset = 0
                    while (offset < part.lastIndex) {
                        val slice = part.subList(offset, minOf(offset + MAX_ROW_POINTS, part.size))
                        offset += slice.size - 1
                        if (totalParts >= MAX_ROAD_PARTS ||
                            totalPoints + slice.size > MAX_ROAD_POINTS)
                            throw IOException("MVT: decoded road budget exceeded")
                        val grid = (1 shl z).toDouble()
                        val pts = slice.map { (px, py) ->
                            val lon = (x + px.toDouble() / extent) / grid * 360.0 - 180.0
                            val latitude = Math.toDegrees(atan(sinh(
                                PI * (1 - 2.0 * (y + py.toDouble() / extent) / grid))))
                            Point((latitude * 1e6).roundToInt(), (lon * 1e6).roundToInt())
                        }.distinct()
                        if (pts.size < 2) continue
                        val speed = when (roadType) { 0 -> 110; 1 -> 90; 2 -> 70; else -> 50 }
                        writer.write(roadType.toString() + "," + speed + "|" +
                            pts.minOf { it.latE6 } + "," + pts.minOf { it.lonE6 } + "," +
                            pts.maxOf { it.latE6 } + "," + pts.maxOf { it.lonE6 } + "|" +
                            pts.joinToString(";") { it.latE6.toString() + "," + it.lonE6 })
                        writer.newLine()
                        totalParts++
                        totalPoints += pts.size
                    }
                }
            }
        }
        return Stats(
            seenFeatures, roadFeatures, totalParts, totalPoints,
            layerNames.joinToString(",").take(200),
            classes.entries.joinToString(",") { it.key + ":" + it.value }.take(240)
        )
    }
}
