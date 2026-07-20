package net.extrawdw.apps.notisync.screen

import java.nio.ByteBuffer
import java.nio.ByteOrder
import net.extrawdw.notisync.protocol.ScreenMirrorCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScrcpyVideoPreambleTest {
    @Test
    fun matchingCodecAndSessionRecord_areAccepted() {
        val bytes = preamble(0x68_32_36_34, Int.MIN_VALUE, 1080, 2400)

        assertEquals(
            ScrcpySessionDimensions(1080, 2400),
            ScrcpyVideoPreamble.validate(bytes, ScreenMirrorCodec.H264),
        )
    }

    @Test
    fun wrongCodecAndMediaPacketFlags_areRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ScrcpyVideoPreamble.validate(
                preamble(0x68_32_36_35, Int.MIN_VALUE, 1080, 2400),
                ScreenMirrorCodec.H264,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScrcpyVideoPreamble.validate(
                preamble(0x68_32_36_34, 0, 1080, 2400),
                ScreenMirrorCodec.H264,
            )
        }
    }

    @Test
    fun viewerDimensionAndPixelLimits_areEnforced() {
        assertEquals(
            ScrcpySessionDimensions(4000, 4000),
            ScrcpyVideoPreamble.validate(
                preamble(0x68_32_36_34, Int.MIN_VALUE, 4000, 4000),
                ScreenMirrorCodec.H264,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ScrcpyVideoPreamble.validate(
                preamble(0x68_32_36_34, Int.MIN_VALUE, 8193, 1),
                ScreenMirrorCodec.H264,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScrcpyVideoPreamble.validate(
                preamble(0x68_32_36_34, Int.MIN_VALUE, 4001, 4000),
                ScreenMirrorCodec.H264,
            )
        }
    }

    private fun preamble(codec: Int, flags: Int, width: Int, height: Int): ByteArray =
        ByteBuffer.allocate(ScrcpyVideoPreamble.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
            .putInt(codec)
            .putInt(flags)
            .putInt(width)
            .putInt(height)
            .array()
}
