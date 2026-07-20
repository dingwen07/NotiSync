package net.extrawdw.apps.notisync.screen

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import net.extrawdw.notisync.protocol.ScreenMirrorCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidScreenVideoDecoderTest {
    @Test
    fun detachedDecoderConsumesWholeStreamAndTracksSessionChanges() {
        val input = TrackingInputStream(
            stream(
                sessionRecord(2400, 1080),
                packetRecord(CONFIG_FLAG, byteArrayOf(1, 2, 3)),
                packetRecord(KEY_FRAME_FLAG or 10L, byteArrayOf(4, 5, 6)),
                packetRecord(11L, byteArrayOf(7, 8, 9)),
            ),
        )
        val dimensions = mutableListOf<ScrcpySessionDimensions>()
        val decoder = AndroidScreenVideoDecoder(
            input = input,
            expectedCodec = ScreenMirrorCodec.H264,
            onDimensionsChanged = dimensions::add,
        )

        decoder.decode()

        assertEquals(
            listOf(
                ScrcpySessionDimensions(1080, 2400),
                ScrcpySessionDimensions(2400, 1080),
            ),
            dimensions,
        )
        assertEquals(0, input.available())
        assertTrue(input.closed)
    }

    @Test
    fun detachedDecoderStillRejectsMalformedAuthenticatedVideo() {
        val decoder = AndroidScreenVideoDecoder(
            input = ByteArrayInputStream(
                preamble(codec = H265_WIRE_ID, width = 1080, height = 2400),
            ),
            expectedCodec = ScreenMirrorCodec.H264,
        )

        assertThrows(IllegalArgumentException::class.java) { decoder.decode() }
    }

    @Test
    fun closeUnblocksDetachedParserWithoutReportingFailure() {
        val input = PreambleThenBlockingInputStream(preamble(H264_WIRE_ID, 1080, 2400))
        val dimensionsRead = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val decoder = AndroidScreenVideoDecoder(
            input = input,
            expectedCodec = ScreenMirrorCodec.H264,
            onDimensionsChanged = { dimensionsRead.countDown() },
        )
        val decodeThread = Thread {
            runCatching(decoder::decode).onFailure(failure::set)
        }
        decodeThread.start()

        assertTrue(dimensionsRead.await(2, TimeUnit.SECONDS))
        decoder.close()
        decodeThread.join(2_000)

        assertFalse(decodeThread.isAlive)
        assertEquals(null, failure.get())
        assertTrue(input.closed)
    }

    private fun stream(vararg records: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(preamble(H264_WIRE_ID, 1080, 2400))
        records.forEach(::write)
    }.toByteArray()

    private fun preamble(codec: Int, width: Int, height: Int): ByteArray =
        ByteBuffer.allocate(ScrcpyVideoPreamble.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
            .putInt(codec)
            .putInt(Int.MIN_VALUE)
            .putInt(width)
            .putInt(height)
            .array()

    private fun sessionRecord(width: Int, height: Int): ByteArray =
        ByteBuffer.allocate(ScrcpyVideoStreamReader.RECORD_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
            .putInt(Int.MIN_VALUE)
            .putInt(width)
            .putInt(height)
            .array()

    private fun packetRecord(flagsAndPts: Long, data: ByteArray): ByteArray =
        ByteBuffer.allocate(ScrcpyVideoStreamReader.RECORD_HEADER_BYTES + data.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(flagsAndPts)
            .putInt(data.size)
            .put(data)
            .array()

    private class TrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class PreambleThenBlockingInputStream(
        private val prefix: ByteArray,
    ) : InputStream() {
        private var offset = 0
        private val lock = Object()

        @Volatile
        var closed = false
            private set

        override fun read(): Int {
            val target = ByteArray(1)
            return if (read(target, 0, 1) < 0) -1 else target[0].toInt() and 0xff
        }

        override fun read(target: ByteArray, targetOffset: Int, length: Int): Int {
            synchronized(lock) {
                if (offset < prefix.size) {
                    val count = minOf(length, prefix.size - offset)
                    prefix.copyInto(target, targetOffset, offset, offset + count)
                    offset += count
                    return count
                }
                while (!closed) lock.wait()
                return -1
            }
        }

        override fun close() {
            synchronized(lock) {
                closed = true
                lock.notifyAll()
            }
        }
    }

    private companion object {
        const val H264_WIRE_ID = 0x68_32_36_34
        const val H265_WIRE_ID = 0x68_32_36_35
        const val CONFIG_FLAG = 1L shl 62
        const val KEY_FRAME_FLAG = 1L shl 61
    }
}
