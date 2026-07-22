package net.extrawdw.notisync.peer.transport

import java.util.concurrent.atomic.AtomicBoolean
import net.extrawdw.notisync.protocol.ScreenRelayChannel
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrokerRelayConnectionTest {
    @Test
    fun videoWritesAreSplitIntoSmallBoundedFrames() {
        val socket = RecordingWebSocket()
        val relay = BrokerRelayConnection(ScreenRelayChannel.VIDEO)
        relay.attach(socket)
        val bytes = ByteArray(BrokerRelayConnection.MAX_FRAME_BYTES * 2 + 7) { it.toByte() }

        relay.output.write(bytes)
        relay.close()

        assertEquals(listOf(16 * 1024, 16 * 1024, 7), socket.frames.map { frame -> frame.size })
        assertArrayEquals(bytes, socket.frames.flatMap { it.toByteArray().asIterable() }.toByteArray())
        assertTrue(socket.cancelled.get())
    }

    @Test
    fun inboundFramesRemainAnOrderedByteStream() {
        val relay = BrokerRelayConnection(ScreenRelayChannel.CONTROL)
        relay.attach(RecordingWebSocket())

        relay.receive(byteArrayOf(1, 2))
        relay.receive(byteArrayOf(3, 4))

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), relay.input.readNBytes(4))
        relay.close()
    }

    private class RecordingWebSocket : WebSocket {
        val frames = mutableListOf<ByteString>()
        val cancelled = AtomicBoolean()

        override fun request(): Request = Request.Builder().url("https://broker.invalid/v1/screen-relay").build()
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = frames.add(bytes)
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() {
            cancelled.set(true)
        }
    }
}
