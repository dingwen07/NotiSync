package net.extrawdw.apps.notisync.screen

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.Closeable
import java.io.InputStream
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import net.extrawdw.notisync.protocol.ScreenMirrorCodec

/**
 * Continuously consumes one authenticated NotiSync/scrcpy video channel and renders it only while
 * a caller-owned [Surface] is attached.
 *
 * The parser has its own bounded producer lane. MediaCodec is exclusively owned by [decode]'s
 * calling thread, so a Surface may be detached promptly without racing codec calls. While detached,
 * records are still authenticated, bounded and drained from the socket, but encoded frames are
 * discarded. Reattachment starts the decoder at the next key frame using the most recent bounded
 * codec configuration. The attached Surface is borrowed and is never released here.
 */
internal class AndroidScreenVideoDecoder(
    input: InputStream,
    private val expectedCodec: ScreenMirrorCodec,
    private val hardwareDecoderName: String? = null,
    private val onDimensionsChanged: (ScrcpySessionDimensions) -> Unit = {},
) : Closeable {
    private data class SurfaceAttachment(val ownerToken: String, val surface: Surface)

    private sealed interface DecoderEvent {
        data class Dimensions(val value: ScrcpySessionDimensions) : DecoderEvent
        data class Record(val value: ScrcpyVideoRecord) : DecoderEvent
        data class Failure(val error: Throwable) : DecoderEvent
        data object End : DecoderEvent
        data object Wake : DecoderEvent
    }

    private val reader = ScrcpyVideoStreamReader(input, expectedCodec)
    private val events = ArrayBlockingQueue<DecoderEvent>(EVENT_QUEUE_CAPACITY)
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val readerCloseStarted = AtomicBoolean()
    private val surfaceLock = Any()

    private var requestedSurface: SurfaceAttachment? = null

    @Volatile
    private var readerThread: Thread? = null

    /**
     * Attach or replace the rendering Surface for one exact Activity/view generation.
     * A stale owner can never detach a newer owner's Surface.
     */
    fun attachSurface(ownerToken: String, surface: Surface): Boolean {
        require(ownerToken.isNotBlank() && ownerToken.length <= MAX_OWNER_TOKEN_LENGTH) {
            "invalid screen surface owner token"
        }
        if (closed.get() || !surface.isValid) return false
        synchronized(surfaceLock) {
            if (closed.get()) return false
            requestedSurface = SurfaceAttachment(ownerToken, surface)
        }
        signalSurfaceChange()
        return true
    }

    /** Detaches only the Surface installed by [ownerToken]. */
    fun detachSurface(ownerToken: String): Boolean {
        val detached = synchronized(surfaceLock) {
            if (requestedSurface?.ownerToken != ownerToken) {
                false
            } else {
                requestedSurface = null
                true
            }
        }
        if (detached) signalSurfaceChange()
        return detached
    }

    /** Runs until channel EOF, cancellation via [close], or malformed/undecodable input. */
    fun decode() {
        check(started.compareAndSet(false, true)) { "screen video decoder may only run once" }
        if (closed.get()) return

        val parser = Thread(::readEvents, "notisync-screen-video-reader").apply {
            isDaemon = true
        }
        readerThread = parser
        parser.start()

        var dimensions: ScrcpySessionDimensions? = null
        var codec: MediaCodec? = null
        var codecSurface: SurfaceAttachment? = null
        val codecConfigs = ArrayDeque<ScrcpyVideoRecord.Packet>()
        var codecConfigBytes = 0

        try {
            while (!closed.get()) {
                val event = events.take()
                val desiredSurface = requestedValidSurface()
                if (codec != null && !codecSurface.matches(desiredSurface)) {
                    releaseCodec(codec)
                    codec = null
                    codecSurface = null
                }

                when (event) {
                    is DecoderEvent.Dimensions -> {
                        dimensions = event.value
                        onDimensionsChanged(event.value)
                        releaseCodec(codec)
                        codec = null
                        codecSurface = null
                        codecConfigs.clear()
                        codecConfigBytes = 0
                    }

                    is DecoderEvent.Record -> when (val record = event.value) {
                        is ScrcpyVideoRecord.Session -> {
                            dimensions = record.dimensions
                            onDimensionsChanged(record.dimensions)
                            releaseCodec(codec)
                            codec = null
                            codecSurface = null
                            // A session marker means the source encoder was recreated. Never feed
                            // its new key frame configuration from the previous encoder instance.
                            codecConfigs.clear()
                            codecConfigBytes = 0
                        }

                        is ScrcpyVideoRecord.Packet -> {
                            if (record.codecConfig) {
                                if (record.data.size <= MAX_CACHED_CODEC_CONFIG_BYTES) {
                                    while (
                                        codecConfigs.isNotEmpty() &&
                                        (codecConfigs.size >= MAX_CACHED_CODEC_CONFIG_PACKETS ||
                                            codecConfigBytes + record.data.size >
                                            MAX_CACHED_CODEC_CONFIG_BYTES)
                                    ) {
                                        codecConfigBytes -= codecConfigs.removeFirst().data.size
                                    }
                                    if (
                                        codecConfigs.size < MAX_CACHED_CODEC_CONFIG_PACKETS &&
                                        codecConfigBytes + record.data.size <=
                                        MAX_CACHED_CODEC_CONFIG_BYTES
                                    ) {
                                        codecConfigs.addLast(record)
                                        codecConfigBytes += record.data.size
                                    }
                                }
                                codec?.let {
                                    queuePacket(it, record, codecSurface)
                                    drainAvailableOutput(
                                        it,
                                        OUTPUT_DEQUEUE_TIMEOUT_US,
                                        codecSurface,
                                    )
                                }
                            } else {
                                val renderSurface = requestedValidSurface()
                                if (
                                    codec == null &&
                                    record.keyFrame &&
                                    renderSurface != null &&
                                    codecConfigs.isNotEmpty()
                                ) {
                                    val currentDimensions = checkNotNull(dimensions) {
                                        "scrcpy video packet arrived before session dimensions"
                                    }
                                    codec = createAndStartCodec(currentDimensions, renderSurface.surface)
                                    codecSurface = renderSurface
                                    codecConfigs.forEach {
                                        queuePacket(checkNotNull(codec), it, codecSurface)
                                    }
                                }
                                codec?.let {
                                    queuePacket(it, record, codecSurface)
                                    drainAvailableOutput(
                                        it,
                                        OUTPUT_DEQUEUE_TIMEOUT_US,
                                        codecSurface,
                                    )
                                }
                            }
                        }
                    }

                    is DecoderEvent.Failure -> throw event.error
                    DecoderEvent.End -> {
                        codec?.let {
                            queueEndOfStream(it, codecSurface)
                            drainEndOfStream(it, codecSurface)
                        }
                        return
                    }
                    DecoderEvent.Wake -> Unit
                }
            }
        } catch (interrupted: InterruptedException) {
            if (!closed.get()) throw interrupted
            Thread.currentThread().interrupt()
        } finally {
            closed.set(true)
            synchronized(surfaceLock) { requestedSurface = null }
            releaseCodec(codec)
            parser.interrupt()
            // A MediaCodec failure may occur while the parser still owns Bouncy Castle's blocking
            // TLS read lock. Give an already-finishing parser a tiny chance to settle (which keeps
            // finite-stream cleanup deterministic), but never close that TLS stream synchronously
            // while it remains blocked. The session owner will abort the raw socket next.
            if (parser.isAlive && Thread.currentThread() !== parser) {
                runCatching { parser.join(READER_SETTLE_JOIN_TIMEOUT_MILLIS) }
            }
            if (parser.isAlive) closeReaderAsync() else closeReaderNow()
            readerThread = null
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(surfaceLock) { requestedSurface = null }
        readerThread?.interrupt()
        events.offer(DecoderEvent.Wake)
        // Never make the caller wait on TlsInputStream.close(); raw transport ownership lives one
        // layer above this decoder and its abort will release the parser.
        closeReaderAsync()
    }

    private fun closeReaderNow() {
        if (!readerCloseStarted.compareAndSet(false, true)) return
        runCatching { reader.close() }
    }

    private fun closeReaderAsync() {
        if (!readerCloseStarted.compareAndSet(false, true)) return
        runCatching {
            Thread(
                { runCatching { reader.close() } },
                "notisync-screen-video-input-close",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun readEvents() {
        try {
            putEvent(DecoderEvent.Dimensions(reader.readPreamble()))
            while (!closed.get()) {
                val record = reader.readRecord() ?: break
                putEvent(DecoderEvent.Record(record))
            }
            if (!closed.get()) putEvent(DecoderEvent.End)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            if (!closed.get()) putEventIgnoringInterrupt(DecoderEvent.Failure(error))
        }
    }

    @Throws(InterruptedException::class)
    private fun putEvent(event: DecoderEvent) {
        while (!closed.get()) {
            events.put(event)
            return
        }
    }

    private fun putEventIgnoringInterrupt(event: DecoderEvent) {
        while (!closed.get()) {
            try {
                events.put(event)
                return
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun signalSurfaceChange() {
        // If the bounded queue is full, decode is already awake and reconciles the requested
        // Surface before the next queued record. An empty/blocked parser always leaves room here.
        events.offer(DecoderEvent.Wake)
    }

    private fun requestedValidSurface(): SurfaceAttachment? = synchronized(surfaceLock) {
        requestedSurface?.takeIf { it.surface.isValid }
    }

    private fun SurfaceAttachment?.matches(other: SurfaceAttachment?): Boolean = when {
        this == null || other == null -> this == null && other == null
        else -> ownerToken == other.ownerToken && surface === other.surface
    }

    private fun createAndStartCodec(
        dimensions: ScrcpySessionDimensions,
        surface: Surface,
    ): MediaCodec {
        check(!closed.get()) { "screen video decoder is closed" }
        check(surface.isValid) { "screen output Surface is no longer valid" }
        val mime = mimeFor(expectedCodec)
        val codec = when (expectedCodec) {
            ScreenMirrorCodec.AV1, ScreenMirrorCodec.H265 -> MediaCodec.createByCodecName(
                requireNotNull(hardwareDecoderName) {
                    "selected ${expectedCodec.name.lowercase()} codec has no hardware decoder"
                },
            )
            ScreenMirrorCodec.H264 -> hardwareDecoderName
                ?.let(MediaCodec::createByCodecName)
                ?: MediaCodec.createDecoderByType(mime)
        }
        try {
            val format = MediaFormat.createVideoFormat(
                mime,
                dimensions.width,
                dimensions.height,
            ).apply {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec.configure(format, surface, null, 0)
            codec.start()
            return codec
        } catch (error: Throwable) {
            runCatching { codec.release() }
            throw error
        }
    }

    private fun queuePacket(
        codec: MediaCodec,
        packet: ScrcpyVideoRecord.Packet,
        codecSurface: SurfaceAttachment?,
    ) {
        while (!closed.get()) {
            val inputIndex = codec.dequeueInputBuffer(INPUT_DEQUEUE_TIMEOUT_US)
            if (inputIndex < 0) {
                drainAvailableOutput(codec, 0L, codecSurface)
                continue
            }
            val inputBuffer = checkNotNull(codec.getInputBuffer(inputIndex)) {
                "decoder returned no input buffer"
            }
            require(packet.data.size <= inputBuffer.capacity()) {
                "scrcpy packet exceeds decoder input capacity"
            }
            inputBuffer.clear()
            inputBuffer.put(packet.data)
            val flags = when {
                packet.codecConfig -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                packet.keyFrame -> MediaCodec.BUFFER_FLAG_KEY_FRAME
                else -> 0
            }
            codec.queueInputBuffer(
                inputIndex,
                0,
                packet.data.size,
                packet.presentationTimeUs,
                flags,
            )
            return
        }
    }

    private fun queueEndOfStream(codec: MediaCodec, codecSurface: SurfaceAttachment?) {
        val deadlineNanos = System.nanoTime() + END_OF_STREAM_DRAIN_TIMEOUT_NS
        while (!closed.get() && System.nanoTime() < deadlineNanos) {
            val inputIndex = codec.dequeueInputBuffer(INPUT_DEQUEUE_TIMEOUT_US)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
            drainAvailableOutput(codec, 0L, codecSurface)
        }
    }

    private fun drainAvailableOutput(
        codec: MediaCodec,
        firstTimeoutUs: Long,
        codecSurface: SurfaceAttachment?,
    ): Boolean {
        var timeoutUs = firstTimeoutUs
        val info = MediaCodec.BufferInfo()
        while (!closed.get()) {
            when (val outputIndex = codec.dequeueOutputBuffer(info, timeoutUs)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> validateOutputFormat(codec.outputFormat)
                else -> if (outputIndex >= 0) {
                    val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    // surfaceDestroyed() may race with a finite dequeue call. Re-check the exact
                    // owner immediately before release so the last buffered frame is discarded
                    // instead of being rendered into a Surface whose callback is returning.
                    val render = info.size > 0 && codecSurface.matches(requestedValidSurface())
                    codec.releaseOutputBuffer(outputIndex, render)
                    if (endOfStream) return true
                }
            }
            timeoutUs = 0L
        }
        return false
    }

    private fun drainEndOfStream(codec: MediaCodec, codecSurface: SurfaceAttachment?) {
        val deadlineNanos = System.nanoTime() + END_OF_STREAM_DRAIN_TIMEOUT_NS
        while (!closed.get() && System.nanoTime() < deadlineNanos) {
            if (drainAvailableOutput(codec, OUTPUT_DEQUEUE_TIMEOUT_US, codecSurface)) return
        }
    }

    private fun validateOutputFormat(format: MediaFormat) {
        if (!format.containsKey(MediaFormat.KEY_WIDTH) || !format.containsKey(MediaFormat.KEY_HEIGHT)) return
        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        require(
            width in 1..ScrcpyVideoStreamReader.MAX_DIMENSION &&
                height in 1..ScrcpyVideoStreamReader.MAX_DIMENSION &&
                width.toLong() * height.toLong() <= ScrcpyVideoStreamReader.MAX_PIXELS,
        ) { "decoder reported invalid output dimensions" }
    }

    private fun releaseCodec(codec: MediaCodec?) {
        if (codec == null) return
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    companion object {
        private const val EVENT_QUEUE_CAPACITY = 4
        private const val MAX_OWNER_TOKEN_LENGTH = 128
        private const val MAX_CACHED_CODEC_CONFIG_PACKETS = 4
        private const val MAX_CACHED_CODEC_CONFIG_BYTES = 1024 * 1024
        private const val READER_SETTLE_JOIN_TIMEOUT_MILLIS = 25L
        private const val INPUT_DEQUEUE_TIMEOUT_US = 10_000L
        private const val OUTPUT_DEQUEUE_TIMEOUT_US = 5_000L
        private const val END_OF_STREAM_DRAIN_TIMEOUT_NS = 250_000_000L

        internal fun mimeFor(codec: ScreenMirrorCodec): String = when (codec) {
            ScreenMirrorCodec.H264 -> "video/avc"
            ScreenMirrorCodec.H265 -> "video/hevc"
            ScreenMirrorCodec.AV1 -> "video/av01"
        }
    }
}
