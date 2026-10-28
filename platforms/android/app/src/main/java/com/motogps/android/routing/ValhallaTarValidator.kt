package com.motogps.android.routing

import java.io.File
import java.io.FileInputStream

/** Validate uncompressed Valhalla TAR headers without unpacking the graph. */
internal object ValhallaTarValidator {
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
