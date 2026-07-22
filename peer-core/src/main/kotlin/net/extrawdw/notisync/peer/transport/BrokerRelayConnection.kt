package net.extrawdw.notisync.peer.transport

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import net.extrawdw.notisync.protocol.ScreenRelayChannel
import okhttp3.WebSocket
import okio.ByteString.Companion.toByteString

/** Blocking ordered stream facade with a hard bound on OkHttp's actual WebSocket send queue. */
class BrokerRelayConnection internal constructor(channel: ScreenRelayChannel) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val inbound = PipedInputStream(
        if (channel == ScreenRelayChannel.VIDEO) VIDEO_PIPE_BUFFER_BYTES else CONTROL_PIPE_BUFFER_BYTES,
    )
    private val inboundSink = PipedOutputStream(inbound)
    private val frameBytes = if (channel == ScreenRelayChannel.VIDEO) VIDEO_FRAME_BYTES else CONTROL_FRAME_BYTES
    private val maximumQueuedBytes =
        if (channel == ScreenRelayChannel.VIDEO) VIDEO_MAX_QUEUED_BYTES else CONTROL_MAX_QUEUED_BYTES

    @Volatile
    private var webSocket: WebSocket? = null

    val input: InputStream = inbound
    val output: OutputStream = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length == 0) return
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
            var cursor = offset
            var remaining = length
            while (remaining > 0) {
                val count = minOf(remaining, frameBytes)
                val socket = awaitWritableSocket(count)
                if (!socket.send(bytes.toByteString(cursor, count))) {
                    throw IOException("broker relay WebSocket rejected data")
                }
                cursor += count
                remaining -= count
            }
        }

        override fun close() = this@BrokerRelayConnection.close()
    }

    internal fun attach(socket: WebSocket) {
        if (closed.get()) {
            socket.cancel()
        } else {
            webSocket = socket
        }
    }

    internal fun receive(bytes: ByteArray) {
        if (closed.get()) return
        try {
            inboundSink.write(bytes)
        } catch (error: IOException) {
            if (!closed.get()) throw error
        }
    }

    internal fun terminate() = closeInternal(cancelSocket = false)

    override fun close() = closeInternal(cancelSocket = true)

    private fun awaitWritableSocket(nextFrameBytes: Int): WebSocket {
        while (true) {
            if (closed.get()) throw IOException("broker relay is closed")
            val socket = webSocket ?: throw IOException("broker relay is not attached")
            if (socket.queueSize() + nextFrameBytes <= maximumQueuedBytes) return socket
            try {
                Thread.sleep(QUEUE_POLL_MILLIS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("broker relay write interrupted", interrupted)
            }
        }
    }

    private fun closeInternal(cancelSocket: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        runCatching { inboundSink.close() }
        runCatching { inbound.close() }
        if (cancelSocket) runCatching { webSocket?.cancel() }
        webSocket = null
    }

    companion object {
        const val MAX_FRAME_BYTES = 16 * 1024
        private const val VIDEO_FRAME_BYTES = MAX_FRAME_BYTES
        private const val CONTROL_FRAME_BYTES = 2 * 1024
        private const val VIDEO_PIPE_BUFFER_BYTES = 64 * 1024
        private const val CONTROL_PIPE_BUFFER_BYTES = 8 * 1024
        private const val VIDEO_MAX_QUEUED_BYTES = 2L * VIDEO_FRAME_BYTES
        private const val CONTROL_MAX_QUEUED_BYTES = 2L * CONTROL_FRAME_BYTES
        private const val QUEUE_POLL_MILLIS = 2L
    }
}
