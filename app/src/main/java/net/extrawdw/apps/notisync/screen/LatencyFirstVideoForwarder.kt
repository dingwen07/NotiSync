package net.extrawdw.apps.notisync.screen

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import net.extrawdw.notisync.protocol.ScreenMirrorCodec

internal enum class VideoCongestionReason { QUEUE_FULL, QUEUE_AGE, WRITE_STALL, RECOVERY }

internal data class VideoRecoveryDecision(
    val bitrateBps: Int,
    val previousBitrateBps: Int,
    val reason: VideoCongestionReason,
)

/** Sender-side bitrate decisions driven by the real network writer instead of the encoder pipe. */
internal class AdaptiveVideoBitrateController(
    private val targetBitrateBps: Int,
) {
    init {
        require(targetBitrateBps > 0) { "target video bitrate must be positive" }
    }

    private val minimumBitrateBps = min(targetBitrateBps, MINIMUM_BITRATE_BPS)
    private var currentBitrateBps = targetBitrateBps
    private var lastCongestionNanos = Long.MIN_VALUE
    private var lastAdjustmentNanos = Long.MIN_VALUE
    private var lastRecoveryRequestNanos = Long.MIN_VALUE

    @Synchronized
    fun onCongestion(
        reason: VideoCongestionReason,
        writtenBytes: Int,
        writeDurationNanos: Long,
        nowNanos: Long,
    ): VideoRecoveryDecision? {
        lastCongestionNanos = nowNanos
        if (!elapsedAtLeast(nowNanos, lastRecoveryRequestNanos, MIN_RECOVERY_INTERVAL_NANOS)) return null
        val previous = currentBitrateBps
        if (elapsedAtLeast(nowNanos, lastAdjustmentNanos, MIN_ADJUSTMENT_INTERVAL_NANOS)) {
            val measuredBudget = if (writtenBytes > 0 && writeDurationNanos > 0) {
                ((writtenBytes.toDouble() * 8_000_000_000.0 / writeDurationNanos) * 0.8)
                    .toLong()
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            } else {
                currentBitrateBps / 2
            }
            currentBitrateBps = min(currentBitrateBps * 3 / 5, measuredBudget)
                .coerceAtLeast(minimumBitrateBps)
            lastAdjustmentNanos = nowNanos
        }
        lastRecoveryRequestNanos = nowNanos
        return VideoRecoveryDecision(currentBitrateBps, previous, reason)
    }

    @Synchronized
    fun onHealthyWrite(nowNanos: Long): VideoRecoveryDecision? {
        if (lastCongestionNanos == Long.MIN_VALUE || currentBitrateBps >= targetBitrateBps) return null
        if (!elapsedAtLeast(nowNanos, lastCongestionNanos, STABLE_RECOVERY_NANOS) ||
            !elapsedAtLeast(nowNanos, lastAdjustmentNanos, STABLE_RECOVERY_NANOS)
        ) return null
        val previous = currentBitrateBps
        currentBitrateBps = min(targetBitrateBps, maxOf(currentBitrateBps + 1, currentBitrateBps * 5 / 4))
        lastAdjustmentNanos = nowNanos
        return VideoRecoveryDecision(currentBitrateBps, previous, VideoCongestionReason.RECOVERY)
    }

    @Synchronized
    fun currentBitrateBps(): Int = currentBitrateBps

    private fun elapsedAtLeast(now: Long, then: Long, duration: Long): Boolean =
        then == Long.MIN_VALUE || now - then >= duration

    private companion object {
        const val MINIMUM_BITRATE_BPS = 256_000
        const val MIN_ADJUSTMENT_INTERVAL_NANOS = 500_000_000L
        const val MIN_RECOVERY_INTERVAL_NANOS = 500_000_000L
        const val STABLE_RECOVERY_NANOS = 15_000_000_000L
    }
}

/**
 * Parses complete encoded access units before the encrypted network writer. When that writer falls
 * behind, queued predictive frames are discarded and the privileged encoder is asked for a lower
 * bitrate sync frame. At most the frame currently inside TLS remains unavoidable.
 */
