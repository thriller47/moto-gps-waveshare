package com.motogps.android.routing

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Routing graphs are independent of .motomap display caches. */
data class ValhallaRegion(
    val id: String, val label: String, val bytes: Long,
    val url: String, val sha256: String
)

internal object ValhallaTileDownloads {
    private const val CATALOG = "https://api.github.com/repos/librescoot/valhalla-tiles/releases/tags/latest"
    private const val ORIGIN = "https://github.com/librescoot/valhalla-tiles/releases/download/"
    private val archive = Regex("^valhalla_tiles_([a-z0-9_-]+)\\.tar$")
    private val digestFormat = Regex("^sha256:([a-fA-F0-9]{64})$")

    fun parseCatalog(release: JSONObject): List<ValhallaRegion> {
        val assets = release.optJSONArray("assets") ?: return emptyList()
        val result = mutableListOf<ValhallaRegion>()
        for (i in 0 until assets.length()) {
            val item = assets.optJSONObject(i) ?: continue
            val name = item.optString("name")
            val id = archive.matchEntire(name)?.groupValues?.get(1) ?: continue
            val sha = digestFormat.matchEntire(item.optString("digest"))?.groupValues?.get(1)
                ?: continue
            val url = item.optString("browser_download_url")
            val bytes = item.optLong("size")
            if (bytes <= 1024 || !url.startsWith(ORIGIN) || !url.endsWith("/" + name)) continue
            val label = when (id) {
                "italy-nord-ovest" -> "Italia Nord-Ovest (Lombardia, Piemonte, Liguria, Valle d'Aosta)"
                "ile-de-france" -> "Francia - Île-de-France"
                "berlin_brandenburg" -> "Germania - Berlino e Brandeburgo"
                else -> id.replace('-', ' ').replace('_', ' ')
                    .replaceFirstChar { it.uppercaseChar() }
            }
            result.add(ValhallaRegion(id, label, bytes, url, sha.lowercase()))
        }
        return result.sortedWith(compareBy<ValhallaRegion> { !it.id.startsWith("italy-") }
            .thenBy { it.label })
    }

    fun fetchCatalog(): List<ValhallaRegion> {
        val conn = (URL(CATALOG).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "Moto-GPS-Android")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            if (conn.responseCode != 200)
                throw IOException("Catalogo non disponibile (HTTP " + conn.responseCode + ")")
            parseCatalog(JSONObject(conn.inputStream.bufferedReader().use { it.readText() }))
        } finally {
            conn.disconnect()
        }
    }

    /** Must be called on Dispatchers.IO. Keeps prior routing graph until all checks pass. */
    fun install(
        context: Context, region: ValhallaRegion,
        progress: (Float) -> Unit, cancelled: () -> Boolean
    ) {
        require(archive.matches("valhalla_tiles_" + region.id + ".tar") &&
            region.url.startsWith(ORIGIN) && region.url.endsWith(".tar") &&
            region.sha256.matches(Regex("[a-f0-9]{64}"))) { "Archivio non valido" }
        val target = OfflineValhallaRouter.tileFile(context)
        val dir = target.parentFile ?: error("Directory routing mancante")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Impossibile creare la directory routing")
        if (dir.usableSpace < region.bytes + 64L * 1024 * 1024)
            throw IOException("Spazio insufficiente: servono circa " + (region.bytes / 1_000_000 + 64) + " MB")
        val part = File(dir, "valhalla_tiles.download")
        var conn: HttpURLConnection? = null
        try {
            if (cancelled()) throw IOException("Annullato")
            conn = (URL(region.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "Moto-GPS-Android")
            }
            if (conn.responseCode != 200)
                throw IOException("Download HTTP " + conn.responseCode)
            val checksum = MessageDigest.getInstance("SHA-256")
            var total = 0L
            conn.inputStream.use { input ->
                part.outputStream().buffered().use { output ->
                    val chunk = ByteArray(128 * 1024)
                    while (true) {
                        if (cancelled()) throw IOException("Annullato")
                        val n = input.read(chunk)
                        if (n < 0) break
                        output.write(chunk, 0, n)
                        checksum.update(chunk, 0, n)
                        total += n
                        if (total > region.bytes) throw IOException("Dimensioni archivio non coerenti")
                        progress((total.toDouble() / region.bytes).toFloat().coerceIn(0f, 1f))
                    }
                }
            }
            if (cancelled()) throw IOException("Annullato")
            if (total != region.bytes) throw IOException("Archivio incompleto")
            val actual = checksum.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (actual != region.sha256) throw IOException("Controllo SHA-256 fallito")
            if (!validateTar(part)) throw IOException("Archivio non contiene tile Valhalla .gph valide")
            if (cancelled()) throw IOException("Annullato")
            OfflineValhallaRouter.close()
            try {
                Files.move(part.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            progress(1f)
        } finally {
            conn?.disconnect()
            part.delete()
        }
    }

    /** Reads tar metadata without unpacking large binary tiles. SHA validates every byte. */
    fun validateTar(file: File): Boolean {
        if (!file.isFile || file.length() < 1024) return false
        FileInputStream(file).use { stream ->
            val header = ByteArray(512)
            var hasTile = false
            for (i in 0 until 100_000) {
                var read = 0
                while (read < 512) {
                    val n = stream.read(header, read, 512 - read)
                    if (n < 0) return false
                    read += n
                }
                if (header.all { it == 0.toByte() }) return hasTile
                if (String(header, 257, 5, Charsets.US_ASCII) != "ustar") return false
                val name = String(header, 0, 100, Charsets.US_ASCII).trimEnd('\u0000')
                if (name.endsWith(".gph")) hasTile = true
                val octal = String(header, 124, 12, Charsets.US_ASCII)
                    .trim('\u0000', ' ', '\n')
                val size = octal.toLongOrNull(8) ?: return false
                if (size < 0 || size > file.length()) return false
                var left = ((size + 511) / 512) * 512
                while (left > 0) {
                    val skipped = stream.skip(left)
                    if (skipped <= 0) return false
                    left -= skipped
                }
            }
        }
        return false
    }
}
