package net.extrawdw.notisync.server.delivery

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.extrawdw.notisync.protocol.ScreenRelayVideoWire

internal data class ScreenRelayVideoEnqueueResult(
    /** Highest source record made obsolete by this enqueue. */
    val congestedThroughSequence: Long? = null,
)

/**
 * Small TCP-facing broker queue. Predictive fragments are disposable; session/config/key material
 * applies backpressure instead. Once congestion drops any predictive data, no more delta records are
 * accepted until a fresh key frame begins.
 */
internal class ScreenRelayVideoQueue(
    private val maximumQueuedBytes: Int = DEFAULT_MAXIMUM_QUEUED_BYTES,
) : AutoCloseable {
    private data class Packet(val bytes: ByteArray, val predictive: Boolean, val sequence: Long)

    private val closed = AtomicBoolean(false)
    private val mutex = Mutex()
    private val packets = ArrayDeque<Packet>()
    private val itemReady = Channel<Unit>(Channel.CONFLATED)
    private val spaceReady = Channel<Unit>(Channel.CONFLATED)
    private var queuedBytes = 0
    private var droppingUntilKeyFrame = false
    private var lastDiscardSignalledSequence = -1L

    init {
        require(maximumQueuedBytes >= ScreenRelayVideoWire.MAX_MESSAGE_BYTES)
    }

    suspend fun enqueue(bytes: ByteArray): ScreenRelayVideoEnqueueResult {
        val header = ScreenRelayVideoWire.decodeHeader(bytes)
            ?: throw IOException("invalid Relay video fragment")
        val packet = Packet(bytes.copyOf(), header.predictive, header.recordSequence)
        while (true) {
            var waitForSpace = false
            var completed: ScreenRelayVideoEnqueueResult? = null
            mutex.withLock {
                if (closed.get()) throw IOException("Relay video queue is closed")
                if (header.keyFrame && header.firstFragment) {
                    droppingUntilKeyFrame = false
                    lastDiscardSignalledSequence = -1L
                }
                when {
                    droppingUntilKeyFrame && packet.predictive -> {
                        completed = ScreenRelayVideoEnqueueResult(
                            packet.sequence.takeUnless { it == lastDiscardSignalledSequence },
                        )
                        lastDiscardSignalledSequence = packet.sequence
                    }
                    queuedBytes + packet.bytes.size <= maximumQueuedBytes -> {
                        packets.addLast(packet)
                        queuedBytes += packet.bytes.size
                        itemReady.trySend(Unit)
                        completed = ScreenRelayVideoEnqueueResult()
                    }
                    packet.predictive -> {
                        var congestedThrough = packet.sequence
                        val retained = ArrayDeque<Packet>(packets.size)
                        while (packets.isNotEmpty()) {
                            val queued = packets.removeFirst()
                            if (queued.predictive) {
                                queuedBytes -= queued.bytes.size
                                congestedThrough = maxOf(congestedThrough, queued.sequence)
                            } else {
                                retained.addLast(queued)
                            }
                        }
                        packets.addAll(retained)
                        droppingUntilKeyFrame = true
                        spaceReady.trySend(Unit)
                        completed = ScreenRelayVideoEnqueueResult(
                            congestedThroughSequence = congestedThrough.takeUnless {
                                it == lastDiscardSignalledSequence
                            },
                        )
                        lastDiscardSignalledSequence = congestedThrough
                    }
                    else -> waitForSpace = true
                }
            }
            completed?.let { return it }
            check(waitForSpace)
            spaceReady.receiveCatching().getOrNull()
                ?: throw IOException("Relay video queue is closed")
        }
    }

    suspend fun take(): ByteArray? {
        while (true) {
            var packet: Packet? = null
            mutex.withLock {
                if (closed.get()) return null
                if (packets.isNotEmpty()) {
                    packet = packets.removeFirst()
                    queuedBytes -= requireNotNull(packet).bytes.size
                    spaceReady.trySend(Unit)
                }
            }
            packet?.let { return it.bytes }
            itemReady.receiveCatching().getOrNull() ?: return null
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        itemReady.close()
        spaceReady.close()
    }

    private companion object {
        const val DEFAULT_MAXIMUM_QUEUED_BYTES = 256 * 1024
    }
}
