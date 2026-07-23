package net.extrawdw.apps.notisync.screen

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import net.extrawdw.notisync.protocol.ScreenMirrorCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrcpyVideoStreamReaderTest {
    @Test
    fun fragmentedStream_readsPreambleSessionAndPacketsIncrementally() {
        val config = byteArrayOf(0, 0, 0, 1, 0x67)
        val keyFrame = byteArrayOf(0, 0, 0, 1, 0x65, 0x7f)
        val stream = videoStream(
            codec = H264_WIRE_ID,
            initialWidth = 1080,
            initialHeight = 2400,
            records = listOf(
                sessionRecord(width = 2400, height = 1080, clientResize = true),
                packetRecord(CONFIG_FLAG, config),
                packetRecord(KEY_FRAME_FLAG or 123_456L, keyFrame),
            ),
        )
        val reader = ScrcpyVideoStreamReader(
            ChunkedInputStream(ByteArrayInputStream(stream), maxChunk = 2),
            ScreenMirrorCodec.H264,
        )

        assertEquals(ScrcpySessionDimensions(1080, 2400), reader.readPreamble())
        assertEquals(
            ScrcpyVideoRecord.Session(ScrcpySessionDimensions(2400, 1080), clientResize = true),
            reader.readRecord(),
        )
        val configPacket = reader.readRecord() as ScrcpyVideoRecord.Packet
        assertTrue(configPacket.codecConfig)
        assertFalse(configPacket.keyFrame)
        assertEquals(0L, configPacket.presentationTimeUs)
        assertArrayEquals(config, configPacket.data)
        val mediaPacket = reader.readRecord() as ScrcpyVideoRecord.Packet
        assertFalse(mediaPacket.codecConfig)
        assertTrue(mediaPacket.keyFrame)
        assertEquals(123_456L, mediaPacket.presentationTimeUs)
        assertArrayEquals(keyFrame, mediaPacket.data)
        assertNull(reader.readRecord())
    }

    @Test
    fun readOrderAndSinglePreamble_areEnforced() {
        val bytes = videoStream(H264_WIRE_ID, 1080, 2400, emptyList())
        val beforePreamble = ScrcpyVideoStreamReader(ByteArrayInputStream(bytes), ScreenMirrorCodec.H264)
        assertThrows(IllegalStateException::class.java) { beforePreamble.readRecord() }

        val duplicatePreamble = ScrcpyVideoStreamReader(ByteArrayInputStream(bytes), ScreenMirrorCodec.H264)
        duplicatePreamble.readPreamble()
        assertThrows(IllegalStateException::class.java) { duplicatePreamble.readPreamble() }
    }

    @Test
    fun malformedSessionFlagsAndDimensions_areRejectedBeforePacketAllocation() {
        val reservedFlag = rawRecord(Int.MIN_VALUE or 2, 1080, 2400)
        val excessivePixels = sessionRecord(4001, 4000)

        assertMalformedRecord(reservedFlag)
        assertMalformedRecord(excessivePixels)
    }

    @Test
    fun zeroAndOversizedPacketLengths_areRejected() {
        assertMalformedRecord(packetHeader(0L, 0))
        assertMalformedRecord(
            packetHeader(0L, ScrcpyVideoStreamReader.MAX_PACKET_SIZE_BYTES + 1),
        )
    }

    @Test
    fun codecConfigMustNotCarryPtsOrKeyFlag() {
        assertMalformedRecord(packetRecord(CONFIG_FLAG or 7L, byteArrayOf(1)))
        assertMalformedRecord(packetRecord(CONFIG_FLAG or KEY_FRAME_FLAG, byteArrayOf(1)))
    }

    @Test
    fun truncatedPreambleHeaderAndPayload_areRejected() {
        val preamble = videoStream(H264_WIRE_ID, 1080, 2400, emptyList())
        assertThrows(EOFException::class.java) {
            ScrcpyVideoStreamReader(
                ByteArrayInputStream(preamble.copyOf(preamble.size - 1)),
                ScreenMirrorCodec.H264,
            ).readPreamble()
        }

        val truncatedHeader = preamble + packetHeader(1L, 1).copyOf(7)
        val headerReader = ScrcpyVideoStreamReader(ByteArrayInputStream(truncatedHeader), ScreenMirrorCodec.H264)
        headerReader.readPreamble()
        assertThrows(EOFException::class.java) { headerReader.readRecord() }

        val truncatedPayload = preamble + packetHeader(1L, 4) + byteArrayOf(1, 2, 3)
        val payloadReader = ScrcpyVideoStreamReader(ByteArrayInputStream(truncatedPayload), ScreenMirrorCodec.H264)
        payloadReader.readPreamble()
        assertThrows(EOFException::class.java) { payloadReader.readRecord() }
    }

    @Test
    fun expectedCodecIsBoundToAuthenticatedSelection() {
        val bytes = videoStream(H265_WIRE_ID, 1080, 2400, emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            ScrcpyVideoStreamReader(ByteArrayInputStream(bytes), ScreenMirrorCodec.H264).readPreamble()
        }
    }

    @Test
    fun androidMimeMapping_coversAllProtocolCodecs() {
        assertEquals("video/avc", AndroidScreenVideoDecoder.mimeFor(ScreenMirrorCodec.H264))
        assertEquals("video/hevc", AndroidScreenVideoDecoder.mimeFor(ScreenMirrorCodec.H265))
        assertEquals("video/av01", AndroidScreenVideoDecoder.mimeFor(ScreenMirrorCodec.AV1))
    }

    private fun assertMalformedRecord(record: ByteArray) {
        val bytes = videoStream(H264_WIRE_ID, 1080, 2400, listOf(record))
        val reader = ScrcpyVideoStreamReader(ByteArrayInputStream(bytes), ScreenMirrorCodec.H264)
        reader.readPreamble()
        assertThrows(IllegalArgumentException::class.java) { reader.readRecord() }
    }

    private fun videoStream(
        codec: Int,
        initialWidth: Int,
        initialHeight: Int,
        records: List<ByteArray>,
    ): ByteArray = ByteArrayOutputStream().apply {
        write(rawRecord(codec, Int.MIN_VALUE, initialWidth, initialHeight))
        records.forEach(::write)
    }.toByteArray()

    private fun sessionRecord(width: Int, height: Int, clientResize: Boolean = false): ByteArray =
        rawRecord(Int.MIN_VALUE or if (clientResize) 1 else 0, width, height)

    private fun packetRecord(ptsAndFlags: Long, payload: ByteArray): ByteArray =
        packetHeader(ptsAndFlags, payload.size) + payload

    private fun packetHeader(ptsAndFlags: Long, size: Int): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .putLong(ptsAndFlags)
            .putInt(size)
            .array()

    private fun rawRecord(first: Int, second: Int, third: Int): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .putInt(first)
            .putInt(second)
            .putInt(third)
            .array()

    private fun rawRecord(codec: Int, flags: Int, width: Int, height: Int): ByteArray =
        ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putInt(codec)
            .putInt(flags)
            .putInt(width)
            .putInt(height)
            .array()

    private class ChunkedInputStream(
        private val delegate: InputStream,
        private val maxChunk: Int,
    ) : InputStream() {
        override fun read(): Int = delegate.read()

        override fun read(target: ByteArray, offset: Int, length: Int): Int =
            delegate.read(target, offset, minOf(length, maxChunk))

        override fun close() = delegate.close()
    }

    companion object {
        private const val H264_WIRE_ID = 0x68_32_36_34
        private const val H265_WIRE_ID = 0x68_32_36_35
        private const val CONFIG_FLAG = 1L shl 62
        private const val KEY_FRAME_FLAG = 1L shl 61
    }
}
