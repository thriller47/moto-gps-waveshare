package com.motogps.android.map

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/** Import pre-built OSM/PBF road tile packs, never parsing massive PBFs on a phone. */
internal object MotomapPackImporter {
    private val fileName = Regex("^tile_15_[0-9]{1,5}_[0-9]{1,5}\\.motomap$")
    private const val MAX_ENTRY_BYTES = 5L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 1_000L * 1024 * 1024
    private const val MAX_ENTRIES = 32_000

    fun safeName(name: String): Boolean = fileName.matches(name) &&
        !name.contains('/') && !name.contains('\\')

    fun install(context: Context, uri: Uri, onProgress: (Int) -> Unit = {}): Int {
        val mapsDir = HybridMapRepository.getSharedMapsDir(context)
        // A directory ending _parts is already indexed by the existing
        // repository; publish all tiles only when import succeeded.
        val temp = File(mapsDir, ".osm_import_staging")
        val target = File(mapsDir, ".osm_import_parts")
        val old = File(mapsDir, ".osm_import_previous")
        temp.deleteRecursively()
        if (!temp.mkdirs()) throw IOException("Impossibile creare cartella importazione")
        var total = 0L
        var count = 0
        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Impossibile aprire il pacchetto .motomap.zip")
            stream.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!safeName(entry.name) || entry.isDirectory)
                            throw IOException("Pacchetto non valido: ${entry.name}")
                        if (++count > MAX_ENTRIES) throw IOException("Troppe tile OSM")
                        val output = File(temp, entry.name)
                        output.outputStream().buffered().use { writer ->
                            val buffer = ByteArray(8192)
                            var entryBytes = 0L
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                entryBytes += read
                                total += read
                                if (entryBytes > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES)
                                    throw IOException("Pacchetto OSM troppo grande")
                                writer.write(buffer, 0, read)
                            }
                        }
                        if (output.length() == 0L) throw IOException("Tile OSM vuota")
                        zip.closeEntry()
                        if (count % 25 == 0) onProgress(count)
                    }
                }
            }
            if (count == 0) throw IOException("Nessuna tile .motomap nel pacchetto")
            old.deleteRecursively()
            if (target.exists() && !target.renameTo(old))
                throw IOException("Impossibile proteggere la precedente mappa")
            if (!temp.renameTo(target)) {
                if (old.exists()) old.renameTo(target)
                throw IOException("Impossibile installare il pacchetto OSM")
            }
            old.deleteRecursively()
            return count
        } finally {
            temp.deleteRecursively()
        }
    }
}
