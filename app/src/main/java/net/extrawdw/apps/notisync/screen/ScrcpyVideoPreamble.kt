package net.extrawdw.apps.notisync.screen

import java.nio.ByteBuffer
import java.nio.ByteOrder
import net.extrawdw.notisync.protocol.ScreenMirrorCodec

internal data class ScrcpySessionDimensions(val width: Int, val height: Int)

/** Validates the bounded scrcpy stream prefix before any privileged byte is forwarded to the viewer. */
internal object ScrcpyVideoPreamble {
    const val SIZE_BYTES = 16

    fun validate(bytes: ByteArray, codec: ScreenMirrorCodec): ScrcpySessionDimensions {
        require(bytes.size == SIZE_BYTES) { "invalid scrcpy preamble length" }
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val codecId = header.int
        val flags = header.int
        val width = header.int
        val height = header.int
        require(codecId == codec.wireId()) { "screen encoder returned a different codec" }
        require(flags and Int.MIN_VALUE != 0 && flags and 0x7fff_fffe == 0) {
            "invalid scrcpy session flags"
        }
        require(
            width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION &&
                width.toLong() * height.toLong() <= MAX_PIXELS,
        ) {
            "invalid scrcpy session dimensions"
        }
        return ScrcpySessionDimensions(width, height)
    }

    private fun ScreenMirrorCodec.wireId(): Int = when (this) {
        ScreenMirrorCodec.H264 -> 0x68_32_36_34
        ScreenMirrorCodec.H265 -> 0x68_32_36_35
        ScreenMirrorCodec.AV1 -> 0x00_61_76_31
    }

    private const val MAX_DIMENSION = 8192
    private const val MAX_PIXELS = 16_000_000L
}