internal class LatencyFirstVideoForwarder(
    private val input: InputStream,
    private val output: OutputStream,
    private val preamble: ByteArray,
    private val codec: ScreenMirrorCodec,
    targetBitrateBps: Int,
    private val recoverVideo: (VideoRecoveryDecision) -> Boolean,
    private val nanoTime: () -> Long = System::nanoTime,
) : Closeable {
    private val queue = LatestDecodableVideoQueue(nanoTime)
    private val bitrate = AdaptiveVideoBitrateController(targetBitrateBps)
    private val recoveryLock = Any()

    fun forward() {
        val writerFailure = AtomicReference<Throwable?>()
        val writer = Thread(
            {
                try {
                    writeQueuedRecords()
                } catch (error: Throwable) {
                    writerFailure.compareAndSet(null, error)
                    queue.fail(error)
                    runCatching { input.close() }
                }
            },
            "notisync-screen-video-network",
        ).apply {
            isDaemon = true
            start()
        }

        val reader = ScrcpyVideoStreamReader(
            SequenceInputStream(ByteArrayInputStream(preamble), input),
            codec,
        )
        try {
            reader.readPreamble()
            while (true) {
                val record = reader.readRecord() ?: break
                val rejected = queue.tryEnqueue(record)
                if (rejected != null &&
                    !requestRecovery(VideoCongestionReason.QUEUE_FULL, 0, 0L)
                ) {
                    queue.enqueueBlocking(rejected)
                }
            }
            queue.finish()
        } catch (error: Throwable) {
            queue.fail(error)
            throw error
        } finally {
            runCatching { writer.join() }
        }
        writerFailure.get()?.let { throw IOException("screen video network writer failed", it) }
    }

    override fun close() {
        queue.finish()
        runCatching { input.close() }
    }

    private fun writeQueuedRecords() {
        while (true) {
            val record = queue.take() ?: break
            val now = nanoTime()
            if (record.visual && now - record.enqueuedAtNanos >= MAX_QUEUE_AGE_NANOS) {
                if (requestRecovery(VideoCongestionReason.QUEUE_AGE, 0, 0L)) continue
            }
            val started = nanoTime()
            output.write(record.header)
            if (record.payload.isNotEmpty()) output.write(record.payload)
            val completed = nanoTime()
            val duration = completed - started
            if (duration >= SLOW_WRITE_NANOS) {
                requestRecovery(VideoCongestionReason.WRITE_STALL, record.byteCount, duration)
            } else {
                bitrate.onHealthyWrite(completed)?.let(::applyRecovery)
            }
        }
        output.flush()
    }

    private fun requestRecovery(reason: VideoCongestionReason, bytes: Int, durationNanos: Long): Boolean =
        synchronized(recoveryLock) {
            val decision = bitrate.onCongestion(reason, bytes, durationNanos, nanoTime())
            decision?.let(::applyRecovery) ?: false
        }

    private fun applyRecovery(decision: VideoRecoveryDecision): Boolean {
        val accepted = recoverVideo(decision)
        if (accepted) queue.dropUntilNextKeyFrame()
        return accepted
    }

    private companion object {
        const val SLOW_WRITE_NANOS = 80_000_000L
        const val MAX_QUEUE_AGE_NANOS = 150_000_000L
    }
}

internal data class QueuedVideoRecord(
    val header: ByteArray,
    val payload: ByteArray,
    val session: Boolean,
    val codecConfig: Boolean,
    val keyFrame: Boolean,
    val enqueuedAtNanos: Long,
) {
    val visual: Boolean get() = !session && !codecConfig
    val byteCount: Int get() = header.size + payload.size

    fun freshlyQueued(nowNanos: Long): QueuedVideoRecord = copy(enqueuedAtNanos = nowNanos)
}

