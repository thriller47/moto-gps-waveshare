package com.motogps.android.map

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import org.json.JSONObject

/**
 * Keep regional .motomap.zip archives compressed on disk. A complete Italy
 * pack contains hundreds of thousands of tiles: extracting it into separate
 * files is prohibitively expensive on Android.
 */
internal object MotomapPackImporter {
    private val tileName = Regex("^tile_15_[0-9]{1,5}_[0-9]{1,5}\\.motomap$")
    const val METADATA = "motomap_v4.json"
    private const val JUNCTION_SCHEMA = "osm-junction-way-v4"
    const val COVERAGE = "coverage_z14.txt"
    const val MAX_COVERAGE_BYTES = 16L * 1024 * 1024
    private val packName = Regex("[^a-zA-Z0-9._-]")
    private const val MAX_ENTRY_BYTES = 5L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 8L * 1024 * 1024 * 1024
    private const val MAX_ENTRIES = 500_000

    fun safeName(name: String): Boolean = (tileName.matches(name) || name == METADATA || name == COVERAGE) &&
        !name.contains('/') && !name.contains('\\')

    /** Strict v4 contract: a v3 archive must never be loaded as tagged v4. */
    internal fun isV4Manifest(manifest: String): Boolean = try {
        val json = JSONObject(manifest)
        json.optString("format") == "motomap" &&
            json.optInt("version") == 4 &&
            json.optInt("zoom") == 15 &&
            json.optString("topology") == "osm-node-ids-base36" &&
            json.optString("junction") == JUNCTION_SCHEMA
    } catch (_: Exception) {
        false
    }

    internal fun isV4Pack(zip: ZipFile): Boolean {
        val entry = zip.getEntry(METADATA) ?: return false
        if (entry.size !in 1L..4096L) return false
        return try {
            val manifest = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            isV4Manifest(manifest)
        } catch (_: Exception) {
            false
        }
    }

    fun packagesDir(context: Context): File =
        File(HybridMapRepository.getSharedMapsDir(context), ".osm_packages")

    private fun sourceName(context: Context, uri: Uri): String {
        var name: String? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0)
        }
        val clean = (name ?: "osm_roads.motomap.zip").substringAfterLast('/')
            .substringAfterLast('\\').replace(packName, "_").take(120)
        if (!clean.endsWith(".motomap.zip", ignoreCase = true))
            throw IOException("Selezionare un archivio .motomap.zip")
        return clean
    }

    /** Returns number of tiles. An existing pack with the same name is replaced atomically. */
    fun install(context: Context, uri: Uri, onProgress: (Int) -> Unit = {}): Int {
        val dir = packagesDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Impossibile creare la cartella mappe")
        val destination = File(dir, sourceName(context, uri))
        val pending = File(dir, destination.name + ".part")
        try {
            var copied = 0L
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Impossibile aprire il pacchetto .motomap.zip")
            input.use { source ->
                pending.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        copied += n
                        if (copied > MAX_ARCHIVE_BYTES)
                            throw IOException("Archivio ZIP troppo grande")
                        output.write(buffer, 0, n)
                    }
                }
            }
            var count = 0
            var total = 0L
            var metadata = false
            var coverage = false
            ZipFile(pending).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !safeName(entry.name))
                        throw IOException("Tile ZIP non valida: ${entry.name}")
                    if (++count > MAX_ENTRIES) throw IOException("Troppe tile OSM")
                    val limit = when (entry.name) {
                        METADATA -> 4096L
                        COVERAGE -> MAX_COVERAGE_BYTES
                        else -> MAX_ENTRY_BYTES
                    }
                    if (entry.size <= 0 || entry.size > limit || entry.compressedSize < 0)
                        throw IOException("Dimensione tile non valida: ${entry.name} (${entry.size} byte, limite $limit)")
                    if (entry.name == METADATA) {
                        if (metadata) throw IOException("Metadata duplicate")
                        metadata = true
                    }
                    if (entry.name == COVERAGE) {
                        if (coverage) throw IOException("Coverage index duplicate")
                        coverage = true
                    }
                    total += entry.size
                    if (total > MAX_TOTAL_BYTES) throw IOException("Pacchetto OSM troppo grande")
                    if (count % 1000 == 0) onProgress(count)
                }
                if (!metadata || !isV4Pack(zip)) {
                    throw IOException("MotoMap v4 obbligatoria: rigenerare lo ZIP dal PBF con il nuovo convertitore")
                }
            }
            if (count <= 1) throw IOException("Archivio MotoMap senza tile stradali")
            // Keep the previous archive intact if publishing the new one fails.
            val backup = File(dir, destination.name + ".backup")
            if (destination.exists()) {
                if (backup.exists() && !backup.delete()) throw IOException("Backup non eliminabile")
                if (!destination.renameTo(backup)) throw IOException("Impossibile proteggere la mappa precedente")
            }
            if (!pending.renameTo(destination)) {
                if (backup.exists()) backup.renameTo(destination)
                throw IOException("Impossibile installare il pacchetto")
            }
            backup.delete()
            onProgress(count)
            return count
        } finally {
            pending.delete()
        }
    }
}
