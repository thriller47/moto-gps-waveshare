package com.motogps.android.routing

import android.content.Context
import com.motogps.android.routing.OfflineValhallaRouter
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Installs Skadi .hgt.gz tiles from the verified MotoPack component.
 * Files are staged on the same filesystem and swapped only after extraction.
 */
internal object ValhallaElevationImporter {
    private val tilePath = Regex("^[NS][0-9]{2}/[NS][0-9]{2}[EW][0-9]{3}\\.hgt\\.gz$")
    private const val MAX_TILE_BYTES = 32L * 1024 * 1024
    private const val MAX_DATA_BYTES = 3L * 1024 * 1024 * 1024

    fun install(context: Context, archive: File, cancelled: () -> Boolean = { false }) {
        val parent = OfflineValhallaRouter.tileFile(context).parentFile
            ?: throw IOException("Cartella routing assente")
        if (!parent.isDirectory && !parent.mkdirs())
            throw IOException("Impossibile creare la cartella routing")
        val active = File(parent, "elevation")
        val staging = File(parent, "elevation.import")
        val backup = File(parent, "elevation.previous")
        if (staging.exists() || backup.exists())
            throw IOException("Installazione DEM precedente incompleta: verificare elevation.import / elevation.previous")
        if (!staging.mkdirs()) throw IOException("Impossibile preparare i dati DEM")

        var installed = false
        try {
            var total = 0L
            val seen = HashSet<String>()
            val buffer = ByteArray(128 * 1024)
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    if (cancelled()) throw java.util.concurrent.CancellationException("Import DEM annullato")
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory || !tilePath.matches(name) || !seen.add(name) ||
                        seen.size > 3000) {
                        throw IOException("Tile DEM non valida o duplicata: " + name)
                    }
                    val output = File(staging, name)
                    if (!output.parentFile!!.isDirectory && !output.parentFile!!.mkdirs())
                        throw IOException("Impossibile creare la sottocartella DEM")
                    var entryBytes = 0L
                    output.outputStream().buffered().use { sink ->
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            if (cancelled()) throw java.util.concurrent.CancellationException("Import DEM annullato")
                            entryBytes += n
                            total += n
                            if (entryBytes > MAX_TILE_BYTES || total > MAX_DATA_BYTES)
                                throw IOException("Dati DEM oltre il limite previsto")
                            sink.write(buffer, 0, n)
                        }
                    }
                    if (entryBytes < 100) throw IOException("Tile DEM vuota: " + name)
                    zip.closeEntry()
                }
            }
            if (seen.size < 50 ||
                !seen.contains("N45/N45E009.hgt.gz") ||
                !seen.contains("N40/N40E014.hgt.gz"))
                throw IOException("Pacchetto DEM Italia incompleto o copertura insufficiente")
            // The native actor scans the elevation directory at construction.
            OfflineValhallaRouter.close()
            if (active.exists() && !active.renameTo(backup))
                throw IOException("Impossibile salvare la cartella DEM precedente")
            if (!staging.renameTo(active)) {
                if (backup.exists()) backup.renameTo(active)
                throw IOException("Impossibile attivare la nuova cartella DEM")
            }
            installed = true
            if (backup.exists() && !backup.deleteRecursively())
                android.util.Log.w("ValhallaElevationImporter", "Backup DEM da rimuovere manualmente")
        } finally {
            if (!installed) {
                staging.deleteRecursively()
                if (backup.exists() && !active.exists()) backup.renameTo(active)
            }
        }
    }
}
