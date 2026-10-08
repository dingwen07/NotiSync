package net.extrawdw.apps.notisync.sshkeyprovider

import java.io.IOException
import java.nio.ByteBuffer
import net.extrawdw.apps.notisync.appicon.MAX_ICON_SOURCE_BYTES

/**
 * Unwraps PNG representations from modern ICNS files for Android's native image decoder.
 * JPEG 2000 and legacy bitmap/mask representations are skipped; no image codec is implemented here.
 * Container layout: https://github.com/relikd/icns-analysis#file-structure
 */
internal object IcnsIconReader {
    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    private val imageTypes = setOf(
        "icp4", "icp5", "icp6", "ic07", "ic08", "ic09", "ic10", "ic11", "ic12", "ic13", "ic14",
        "ic04", "ic05", "icsb", "icsB", "sb24", "SB24", "is32", "il32", "ih32", "it32",
    )

    /** Null means an ordinary image. Recognized but malformed/unsupported ICNS files fail explicitly. */
    fun pngImages(source: ByteArray): List<ByteBuffer>? {
        if (source.size < 4 || source.ascii(0) != "icns") return null
        if (source.size !in 8..MAX_ICON_SOURCE_BYTES || source.int32(4) != source.size) {
            throw IOException("Invalid ICNS file length")
        }
        val images = mutableListOf<Pair<Int, ByteBuffer>>()
        var offset = 8
        var entries = 0
        while (offset < source.size) {
            // Normal icons contain a few dozen entries. Bound work on untrusted containers too.
            if (++entries > 256 || source.size - offset < 8) throw IOException("Invalid ICNS entry header")
            val type = source.ascii(offset)
            val length = source.int32(offset + 4)
            if (length < 8 || length > source.size - offset) throw IOException("Invalid ICNS entry length")
            val start = offset + 8
            val payloadLength = length - 8
            if (type in imageTypes && payloadLength >= 33 &&
                pngSignature.indices.all { source[start + it] == pngSignature[it] } &&
                source.int32(start + 8) == 13 && source.ascii(start + 12) == "IHDR"
            ) {
                val width = source.int32(start + 16)
                val height = source.int32(start + 20)
                // ICNS representations are square, at most 1024 physical pixels (including Retina).
                if (width in 1..1024 && height == width) {
                    images += width to ByteBuffer.wrap(source, start, payloadLength).slice().asReadOnlyBuffer()
                }
            }
            offset += length
        }
        if (images.isEmpty()) throw IOException("ICNS contains no supported PNG representation")
        return images.sortedByDescending { it.first }.map { it.second }
    }

    private fun ByteArray.ascii(offset: Int): String = String(this, offset, 4, Charsets.US_ASCII)

    private fun ByteArray.int32(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 24) or
            ((this[offset + 1].toInt() and 0xff) shl 16) or
            ((this[offset + 2].toInt() and 0xff) shl 8) or
            (this[offset + 3].toInt() and 0xff)
}
