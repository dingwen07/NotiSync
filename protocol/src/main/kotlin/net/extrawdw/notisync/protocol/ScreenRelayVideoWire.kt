package net.extrawdw.notisync.protocol

/**
 * Clear, authenticated metadata for one fragment of an end-to-end encrypted Relay video record.
 *
 * The broker may inspect only this fixed header. The complete header is AES-GCM AAD, so changing a
 * sequence, record type, length, or offset is detected by the requester. A record is the exact bytes
 * consumed by the scrcpy stream parser (preamble or one complete framed access unit).
 */
data class ScreenRelayVideoFragmentHeader(
    val flags: Int,
    val recordSequence: Long,
    val recordBytes: Int,
    val fragmentOffset: Int,
    val fragmentBytes: Int,
) {
    val firstFragment: Boolean get() = fragmentOffset == 0
    val predictive: Boolean get() = flags == ScreenRelayVideoWire.FLAG_DELTA
    val keyFrame: Boolean get() = flags == ScreenRelayVideoWire.FLAG_KEY_FRAME
}

object ScreenRelayVideoWire {
    const val HEADER_BYTES = 28
    const val AEAD_TAG_BYTES = 16
    const val MAX_MESSAGE_BYTES = 16 * 1024
    const val MAX_FRAGMENT_PLAINTEXT_BYTES = MAX_MESSAGE_BYTES - HEADER_BYTES - AEAD_TAG_BYTES
    const val MAX_RECORD_BYTES = 16 * 1024 * 1024 + 12

    const val FLAG_PREAMBLE = 1
    const val FLAG_SESSION = 2
    const val FLAG_CODEC_CONFIG = 4
    const val FLAG_KEY_FRAME = 8
    const val FLAG_DELTA = 16

    private const val MAGIC = 0x4e535231 // "NSR1"
    private const val VERSION = 1
    private const val TYPE_VIDEO_FRAGMENT = 1
    private const val KNOWN_FLAGS = FLAG_PREAMBLE or FLAG_SESSION or FLAG_CODEC_CONFIG or
        FLAG_KEY_FRAME or FLAG_DELTA

    fun encodeHeader(
        flags: Int,
        recordSequence: Long,
        recordBytes: Int,
        fragmentOffset: Int,
        fragmentBytes: Int,
    ): ByteArray {
        requireValid(flags, recordSequence, recordBytes, fragmentOffset, fragmentBytes)
        return ByteArray(HEADER_BYTES).also { target ->
            putInt(target, 0, MAGIC)
            target[4] = VERSION.toByte()
            target[5] = TYPE_VIDEO_FRAGMENT.toByte()
            target[6] = flags.toByte()
            target[7] = 0
            putLong(target, 8, recordSequence)
            putInt(target, 16, recordBytes)
            putInt(target, 20, fragmentOffset)
            putInt(target, 24, fragmentBytes)
        }
    }

    /** Returns null for anything that is not one canonical Relay video fragment. */
    fun decodeHeader(message: ByteArray): ScreenRelayVideoFragmentHeader? {
        if (message.size !in (HEADER_BYTES + AEAD_TAG_BYTES + 1)..MAX_MESSAGE_BYTES) return null
        if (getInt(message, 0) != MAGIC ||
            message[4].toInt() and 0xff != VERSION ||
            message[5].toInt() and 0xff != TYPE_VIDEO_FRAGMENT ||
            message[7].toInt() != 0
        ) return null
        val header = ScreenRelayVideoFragmentHeader(
            flags = message[6].toInt() and 0xff,
            recordSequence = getLong(message, 8),
            recordBytes = getInt(message, 16),
            fragmentOffset = getInt(message, 20),
            fragmentBytes = getInt(message, 24),
        )
        if (!valid(
                header.flags,
                header.recordSequence,
                header.recordBytes,
                header.fragmentOffset,
                header.fragmentBytes,
            ) || message.size != HEADER_BYTES + header.fragmentBytes + AEAD_TAG_BYTES
        ) return null
        return header
    }

    fun headerBytes(message: ByteArray): ByteArray = message.copyOfRange(0, HEADER_BYTES)

    private fun requireValid(
        flags: Int,
        recordSequence: Long,
        recordBytes: Int,
        fragmentOffset: Int,
        fragmentBytes: Int,
    ) = require(valid(flags, recordSequence, recordBytes, fragmentOffset, fragmentBytes)) {
        "invalid Relay video fragment metadata"
    }

    private fun valid(
        flags: Int,
        recordSequence: Long,
        recordBytes: Int,
        fragmentOffset: Int,
        fragmentBytes: Int,
    ): Boolean = flags != 0 && flags and KNOWN_FLAGS == flags && flags.countOneBits() == 1 &&
        recordSequence >= 0 && recordBytes in 1..MAX_RECORD_BYTES &&
        fragmentOffset >= 0 && fragmentBytes in 1..MAX_FRAGMENT_PLAINTEXT_BYTES &&
        fragmentOffset <= recordBytes - fragmentBytes

    private fun putInt(target: ByteArray, offset: Int, value: Int) {
        repeat(4) { index -> target[offset + index] = (value ushr (24 - index * 8)).toByte() }
    }

    private fun getInt(source: ByteArray, offset: Int): Int {
        var value = 0
        repeat(4) { index -> value = value shl 8 or (source[offset + index].toInt() and 0xff) }
        return value
    }

    private fun putLong(target: ByteArray, offset: Int, value: Long) {
        repeat(8) { index -> target[offset + index] = (value ushr (56 - index * 8)).toByte() }
    }

    private fun getLong(source: ByteArray, offset: Int): Long {
        var value = 0L
        repeat(8) { index -> value = value shl 8 or (source[offset + index].toLong() and 0xffL) }
        return value
    }
}
