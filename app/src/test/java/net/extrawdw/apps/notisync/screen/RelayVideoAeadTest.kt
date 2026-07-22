package net.extrawdw.apps.notisync.screen

import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import net.extrawdw.notisync.protocol.ScreenRelayVideoWire
import net.extrawdw.notisync.screen.MASTER_PSK_BYTES
import net.extrawdw.notisync.screen.ROUTING_TOKEN_BYTES
import net.extrawdw.notisync.screen.SessionDescriptor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RelayVideoAeadTest {
    @Test
    fun framedTransportReconstructsExactStreamAndAcknowledgesConsumption() {
        val token = ByteArray(ROUTING_TOKEN_BYTES) { it.toByte() }
        val psk = ByteArray(MASTER_PSK_BYTES) { (it * 3).toByte() }
        val endpoint = RecordingRelayVideoEndpoint()
        val sink = RelayVideoRecordSink(endpoint, descriptor(), token, psk)
        val input = RelayVideoInputStream(endpoint, descriptor(), token, psk)
        val preamble = ByteArray(ScrcpyVideoPreamble.SIZE_BYTES) { (it + 1).toByte() }
        val header = ByteArray(ScrcpyVideoStreamReader.RECORD_HEADER_BYTES) { (it + 20).toByte() }
        val payload = ByteArray(40_000) { (it xor 0x3c).toByte() }

        sink.writePreamble(preamble)
        sink.writeRecord(
            QueuedVideoRecord(
                header = header,
                payload = payload,
                session = false,
                codecConfig = false,
                keyFrame = true,
                enqueuedAtNanos = 0,
            ),
        )

        assertArrayEquals(preamble + header + payload, input.readNBytes(preamble.size + header.size + payload.size))
        assertEquals(
            listOf(0L to preamble.size, 1L to (header.size + payload.size)),
            endpoint.acknowledgements,
        )
        input.close()
        sink.close()
    }

    @Test
    fun fragmentRoundTripsAndAuthenticatesVisibleMetadata() {
        val token = ByteArray(ROUTING_TOKEN_BYTES) { it.toByte() }
        val psk = ByteArray(MASTER_PSK_BYTES) { (it * 3).toByte() }
        val sender = RelayVideoAead(descriptor(), token, psk)
        val receiver = RelayVideoAead(descriptor(), token, psk)
        val plaintext = ByteArray(200) { (it xor 0x5a).toByte() }
        val header = ScreenRelayVideoWire.encodeHeader(
            flags = ScreenRelayVideoWire.FLAG_KEY_FRAME,
            recordSequence = 7,
            recordBytes = plaintext.size,
            fragmentOffset = 0,
            fragmentBytes = plaintext.size,
        )
        val message = header + sender.seal(header, 7, 0, plaintext)

        assertArrayEquals(
            plaintext,
            receiver.open(message, requireNotNull(ScreenRelayVideoWire.decodeHeader(message))),
        )

        val changedHeader = message.copyOf().also {
            it[6] = ScreenRelayVideoWire.FLAG_DELTA.toByte()
        }
        assertThrows(Exception::class.java) {
            receiver.open(
                changedHeader,
                requireNotNull(ScreenRelayVideoWire.decodeHeader(changedHeader)),
            )
        }
        sender.close()
        receiver.close()
    }

    private fun descriptor() = SessionDescriptor(
        sessionId = "screen:relay-crypto-test",
        sourcePeerId = "source",
        requesterPeerId = "requester",
        issuedAtEpochMillis = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(),
        expiresAtEpochMillis = Instant.parse("2026-01-01T00:05:00Z").toEpochMilli(),
        codec = "av1",
        controlEnabled = true,
        clipboardEnabled = false,
        maxDimension = 1_920,
        maxFps = 60,
        videoBitrateBps = 8_000_000,
    )

    private class RecordingRelayVideoEndpoint : RelayVideoEndpoint {
        val acknowledgements = mutableListOf<Pair<Long, Int>>()
        private val frames = LinkedBlockingQueue<ByteArray>()

        override fun beginVideoRecord(sequence: Long, recordBytes: Int) = Unit
        override fun abortVideoRecord(sequence: Long) = Unit
        override fun sendVideoFrame(bytes: ByteArray) {
            frames.put(bytes.copyOf())
        }

        override fun takeVideoFrame(): ByteArray = frames.take()
        override fun acknowledgeVideoRecord(sequence: Long, deliveredBytes: Int) {
            acknowledgements += sequence to deliveredBytes
        }

        override fun consumeVideoCongestion(): Boolean = false
        override fun close() = Unit
    }
}
