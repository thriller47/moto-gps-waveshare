package com.motogps.android.map

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manually triggered ONE tile OpenFreeMap experiment, not a navigation source.
 * Do not call from a GPS update or prefetch neighbours automatically.
 * Public-service ToS: https://openfreemap.org/tos/ (2026-10-14).
 */
internal object OpenFreeMapTileProbe {
    data class Result(
        val tile: GatewayRoadTiles.Tile,
        val downloadedBytes: Int,
        val stats: OpenFreeMapMvtDecoder.Stats,
        val filename: String
    )

    fun downloadOneTile(context: Context, latitude: Double, longitude: Double): Result {
        val tile = GatewayRoadTiles.tileFor(latitude, longitude)
        val url = URL("https://tiles.openfreemap.org/planet/latest/" +
            GatewayRoadTiles.ZOOM + "/" + tile.x + "/" + tile.y + ".pbf")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/x-protobuf, application/octet-stream")
            setRequestProperty("User-Agent", "MotoGPS-OpenFreeMap-MVT-Trial/0.1")
            instanceFollowRedirects = false
        }
        val bytes: ByteArray
        var contentType = ""
        var responseLength = -1L
        var contentEncoding = ""
        try {
            if (connection.responseCode != 200)
                throw IOException("OpenFreeMap HTTP " + connection.responseCode)
            contentType = connection.contentType.orEmpty().take(80)
            responseLength = connection.contentLengthLong
            contentEncoding = connection.contentEncoding.orEmpty().take(40)
            if (connection.contentLengthLong > OpenFreeMapMvtDecoder.MAX_TILE_BYTES)
                throw IOException("OpenFreeMap tile exceeds 2 MiB")
            bytes = connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    if (n > OpenFreeMapMvtDecoder.MAX_TILE_BYTES - output.size())
                        throw IOException("OpenFreeMap tile exceeds 2 MiB")
                    output.write(buf, 0, n)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }

        if (bytes.isEmpty()) throw IOException(
            "OpenFreeMap risposta HTTP 200 vuota: tile=" + tile.key +
                " bytes=0 type=" + contentType +
                " length=" + responseLength + " encoding=" + contentEncoding +
                ". Nessun dato MVT da decodificare"
        )

        val maps = HybridMapRepository.getSharedMapsDir(context)
        val name = "ofm_trial_" + tile.key + ".motomap"
        val target = File(maps, name)
        val temp = File(maps, name + ".tmp")
        try {
            val stats = temp.bufferedWriter().use { writer ->
                OpenFreeMapMvtDecoder.writeTile(
                    GatewayRoadTiles.ZOOM, tile.x, tile.y, bytes, writer)
            }
            if (stats.parts == 0) throw IOException(
                "MVT zero strade tile=" + tile.key +
                    " bytes=" + bytes.size + " type=" + contentType +
                    " features=" + stats.features + " roads=" + stats.roads +
                    " layers=" + stats.layers + " classes=" + stats.roadClasses
            )
            if (target.exists() && !target.delete()) throw IOException("Cannot replace trial tile")
            if (!temp.renameTo(target)) throw IOException("Cannot save trial tile")
            return Result(tile, bytes.size, stats, name)
        } finally {
            temp.delete()
        }
    }
}
