package net.extrawdw.apps.notisync.screen

import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import net.extrawdw.notisync.protocol.ScreenMirrorCodec

internal sealed interface ScrcpyVideoRecord {
    data class Session(
        val dimensions: ScrcpySessionDimensions,
        val clientResize: Boolean,
    ) : ScrcpyVideoRecord

    data class Packet(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val codecConfig: Boolean,
        val keyFrame: Boolean,
    ) : ScrcpyVideoRecord
}

/**
 * Incrementally reads the pinned scrcpy v4.1 video framing used by screen protocol v1.
 *
 * The reader owns [input]. It validates every length before allocating packet storage, and a clean
 * end-of-stream is accepted only between complete records. The caller must consume [readPreamble]
 * before calling [readRecord].
 */
internal class ScrcpyVideoStreamReader(
    private val input: InputStream,
    private val expectedCodec: ScreenMirrorCodec,
) : Closeable {
    private var preambleRead = false

    fun readPreamble(): ScrcpySessionDimensions {
        check(!preambleRead) { "scrcpy video preamble has already been read" }
        val bytes = ByteArray(ScrcpyVideoPreamble.SIZE_BYTES)
        readFully(bytes, allowEofBeforeRecord = false)
        return ScrcpyVideoPreamble.validate(bytes, expectedCodec).also { preambleRead = true }
    }

    /** Returns `null` only when EOF occurs before the first byte of the next record. */
    fun readRecord(): ScrcpyVideoRecord? {
        check(preambleRead) { "scrcpy video preamble must be read first" }
        val header = ByteArray(RECORD_HEADER_BYTES)
        if (!readFully(header, allowEofBeforeRecord = true)) return null

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val firstWord = buffer.getInt(0)
        if (firstWord and Int.MIN_VALUE != 0) {
            require(firstWord and SESSION_RESERVED_FLAGS_MASK == 0) {
                "invalid scrcpy session flags"
            }
            val dimensions = validateDimensions(
                width = buffer.getInt(Int.SIZE_BYTES),
                height = buffer.getInt(Int.SIZE_BYTES * 2),
            )
            return ScrcpyVideoRecord.Session(
                dimensions = dimensions,
                clientResize = firstWord and SESSION_CLIENT_RESIZE_FLAG != 0,
            )
        }

        val ptsAndFlags = buffer.long
        val packetSize = buffer.int
        require(packetSize in 1..MAX_PACKET_SIZE_BYTES) { "invalid scrcpy video packet size" }

        val codecConfig = ptsAndFlags and PACKET_FLAG_CONFIG != 0L
        val keyFrame = ptsAndFlags and PACKET_FLAG_KEY_FRAME != 0L
        if (codecConfig) {
            // The pinned server writes config as an un-timestamped metadata packet with no other flags.
            require(ptsAndFlags == PACKET_FLAG_CONFIG) { "invalid scrcpy codec-config flags" }
        }

        val data = ByteArray(packetSize)
        readFully(data, allowEofBeforeRecord = false)
        return ScrcpyVideoRecord.Packet(
            data = data,
            presentationTimeUs = if (codecConfig) 0L else ptsAndFlags and PACKET_PTS_MASK,
            codecConfig = codecConfig,
            keyFrame = keyFrame,
        )
    }

    override fun close() {
        input.close()
    }

    private fun readFully(target: ByteArray, allowEofBeforeRecord: Boolean): Boolean {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) {
                if (allowEofBeforeRecord && offset == 0) return false
                throw EOFException("truncated scrcpy video stream")
            }
            if (count == 0) {
                // InputStream forbids a zero result for a non-empty request, but treating a broken
                // implementation as a one-byte read avoids a tight loop without weakening bounds.
                val next = input.read()
                if (next < 0) {
                    if (allowEofBeforeRecord && offset == 0) return false
                    throw EOFException("truncated scrcpy video stream")
                }
                target[offset++] = next.toByte()
            } else {
                offset += count
            }
        }
        return true
    }

    companion object {
        const val RECORD_HEADER_BYTES = 12
        const val MAX_PACKET_SIZE_BYTES = 16 * 1024 * 1024
        const val MAX_DIMENSION = 8_192
        const val MAX_PIXELS = 16_000_000L

        private const val SESSION_CLIENT_RESIZE_FLAG = 1
        private const val SESSION_RESERVED_FLAGS_MASK = 0x7fff_fffe
        private const val PACKET_FLAG_CONFIG = 1L shl 62
        private const val PACKET_FLAG_KEY_FRAME = 1L shl 61
        private const val PACKET_PTS_MASK = PACKET_FLAG_KEY_FRAME - 1

        private fun validateDimensions(width: Int, height: Int): ScrcpySessionDimensions {
            require(
                width in 1..MAX_DIMENSION &&
                    height in 1..MAX_DIMENSION &&
                    width.toLong() * height.toLong() <= MAX_PIXELS,
            ) { "invalid scrcpy session dimensions" }
            return ScrcpySessionDimensions(width, height)
        }
    }
}
