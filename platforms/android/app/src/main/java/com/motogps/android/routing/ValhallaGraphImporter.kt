package com.motogps.android.routing

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.github.luben.zstd.ZstdInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Install an externally downloaded Valhalla tile extract.
 * The native Valhalla engine can memory-map a plain TAR, not a .tar.zst stream.
 * Write the uncompressed TAR directly to internal storage without buffering it
 * in memory. Keep the previous graph until validation is complete.
 */
internal object ValhallaGraphImporter {
    private const val MAX_TAR_BYTES = 20L * 1024 * 1024 * 1024
    private const val MIN_FREE_BYTES = 64L * 1024 * 1024
    private const val REPORT_EVERY_BYTES = 16L * 1024 * 1024

    fun isSupportedName(fileName: String): Boolean =
        fileName.endsWith(".tar", ignoreCase = true) ||
            fileName.endsWith(".tar.zst", ignoreCase = true)

    private fun displayName(context: Context, uri: Uri): String {
        val resolver = context.contentResolver
        val name = resolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
            .substringAfterLast(':')
        if (!isSupportedName(name)) {
            throw IOException("Selezionare un file .tar o .tar.zst")
        }
        return name
    }

    /**
     * Exposed for JVM tests; progress counts expanded bytes rather than
     * claiming to know the unknown uncompressed archive size.
     */
    fun copyTar(
        source: InputStream,
        output: OutputStream,
        compressed: Boolean,
        onBytes: (Long) -> Unit = {},
        cancelled: () -> Boolean = { false },
        spaceAvailable: () -> Long = { Long.MAX_VALUE }
    ): Long {
        val decoder = if (compressed) ZstdInputStream(source) else source
        decoder.use { input ->
            val buffer = ByteArray(128 * 1024)
            var total = 0L
            var nextReport = REPORT_EVERY_BYTES
            while (true) {
                if (cancelled()) throw IOException("Importazione annullata")
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > MAX_TAR_BYTES) throw IOException("Grafo troppo grande")
                if (total >= nextReport) {
                    if (spaceAvailable() < MIN_FREE_BYTES)
                        throw IOException("Spazio insufficiente durante l'importazione")
                    onBytes(total)
                    nextReport = total + REPORT_EVERY_BYTES
                }
                output.write(buffer, 0, count)
            }
            if (cancelled()) throw IOException("Importazione annullata")
            onBytes(total)
            return total
        }
    }

    /** Called from Dispatchers.IO. Returns installed *uncompressed* bytes. */
    fun install(
        context: Context,
        uri: Uri,
        onBytes: (Long) -> Unit = {},
        cancelled: () -> Boolean = { false }
    ): Long {
        val name = displayName(context, uri)
        val compressed = name.endsWith(".tar.zst", ignoreCase = true)
        val target = OfflineValhallaRouter.tileFile(context)
        val dir = target.parentFile ?: throw IOException("Directory grafo non disponibile")
        if (!dir.isDirectory && !dir.mkdirs())
            throw IOException("Impossibile creare la cartella del grafo")
        if (dir.usableSpace < MIN_FREE_BYTES)
            throw IOException("Spazio insufficiente per importare il grafo")

        val incoming = File(dir, "valhalla_tiles.import")
        val backup = File(dir, "valhalla_tiles.previous")
        // On an interrupted earlier swap, retain a recoverable graph.
        if (backup.isFile && !target.exists() && !backup.renameTo(target))
            throw IOException("Impossibile recuperare il grafo precedente")
        if (backup.isFile && target.exists() && !backup.delete())
            throw IOException("Impossibile rimuovere il vecchio backup")
        if (incoming.exists() && !incoming.delete())
            throw IOException("Impossibile rimuovere un'importazione incompleta")

        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Impossibile aprire l'archivio del grafo")
            stream.use { input ->
                incoming.outputStream().buffered().use { output ->
                    copyTar(input, output, compressed, onBytes, cancelled,
                        { dir.usableSpace })
                }
            }
            if (!ValhallaTileDownloads.validateTar(incoming))
                throw IOException("Archivio TAR non valido: mancano tile Valhalla .gph")
            if (cancelled()) throw IOException("Importazione annullata")
            OfflineValhallaRouter.close()
            if (target.exists() && !target.renameTo(backup))
                throw IOException("Impossibile proteggere il grafo precedente")
            if (!incoming.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                throw IOException("Impossibile installare il nuovo grafo")
            }
            if (backup.exists() && !backup.delete()) {
                // The new graph is already installed; old backup can be
                // cleaned up on the next import without data loss.
                android.util.Log.w("ValhallaGraphImporter", "Backup precedente non eliminato")
            }
            return target.length()
        } finally {
            incoming.delete()
        }
    }
}