/** Small synchronized queue that only resumes predictive delivery from a fresh key frame. */
internal class LatestDecodableVideoQueue(
    private val nanoTime: () -> Long,
) {
    private val lock = Object()
    private val records = ArrayDeque<QueuedVideoRecord>()
    private val codecConfigs = ArrayDeque<QueuedVideoRecord>()
    private var latestSession: QueuedVideoRecord? = null
    private var queuedBytes = 0
    private var droppingUntilKeyFrame = false
    private var closed = false
    private var failure: Throwable? = null

    /** Returns the encoded record only when accepting it would exceed the latency bound. */
    fun tryEnqueue(value: ScrcpyVideoRecord): QueuedVideoRecord? = synchronized(lock) {
        failure?.let { throw IOException("screen video queue failed", it) }
        if (closed) throw IOException("screen video queue is closed")
        val record = encodeRecord(value, nanoTime())
        when {
            record.session -> {
                latestSession = record
                codecConfigs.clear()
                clearLocked()
                droppingUntilKeyFrame = false
                addLocked(record)
                null
            }
            record.codecConfig -> {
                cacheCodecConfigLocked(record)
                when {
                    droppingUntilKeyFrame -> null
                    !fitsLocked(record) -> record
                    else -> {
                        addLocked(record)
                        null
                    }
                }
            }
            droppingUntilKeyFrame && !record.keyFrame -> null
            droppingUntilKeyFrame -> {
                rebuildFromKeyFrameLocked(record)
                null
            }
            record.keyFrame && !fitsLocked(record) -> {
                rebuildFromKeyFrameLocked(record)
                null
            }
            !fitsLocked(record) -> record
            else -> {
                addLocked(record)
                null
            }
        }
    }

    fun enqueueBlocking(record: QueuedVideoRecord) = synchronized(lock) {
        while (true) {
            failure?.let { throw IOException("screen video queue failed", it) }
            if (closed) throw IOException("screen video queue is closed")
            when {
                record.codecConfig && droppingUntilKeyFrame -> return@synchronized
                droppingUntilKeyFrame && !record.keyFrame -> return@synchronized
                droppingUntilKeyFrame || record.keyFrame && !fitsLocked(record) -> {
                    rebuildFromKeyFrameLocked(record)
                    return@synchronized
                }
                fitsLocked(record) -> {
                    addLocked(record)
                    return@synchronized
                }
                else -> lock.wait()
            }
        }
    }

    fun dropUntilNextKeyFrame() = synchronized(lock) {
        if (closed) return@synchronized
        clearLocked()
        droppingUntilKeyFrame = true
    }

    fun take(): QueuedVideoRecord? = synchronized(lock) {
        while (records.isEmpty() && !closed && failure == null) lock.wait()
        failure?.let { throw IOException("screen video queue failed", it) }
        if (records.isEmpty()) return@synchronized null
        records.removeFirst().also {
            queuedBytes -= it.byteCount
            lock.notifyAll()
        }
    }

    fun finish() = synchronized(lock) {
        closed = true
        lock.notifyAll()
    }

    fun fail(error: Throwable) = synchronized(lock) {
        if (failure == null) failure = error
        closed = true
        clearLocked()
        lock.notifyAll()
    }

    private fun rebuildFromKeyFrameLocked(keyFrame: QueuedVideoRecord) {
        clearLocked()
        val now = nanoTime()
        latestSession?.freshlyQueued(now)?.let(::addLocked)
        codecConfigs.forEach { addLocked(it.freshlyQueued(now)) }
        addLocked(keyFrame.freshlyQueued(now))
        droppingUntilKeyFrame = false
    }

    private fun cacheCodecConfigLocked(record: QueuedVideoRecord) {
        while (codecConfigs.size >= MAX_CODEC_CONFIGS) codecConfigs.removeFirst()
        codecConfigs.addLast(record)
    }

    private fun fitsLocked(record: QueuedVideoRecord): Boolean =
        records.isEmpty() ||
            (records.size < MAX_QUEUED_RECORDS && queuedBytes + record.byteCount <= MAX_QUEUED_BYTES)

    private fun addLocked(record: QueuedVideoRecord) {
        records.addLast(record)
        queuedBytes += record.byteCount
        lock.notifyAll()
    }

    private fun clearLocked() {
        records.clear()
        queuedBytes = 0
    }

    private companion object {
        const val MAX_QUEUED_RECORDS = 4
        const val MAX_QUEUED_BYTES = 256 * 1024
        const val MAX_CODEC_CONFIGS = 4
    }
}

private fun encodeRecord(record: ScrcpyVideoRecord, nowNanos: Long): QueuedVideoRecord = when (record) {
    is ScrcpyVideoRecord.Session -> {
        val flags = Int.MIN_VALUE or if (record.clientResize) 1 else 0
        QueuedVideoRecord(
            header = ByteBuffer.allocate(ScrcpyVideoStreamReader.RECORD_HEADER_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(flags)
                .putInt(record.dimensions.width)
                .putInt(record.dimensions.height)
                .array(),
            payload = ByteArray(0),
            session = true,
            codecConfig = false,
            keyFrame = false,
            enqueuedAtNanos = nowNanos,
        )
    }
    is ScrcpyVideoRecord.Packet -> {
        var ptsAndFlags = if (record.codecConfig) PACKET_FLAG_CONFIG else record.presentationTimeUs
        if (record.keyFrame) ptsAndFlags = ptsAndFlags or PACKET_FLAG_KEY_FRAME
        QueuedVideoRecord(
            header = ByteBuffer.allocate(ScrcpyVideoStreamReader.RECORD_HEADER_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(ptsAndFlags)
                .putInt(record.data.size)
                .array(),
            payload = record.data,
            session = false,
            codecConfig = record.codecConfig,
            keyFrame = record.keyFrame,
            enqueuedAtNanos = nowNanos,
        )
    }
}

private const val PACKET_FLAG_CONFIG = 1L shl 62
private const val PACKET_FLAG_KEY_FRAME = 1L shl 61
