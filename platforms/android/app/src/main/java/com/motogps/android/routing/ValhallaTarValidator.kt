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
            val fileLen = file.length()
            var offset = 0L
            val skipBuf = ByteArray(8192)
            for (i in 0 until 500_000) {
                var read = 0
                while (read < 512) {
                    val n = stream.read(header, read, 512 - read)
                    if (n < 0) return false
                    read += n
                }
                offset += 512L
                if (header.all { it == 0.toByte() }) return hasTile
                if (String(header, 257, 5, Charsets.US_ASCII) != "ustar") return false
                val name = String(header, 0, 100, Charsets.US_ASCII).substringBefore('\u0000')
                if (name.endsWith(".gph")) hasTile = true
                val size = if ((header[124].toInt() and 0x80) != 0) {
                    var v = 0L
                    for (b in 125 until 136) {
                        v = (v shl 8) or (header[b].toInt() and 0xFF).toLong()
                    }
                    v
                } else {
                    val octal = String(header, 124, 12, Charsets.US_ASCII)
                        .trim('\u0000', ' ', '\n')
                    octal.toLongOrNull(8) ?: return false
                }
                if (size < 0 || size > fileLen - offset) return false
                var left = ((size + 511L) / 512L) * 512L
                if (left > fileLen - offset) return false
                while (left > 0L) {
                    val skipped = stream.skip(left)
                    if (skipped > 0L) {
                        left -= skipped
                        offset += skipped
                    } else {
                        val toRead = minOf(left, skipBuf.size.toLong()).toInt()
                        val n = stream.read(skipBuf, 0, toRead)
                        if (n <= 0) return false
                        left -= n
                        offset += n
                    }
                }
            }
        }
        return false
    }
}
