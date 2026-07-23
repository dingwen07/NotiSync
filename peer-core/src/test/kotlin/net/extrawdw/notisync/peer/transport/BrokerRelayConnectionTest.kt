package net.extrawdw.notisync.peer.transport

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.ScreenRelayChannel
import net.extrawdw.notisync.protocol.ScreenRelayRole
import net.extrawdw.notisync.protocol.ScreenRelaySignal
import net.extrawdw.notisync.protocol.ScreenRelaySignalKind
import net.extrawdw.notisync.protocol.ScreenRelayVideoWire
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BrokerRelayConnectionTest {
    @Test
    fun controlWritesAreSplitIntoSmallBoundedFrames() {
        val socket = RecordingWebSocket()
        val relay = BrokerRelayConnection(ScreenRelayChannel.CONTROL, ScreenRelayRole.SOURCE)
        relay.attach(socket)
        val bytes = ByteArray(4 * 1024 + 7) { it.toByte() }

        relay.output.write(bytes)
        relay.close()

        assertEquals(listOf(2 * 1024, 2 * 1024, 7), socket.frames.map { it.size })
        assertArrayEquals(bytes, socket.frames.flatMap { it.toByteArray().asIterable() }.toByteArray())
        assertEquals(1000, socket.closeCode)
        assertFalse(socket.cancelled.get())
    }

    @Test
    fun controlFlushWaitsForLocalWebSocketWriterToDrain() {
        val socket = RecordingWebSocket(queuedBytes = 64)
        val relay = BrokerRelayConnection(ScreenRelayChannel.CONTROL, ScreenRelayRole.REQUESTER)
        relay.attach(socket)
        val flushStarted = CountDownLatch(1)
        val flushCompleted = CountDownLatch(1)
        val worker = Thread {
            flushStarted.countDown()
            relay.output.flush()
            flushCompleted.countDown()
        }.apply { start() }

        try {
            assertTrue(flushStarted.await(1, TimeUnit.SECONDS))
            assertFalse(flushCompleted.await(50, TimeUnit.MILLISECONDS))

            socket.queuedBytes.set(0)
            assertTrue(flushCompleted.await(1, TimeUnit.SECONDS))
        } finally {
            relay.close()
            worker.join(1_000)
        }
    }

    @Test
    fun inboundControlFrameImmediatelyWakesSleepingTlsReader() {
        val relay = BrokerRelayConnection(ScreenRelayChannel.CONTROL, ScreenRelayRole.SOURCE)
        relay.attach(RecordingWebSocket())
        val readStarted = CountDownLatch(1)
        val readCompleted = CountDownLatch(1)
        val readValue = AtomicInteger(-1)
        val reader = Thread {
            readStarted.countDown()
            readValue.set(relay.input.read())
            readCompleted.countDown()
        }.apply { start() }

        try {
            assertTrue(readStarted.await(1, TimeUnit.SECONDS))
            assertFalse(readCompleted.await(50, TimeUnit.MILLISECONDS))

            relay.receive(byteArrayOf(42))

            assertTrue(
                "control reader remained asleep after relay delivery",
                readCompleted.await(250, TimeUnit.MILLISECONDS),
            )
            assertEquals(42, readValue.get())
        } finally {
            relay.close()
            reader.join(1_000)
        }
    }

    @Test
    fun inboundVideoPreservesWebSocketMessageBoundaries() {
        val relay = BrokerRelayConnection(ScreenRelayChannel.VIDEO, ScreenRelayRole.REQUESTER)
        relay.attach(RecordingWebSocket())

        relay.receive(byteArrayOf(1, 2))
        relay.receive(byteArrayOf(3, 4))

        assertArrayEquals(byteArrayOf(1, 2), relay.takeVideoFrame())
        assertArrayEquals(byteArrayOf(3, 4), relay.takeVideoFrame())
        relay.close()
    }

    @Test
    fun inboundVideoFailsFastInsteadOfBlockingWebSocketReaderWhenDecoderStalls() {
        val relay = BrokerRelayConnection(
            ScreenRelayChannel.VIDEO,
            ScreenRelayRole.REQUESTER,
            TimeUnit.MILLISECONDS.toNanos(10),
        )
        relay.attach(RecordingWebSocket())
        repeat(8) { sequence -> relay.receive(videoFrame(ScreenRelayVideoWire.FLAG_DELTA, sequence.toLong())) }

        assertThrows(IOException::class.java) {
            relay.receive(videoFrame(ScreenRelayVideoWire.FLAG_DELTA, 8))
        }
        relay.close()
    }

    @Test
    fun sourceWaitsForRequesterDeliveryAcknowledgement() {
        val relay = BrokerRelayConnection(ScreenRelayChannel.VIDEO, ScreenRelayRole.SOURCE)
        relay.attach(RecordingWebSocket())
        relay.beginVideoRecord(0, 200 * 1024)
        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        Thread {
            started.countDown()
            relay.beginVideoRecord(1, 100 * 1024)
            completed.countDown()
        }.start()

        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertFalse(completed.await(100, TimeUnit.MILLISECONDS))
        relay.receiveSignal(
            ScreenRelaySignal(
                kind = ScreenRelaySignalKind.VIDEO_ACK,
                sequence = 0,
                deliveredBytes = 200L * 1024,
            ),
        )

        assertTrue(completed.await(1, TimeUnit.SECONDS))
        relay.close()
    }

    @Test
    fun requesterAcknowledgementCarriesCumulativeConsumedBytes() {
        val socket = RecordingWebSocket()
        val relay = BrokerRelayConnection(ScreenRelayChannel.VIDEO, ScreenRelayRole.REQUESTER)
        relay.attach(socket)

        relay.acknowledgeVideoRecord(3, 120)
        relay.acknowledgeVideoRecord(4, 80)

        val last = ProtocolCodec.decodeFromJson<ScreenRelaySignal>(socket.textFrames.last())
        assertEquals(ScreenRelaySignalKind.VIDEO_ACK, last.kind)
        assertEquals(4L, last.sequence)
        assertEquals(200L, last.deliveredBytes)
        relay.close()
    }

    @Test
    fun sourceFailsWhenRequesterStopsConsumingVideo() {
        val relay = BrokerRelayConnection(
            ScreenRelayChannel.VIDEO,
            ScreenRelayRole.SOURCE,
            TimeUnit.MILLISECONDS.toNanos(10),
        )
        relay.attach(RecordingWebSocket())
        relay.beginVideoRecord(0, 200 * 1024)

        assertThrows(IOException::class.java) {
            relay.beginVideoRecord(1, 100 * 1024)
        }
        relay.close()
    }

    @Test
    fun sourceFailsWhenWebSocketSendQueueStopsDraining() {
        val relay = BrokerRelayConnection(
            ScreenRelayChannel.VIDEO,
            ScreenRelayRole.SOURCE,
            TimeUnit.MILLISECONDS.toNanos(10),
        )
        relay.attach(RecordingWebSocket(queuedBytes = Long.MAX_VALUE))

        assertThrows(IOException::class.java) {
            relay.sendVideoFrame(byteArrayOf(1))
        }
        relay.close()
    }

    private fun videoFrame(flags: Int, sequence: Long): ByteArray =
        ScreenRelayVideoWire.encodeHeader(
            flags = flags,
            recordSequence = sequence,
            recordBytes = 1,
            fragmentOffset = 0,
            fragmentBytes = 1,
        ) + ByteArray(1 + ScreenRelayVideoWire.AEAD_TAG_BYTES)

    private class RecordingWebSocket(queuedBytes: Long = 0L) : WebSocket {
        val frames = Collections.synchronizedList(mutableListOf<ByteString>())
        val textFrames = Collections.synchronizedList(mutableListOf<String>())
        val cancelled = AtomicBoolean()
        val queuedBytes = AtomicLong(queuedBytes)
        var closeCode: Int? = null

        override fun request(): Request = Request.Builder().url("https://broker.invalid/v1/screen-relay").build()
        override fun queueSize(): Long = queuedBytes.get()
        override fun send(text: String): Boolean = textFrames.add(text)
        override fun send(bytes: ByteString): Boolean = frames.add(bytes)
        override fun close(code: Int, reason: String?): Boolean {
            closeCode = code
            return true
        }
        override fun cancel() {
            cancelled.set(true)
        }
    }
}
