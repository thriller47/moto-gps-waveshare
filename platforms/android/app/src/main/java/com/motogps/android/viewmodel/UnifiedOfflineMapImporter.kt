package com.motogps.android.viewmodel

import android.content.Context
import android.net.Uri
import com.motogps.android.map.MotomapPackImporter
import com.motogps.android.routing.ValhallaGraphImporter
import com.motogps.android.routing.ValhallaElevationImporter
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.json.JSONObject

/**
 * Unified offline package. Verify every component before invoking any of the
 * existing (individually atomic) installers. This does NOT make the five
 * installers collectively atomic; an installer failure can leave mixed data.
 */
internal object UnifiedOfflineMapImporter {
    private const val FORMAT = "motochat-offline"
    private const val MANIFEST = "manifest.json"
    private const val MAX_MANIFEST = 16 * 1024
    private const val MAX_COMPONENT_BYTES = 8L * 1024 * 1024 * 1024
    private val names = linkedMapOf(
        "motomap" to "motomap/italy-v4.motomap.zip",
        "valhalla" to "valhalla/valhalla_tiles.tar.zst",
        "photon" to "photon/photon-italy-v2.sqlite",
        "tourist" to "road-dna/tourist-italy-v1.sqlite",
        "elevation" to "elevation/elevation-italy.zip"
    )

    /** Preserve native extensions: Valhalla's importer uses .tar.zst to select Zstd. */
    internal fun stagingName(key: String): String =
        names.getValue(key).substringAfterLast('/')

    /**
     * Callback for UI updates. Caller must dispatch this off the main thread.
     * Reuses existing importers; caller must prevent navigation and concurrent
     * imports while this operation is running.
     */
    fun install(context: Context, uri: Uri, progress: (String) -> Unit = {}, cancelled: () -> Boolean = { false }) {
        val dir = File(context.filesDir, "motopack-staging")
        if (dir.exists()) dir.deleteRecursively()
        if (!dir.mkdirs()) throw IOException("Directory temporanea non disponibile")
        if (dir.usableSpace < 512L * 1024 * 1024) {
            throw IOException("Spazio libero insufficiente per importare il MotoPack (servono almeno 512 MB liberi)")
        }
        val staged = names.mapValues { (key, _) -> File(dir, stagingName(key)) }
        val lengths = mutableMapOf<String, Long>()
        val hashes = mutableMapOf<String, String>()
        val seen = mutableSetOf<String>()
        var manifestText: String? = null
        val buffer = ByteArray(128 * 1024)
        try {
            val source = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Impossibile leggere il pacchetto offline")
            source.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        if (cancelled()) throw java.util.concurrent.CancellationException("Importazione annullata")
                        val entry = zip.nextEntry ?: break
                        if (!seen.add(entry.name) || entry.isDirectory ||
                            (entry.name != MANIFEST && entry.name !in names.values)) {
                            throw IOException("Voce non consentita o duplicata: ${entry.name}")
                        }
                        if (entry.name == MANIFEST) {
                            val output = java.io.ByteArrayOutputStream()
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                if (cancelled()) throw java.util.concurrent.CancellationException("Importazione annullata")
                                if (output.size().toLong() + n > MAX_MANIFEST)
                                    throw IOException("Manifest troppo grande")
                                output.write(buffer, 0, n)
                            }
                            manifestText = output.toString(Charsets.UTF_8.name())
                        } else {
                            val key = names.entries.first { it.value == entry.name }.key
                            val target = staged.getValue(key)
                            if (target.exists() && !target.delete()) throw IOException("File temporaneo occupato")
                            val digest = MessageDigest.getInstance("SHA-256")
                            var total = 0L
                            target.outputStream().buffered().use { output ->
                                while (true) {
                                    val n = zip.read(buffer)
                                    if (n < 0) break
                                    if (cancelled()) throw java.util.concurrent.CancellationException("Importazione annullata")
                                    total += n
                                    if (total > MAX_COMPONENT_BYTES)
                                        throw IOException("Componente troppo grande: $key")
                                    digest.update(buffer, 0, n)
                                    output.write(buffer, 0, n)
                                }
                            }
                            if (total == 0L) throw IOException("Componente vuoto: $key")
                            lengths[key] = total
                            hashes[key] = digest.digest().joinToString("") { "%02x".format(it) }
                            progress("Verificato $key (${total / 1048576} MiB)")
                        }
                        zip.closeEntry()
                    }
                }
            }
            if (seen != names.values.toSet() + MANIFEST)
                throw IOException("Pacchetto incompleto")
            val root = JSONObject(manifestText ?: throw IOException("Manifest assente"))
            if (root.optString("format") != FORMAT || root.optInt("version") != 3 ||
                root.optString("region") != "IT") throw IOException("Versione del pacchetto non supportata")
            val components = root.getJSONObject("components")
            if (components.length() != names.size) throw IOException("Manifest non coerente")
            for ((key, name) in names) {
                val item = components.getJSONObject(key)
                if (item.length() != 3 || item.getString("path") != name ||
                    item.getLong("bytes") != lengths[key] ||
                    item.getString("sha256") != hashes[key]) {
                    throw IOException("Checksum o dimensione non valida: $key")
                }
            }
            // No installer runs until the full archive has passed integrity checks.
            if (cancelled()) throw java.util.concurrent.CancellationException("Importazione annullata")
            progress("Installazione MotoMap...")
            MotomapPackImporter.install(context, Uri.fromFile(staged.getValue("motomap")))
            staged.getValue("motomap").delete()
            if (cancelled()) throw java.util.concurrent.CancellationException("Importazione annullata")
            progress("Installazione Photon...")
            OfflinePhotonPlaceSearch.install(context, Uri.fromFile(staged.getValue("photon")))
            staged.getValue("photon").delete()
            if (cancelled()) throw java.util.concurrent.CancellationException("Importazione annullata")
            progress("Installazione Valhalla...")
            val graphBytes = ValhallaGraphImporter.install(
                context, Uri.fromFile(staged.getValue("valhalla")),
                { copied -> progress("Valhalla: elaborati ${copied / 1048576L} MiB") },
                cancelled
            )
            staged.getValue("valhalla").delete()
            if (graphBytes <= 0L) throw IOException("Valhalla non installato")
            progress("Valhalla installato (${graphBytes / 1048576L} MiB)")
            if (cancelled()) throw java.util.concurrent.CancellationException("Importazione annullata")
            progress("Installazione quote DEM Valhalla...")
            ValhallaElevationImporter.install(context, staged.getValue("elevation"), cancelled)
            staged.getValue("elevation").delete()
            if (cancelled()) throw java.util.concurrent.CancellationException("Importazione annullata")
            progress("Installazione Road DNA...")
            OfflineTouristPlaceSearch.install(context, Uri.fromFile(staged.getValue("tourist")))
            staged.getValue("tourist").delete()
            progress("MotoPack v3 installato (MotoMap, Photon, Valhalla, DEM, Road DNA)")
        } finally {
            staged.values.forEach { it.delete() }
            if (dir.exists() && dir.listFiles().isNullOrEmpty()) dir.delete()
        }
    }
}
