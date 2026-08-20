package net.extrawdw.apps.notisync.data.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityRenderArgsCodecTest {
    @Test
    fun v1RoundTripIsCanonicalAndBounded() {
        val args = ActivityRenderArgs.V1(count = 12, revision = 44L, durationMillis = 900L)
        val encoded = ActivityRenderArgsCodec.encode(args)

        assertTrue(encoded.size <= ActivityLimits.MAX_RENDER_ARGS_BYTES)
        assertEquals(args, ActivityRenderArgsCodec.decode(ActivityRenderArgsCodec.CURRENT_VERSION, encoded))
    }

    @Test
    fun oversizedBytesAreNotRetainedOrDecoded() {
        val result = ActivityRenderArgsCodec.decode(
            ActivityRenderArgsCodec.CURRENT_VERSION,
            ByteArray(ActivityLimits.MAX_RENDER_ARGS_BYTES + 1) { 7 },
        )

        assertEquals(
            ActivityRenderArgs.Corrupt(
                ActivityRenderArgsCodec.CURRENT_VERSION,
                ActivityRenderArgs.CorruptReason.OVERSIZE,
            ),
            result,
        )
    }

    @Test
    fun malformedAndUnknownVersionsFailClosed() {
        assertEquals(
            ActivityRenderArgs.Corrupt(
                ActivityRenderArgsCodec.CURRENT_VERSION,
                ActivityRenderArgs.CorruptReason.MALFORMED,
            ),
            ActivityRenderArgsCodec.decode(ActivityRenderArgsCodec.CURRENT_VERSION, byteArrayOf(1, 2)),
        )
        assertEquals(
            ActivityRenderArgs.Unsupported(7),
            ActivityRenderArgsCodec.decode(7, ActivityRenderArgsCodec.encode(ActivityRenderArgs.V1())),
        )
    }
}
