package net.extrawdw.notisync.peer.transport

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.LinkedHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.ScreenRelayChannel
import net.extrawdw.notisync.protocol.ScreenRelayRole
import net.extrawdw.notisync.protocol.ScreenRelaySignal
import net.extrawdw.notisync.protocol.ScreenRelaySignalKind
import net.extrawdw.notisync.protocol.ScreenRelayVideoWire
import okhttp3.WebSocket
import okio.ByteString.Companion.toByteString

/**
 * One authenticated broker Relay channel.
 *
 * CONTROL retains a small blocking ordered stream for the nested PSK-TLS session. VIDEO deliberately
 * preserves WebSocket message boundaries: the app applies per-fragment E2E AEAD and the source keeps
 * only a bounded number of bytes in flight until the requester acknowledges actual consumption.
 */
class BrokerRelayConnection internal constructor(
    private val channel: ScreenRelayChannel,
    private val role: ScreenRelayRole,
    private val stallTimeoutNanos: Long = DEFAULT_STALL_TIMEOUT_NANOS,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val inbound = PipedInputStream(CONTROL_PIPE_BUFFER_BYTES)
    private val inboundSink = PipedOutputStream(inbound)
    private val videoFrames = ArrayBlockingQueue<ByteArray>(VIDEO_INBOUND_FRAME_CAPACITY)
    private val flowLock = Object()
    private val inFlightRecords = LinkedHashMap<Long, Int>()
    private var inFlightBytes = 0L
    private var lastSentSequence = -1L
    private var lastReleasedSequence = -1L
    private var receivedVideoBytes = 0L
    private var congestionPending = false

    @Volatile
    private var webSocket: WebSocket? = null

    val input: InputStream
        get() {
            check(channel == ScreenRelayChannel.CONTROL) { "Relay VIDEO is message framed" }
            return inbound
        }

    val output: OutputStream = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(channel == ScreenRelayChannel.CONTROL) { "Relay VIDEO is message framed" }
            if (length == 0) return
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
            var cursor = offset
            var remaining = length
            while (remaining > 0) {
                val count = minOf(remaining, CONTROL_FRAME_BYTES)
                sendBinary(bytes, cursor, count, CONTROL_MAX_QUEUED_BYTES)
                cursor += count
                remaining -= count
            }
        }

        /**
         * Preserve the latency/backpressure contract of the nested TLS stream. OkHttp's
         * [WebSocket.send] only enqueues a frame, so inheriting OutputStream's no-op flush lets
         * touch records build up behind the app's MOVE coalescing boundary. Waiting for the local
         * WebSocket writer to empty its queue keeps at most one stale touch in flight without
         * waiting for a network acknowledgement.
         */
        override fun flush() {
            check(channel == ScreenRelayChannel.CONTROL) { "Relay VIDEO is message framed" }
            awaitWebSocketQueueDrained()
        }

        override fun close() = this@BrokerRelayConnection.close()
    }

    internal fun attach(socket: WebSocket) {
        if (closed.get()) socket.cancel() else webSocket = socket
    }

    internal fun receive(bytes: ByteArray) {
        if (closed.get()) return
        try {
            if (channel == ScreenRelayChannel.VIDEO) {
                receiveVideoFrame(bytes)
            } else {
                inboundSink.write(bytes)
                // PipedInputStream waits in one-second intervals and write() alone does not notify
                // a reader sleeping on an empty pipe. Tiny CONTROL frames therefore need this
                // explicit wake-up; VIDEO uses ArrayBlockingQueue and already signals its reader.
                inboundSink.flush()
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!closed.get()) throw IOException("broker relay receive interrupted", interrupted)
        } catch (error: IOException) {
            if (!closed.get()) throw error
        }
    }

    /** Never park OkHttp's WebSocket reader indefinitely behind a stalled decoder. */
    private fun receiveVideoFrame(bytes: ByteArray) {
        if (videoFrames.offer(bytes)) return
        val accepted = try {
            videoFrames.offer(
                bytes,
                minOf(stallTimeoutNanos, VIDEO_RECEIVE_STALL_TIMEOUT_NANOS),
                TimeUnit.NANOSECONDS,
            )
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("broker relay video receive interrupted", interrupted)
        }
        if (!accepted) throw IOException("broker relay video decoder stalled")
    }

    internal fun receiveSignal(signal: ScreenRelaySignal) {
        if (channel != ScreenRelayChannel.VIDEO || role != ScreenRelayRole.SOURCE) return
        val sequence = signal.sequence ?: return
        if (sequence < 0) return
        synchronized(flowLock) {
            if (sequence > lastSentSequence) return
            when (signal.kind) {
                ScreenRelaySignalKind.VIDEO_ACK -> releaseThroughLocked(sequence)
                ScreenRelaySignalKind.VIDEO_CONGESTED -> {
                    releaseThroughLocked(sequence)
                    congestionPending = true
                }
                else -> return
            }
            flowLock.notifyAll()
        }
    }

    /** Reserve an end-to-end delivery window before enqueuing any fragment of a complete record. */
    @Throws(IOException::class)
    fun beginVideoRecord(sequence: Long, recordBytes: Int) {
        check(channel == ScreenRelayChannel.VIDEO && role == ScreenRelayRole.SOURCE)
        require(sequence >= 0 && recordBytes in 1..ScreenRelayVideoWire.MAX_RECORD_BYTES)
        synchronized(flowLock) {
            require(sequence > lastSentSequence) { "Relay video sequence must increase" }
            val deadline = System.nanoTime() + stallTimeoutNanos
            while (!closed.get() && inFlightBytes > 0 &&
                inFlightBytes + recordBytes > VIDEO_MAX_IN_FLIGHT_BYTES
            ) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) {
                    throw IOException("broker relay video delivery stalled")
                }
                try {
                    flowLock.wait(
                        minOf(
                            TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L),
                            DELIVERY_WAIT_POLL_MILLIS,
                        ),
                    )
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("broker relay delivery wait interrupted", interrupted)
                }
            }
            if (closed.get()) throw IOException("broker relay is closed")
            inFlightRecords[sequence] = recordBytes
            inFlightBytes += recordBytes
            lastSentSequence = sequence
        }
    }

    fun abortVideoRecord(sequence: Long) = synchronized(flowLock) {
        inFlightRecords.remove(sequence)?.let { inFlightBytes -= it }
        flowLock.notifyAll()
    }

    @Throws(IOException::class)
    fun sendVideoFrame(bytes: ByteArray) {
        check(channel == ScreenRelayChannel.VIDEO && role == ScreenRelayRole.SOURCE)
        require(bytes.size in 1..MAX_FRAME_BYTES)
        sendBinary(bytes, 0, bytes.size, VIDEO_MAX_LOCAL_QUEUED_BYTES)
    }

    /** Blocks until one complete WebSocket message is available to the requester-side decryptor. */
    @Throws(IOException::class)
    fun takeVideoFrame(): ByteArray {
        check(channel == ScreenRelayChannel.VIDEO && role == ScreenRelayRole.REQUESTER)
        while (true) {
            if (closed.get() && videoFrames.isEmpty()) throw IOException("broker relay is closed")
            val frame = try {
                videoFrames.take()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("broker relay receive interrupted", interrupted)
            }
            if (frame.isEmpty() && closed.get()) throw IOException("broker relay is closed")
            if (frame.isNotEmpty()) return frame
        }
    }

    /** Acknowledge after the decrypted record has actually been consumed by the video parser. */
    @Throws(IOException::class)
    fun acknowledgeVideoRecord(sequence: Long, deliveredBytes: Int) {
        check(channel == ScreenRelayChannel.VIDEO && role == ScreenRelayRole.REQUESTER)
        require(sequence >= 0 && deliveredBytes >= 0)
        receivedVideoBytes += deliveredBytes
        val signal = ScreenRelaySignal(
            kind = ScreenRelaySignalKind.VIDEO_ACK,
            sequence = sequence,
            deliveredBytes = receivedVideoBytes,
        )
        val socket = webSocket ?: throw IOException("broker relay is not attached")
        if (closed.get() || !socket.send(ProtocolCodec.encodeToJson(signal))) {
            throw IOException("broker relay rejected video acknowledgement")
        }
    }

    fun consumeVideoCongestion(): Boolean = synchronized(flowLock) {
        congestionPending.also { congestionPending = false }
    }

    internal fun terminate() = closeInternal(SocketClose.NONE)

    /** Normal session teardown preserves all WebSocket frames already queued ahead of CLOSE. */
    override fun close() = closeInternal(SocketClose.GRACEFUL)

    /** Failed registration/authentication has no established stream worth draining. */
    internal fun abort() = closeInternal(SocketClose.ABORT)

    private fun sendBinary(bytes: ByteArray, offset: Int, count: Int, maximumQueuedBytes: Long) {
        val socket = awaitWritableSocket(count, maximumQueuedBytes)
        if (!socket.send(bytes.toByteString(offset, count))) {
            throw IOException("broker relay WebSocket rejected data")
        }
    }

    private fun awaitWebSocketQueueDrained() {
        val deadline = System.nanoTime() + stallTimeoutNanos
        while (true) {
            if (closed.get()) throw IOException("broker relay is closed")
            val socket = webSocket ?: throw IOException("broker relay is not attached")
            if (socket.queueSize() == 0L) return
            if (System.nanoTime() >= deadline) {
                throw IOException("broker relay WebSocket flush stalled")
            }
            sleepForQueueProgress("broker relay flush interrupted")
        }
    }

    private fun awaitWritableSocket(nextFrameBytes: Int, maximumQueuedBytes: Long): WebSocket {
        val deadline = System.nanoTime() + stallTimeoutNanos
        while (true) {
            if (closed.get()) throw IOException("broker relay is closed")
            val socket = webSocket ?: throw IOException("broker relay is not attached")
            if (socket.queueSize() <= maximumQueuedBytes - nextFrameBytes) return socket
            if (System.nanoTime() >= deadline) {
                throw IOException("broker relay WebSocket write stalled")
            }
            sleepForQueueProgress("broker relay write interrupted")
        }
    }

    private fun sleepForQueueProgress(message: String) {
        try {
            Thread.sleep(QUEUE_POLL_MILLIS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException(message, interrupted)
        }
    }

    private fun releaseThroughLocked(sequence: Long) {
        if (sequence <= lastReleasedSequence) return
        val iterator = inFlightRecords.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key > sequence) break
            inFlightBytes -= entry.value
            iterator.remove()
        }
        lastReleasedSequence = sequence
    }

    private fun closeInternal(socketClose: SocketClose) {
        if (!closed.compareAndSet(false, true)) return
        synchronized(flowLock) {
            inFlightRecords.clear()
            inFlightBytes = 0
            flowLock.notifyAll()
        }
        videoFrames.clear()
        videoFrames.offer(ByteArray(0))
        runCatching { inboundSink.close() }
        runCatching { inbound.close() }
        val socket = webSocket
        webSocket = null
        when (socketClose) {
            SocketClose.NONE -> Unit
            SocketClose.ABORT -> runCatching { socket?.cancel() }
            SocketClose.GRACEFUL -> {
                val closeQueued = runCatching {
                    socket?.close(NORMAL_CLOSE_CODE, NORMAL_CLOSE_REASON) ?: true
                }.getOrDefault(false)
                if (!closeQueued) runCatching { socket?.cancel() }
            }
        }
    }

    private enum class SocketClose { NONE, GRACEFUL, ABORT }

    companion object {
        const val MAX_FRAME_BYTES = ScreenRelayVideoWire.MAX_MESSAGE_BYTES
        private const val CONTROL_FRAME_BYTES = 2 * 1024
        private const val CONTROL_PIPE_BUFFER_BYTES = 8 * 1024
        private const val VIDEO_INBOUND_FRAME_CAPACITY = 8
        private const val VIDEO_MAX_IN_FLIGHT_BYTES = 256L * 1024
        private const val VIDEO_MAX_LOCAL_QUEUED_BYTES = 2L * MAX_FRAME_BYTES
        private const val CONTROL_MAX_QUEUED_BYTES = 2L * CONTROL_FRAME_BYTES
        private const val QUEUE_POLL_MILLIS = 2L
        private const val DELIVERY_WAIT_POLL_MILLIS = 1_000L
        private const val NORMAL_CLOSE_CODE = 1000
        private const val NORMAL_CLOSE_REASON = "screen relay closed"
        private val VIDEO_RECEIVE_STALL_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(250)
        private val DEFAULT_STALL_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5)
    }
}
