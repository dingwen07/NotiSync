package net.extrawdw.notisync.peer.transport

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.protocol.ScreenRelayChannel

/** Blocking ordered byte-stream facade over one authenticated broker-relay WebSocket. */
class BrokerRelayConnection internal constructor(channel: ScreenRelayChannel) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val inbound = PipedInputStream(
        if (channel == ScreenRelayChannel.VIDEO) VIDEO_PIPE_BUFFER_BYTES else CONTROL_PIPE_BUFFER_BYTES,
    )
    private val inboundSink = PipedOutputStream(inbound)
    private val outbound = Channel<ByteArray>(
        if (channel == ScreenRelayChannel.VIDEO) VIDEO_OUTBOUND_FRAMES else CONTROL_OUTBOUND_FRAMES,
    )

    @Volatile
    private var job: Job? = null

    val input: InputStream = inbound
    val output: OutputStream = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length == 0) return
            if (closed.get()) throw IOException("broker relay is closed")
            var cursor = offset
            var remaining = length
            while (remaining > 0) {
                val count = minOf(remaining, MAX_FRAME_BYTES)
                val frame = bytes.copyOfRange(cursor, cursor + count)
                try {
                    runBlocking { outbound.send(frame) }
                } catch (error: Exception) {
                    throw IOException("broker relay is closed", error)
                }
                cursor += count
                remaining -= count
            }
        }

        override fun close() = this@BrokerRelayConnection.close()
    }

    internal val outgoingFrames: ReceiveChannel<ByteArray> get() = outbound

    internal fun attach(job: Job) {
        this.job = job
        if (closed.get()) job.cancel()
    }

    internal fun receive(bytes: ByteArray) {
        if (closed.get()) return
        inboundSink.write(bytes)
        inboundSink.flush()
    }

    internal fun terminate() = closeInternal(cancelJob = false)

    override fun close() = closeInternal(cancelJob = true)

    private fun closeInternal(cancelJob: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        outbound.close()
        runCatching { inboundSink.close() }
        runCatching { inbound.close() }
        if (cancelJob) job?.cancel()
    }

    companion object {
        const val MAX_FRAME_BYTES = 64 * 1024
        private const val VIDEO_PIPE_BUFFER_BYTES = 128 * 1024
        private const val CONTROL_PIPE_BUFFER_BYTES = 16 * 1024
        private const val VIDEO_OUTBOUND_FRAMES = 2
        private const val CONTROL_OUTBOUND_FRAMES = 1
    }
}
