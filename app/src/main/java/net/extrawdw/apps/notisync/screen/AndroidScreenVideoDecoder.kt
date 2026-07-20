package net.extrawdw.apps.notisync.screen

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import net.extrawdw.notisync.protocol.ScreenMirrorCodec

/**
 * Decodes one authenticated NotiSync/scrcpy video channel to a caller-owned [Surface].
 *
 * [decode] is blocking and must run off the main thread. Calling [close] closes the owned input,
 * unblocking a pending read; codec teardown then completes on the decoding thread. The surface is
 * borrowed and is never released here.
 */
internal class AndroidScreenVideoDecoder(
    input: InputStream,
    private val expectedCodec: ScreenMirrorCodec,
    private val surface: Surface,
    private val hardwareDecoderName: String? = null,
    private val onDimensionsChanged: (ScrcpySessionDimensions) -> Unit = {},
) : Closeable {
    private val reader = ScrcpyVideoStreamReader(input, expectedCodec)
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()

    @Volatile
    private var activeCodec: MediaCodec? = null

    /** Runs until a clean channel EOF, cancellation via [close], or a malformed/undecodable stream. */
    fun decode() {
        check(started.compareAndSet(false, true)) { "screen video decoder may only run once" }
        if (closed.get()) return

        var codec: MediaCodec? = null
        try {
            var dimensions = reader.readPreamble()
            onDimensionsChanged(dimensions)
            codec = createAndStartCodec(dimensions)

            while (!closed.get()) {
                when (val record = reader.readRecord() ?: break) {
                    is ScrcpyVideoRecord.Session -> {
                        dimensions = record.dimensions
                        onDimensionsChanged(dimensions)
                        releaseCodec(codec)
                        codec = createAndStartCodec(dimensions)
                    }

                    is ScrcpyVideoRecord.Packet -> {
                        val currentCodec = checkNotNull(codec)
                        queuePacket(currentCodec, record)
                        drainAvailableOutput(currentCodec, OUTPUT_DEQUEUE_TIMEOUT_US)
                    }
                }
            }

            if (!closed.get()) {
                val currentCodec = checkNotNull(codec)
                queueEndOfStream(currentCodec)
                drainEndOfStream(currentCodec)
            }
        } catch (error: IOException) {
            if (!closed.get()) throw error
        } finally {
            activeCodec = null
            releaseCodec(codec)
            runCatching { reader.close() }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Do not release MediaCodec concurrently with dequeue/queue calls. Closing the channel
        // interrupts the only unbounded operation; finite codec timeouts let decode reach finally.
        runCatching { reader.close() }
    }

    private fun createAndStartCodec(dimensions: ScrcpySessionDimensions): MediaCodec {
        check(!closed.get()) { "screen video decoder is closed" }
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
            val format = MediaFormat.createVideoFormat(mime, dimensions.width, dimensions.height).apply {
                // MAX_PACKET_SIZE_BYTES is a defensive protocol/parser ceiling, not a normal
                // encoded-frame size. Advertising that 16 MiB ceiling here can make vendor
                // codecs reserve excessively large buffers. Let the decoder choose its input
                // allocation and validate every packet against the returned buffer below.
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec.configure(format, surface, null, 0)
            codec.start()
            activeCodec = codec
            return codec
        } catch (error: Throwable) {
            runCatching { codec.release() }
            throw error
        }
    }

    private fun queuePacket(codec: MediaCodec, packet: ScrcpyVideoRecord.Packet) {
        while (!closed.get()) {
            val inputIndex = codec.dequeueInputBuffer(INPUT_DEQUEUE_TIMEOUT_US)
            if (inputIndex < 0) {
                drainAvailableOutput(codec, 0L)
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

    private fun queueEndOfStream(codec: MediaCodec) {
        val deadlineNanos = System.nanoTime() + END_OF_STREAM_DRAIN_TIMEOUT_NS
        while (!closed.get() && System.nanoTime() < deadlineNanos) {
            val inputIndex = codec.dequeueInputBuffer(INPUT_DEQUEUE_TIMEOUT_US)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
            drainAvailableOutput(codec, 0L)
        }
    }

    private fun drainAvailableOutput(codec: MediaCodec, firstTimeoutUs: Long): Boolean {
        var timeoutUs = firstTimeoutUs
        val info = MediaCodec.BufferInfo()
        while (!closed.get()) {
            when (val outputIndex = codec.dequeueOutputBuffer(info, timeoutUs)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> validateOutputFormat(codec.outputFormat)
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outputIndex, info.size > 0)
                    if (endOfStream) return true
                }
            }
            timeoutUs = 0L
        }
        return false
    }

    private fun drainEndOfStream(codec: MediaCodec) {
        val deadlineNanos = System.nanoTime() + END_OF_STREAM_DRAIN_TIMEOUT_NS
        while (!closed.get() && System.nanoTime() < deadlineNanos) {
            if (drainAvailableOutput(codec, OUTPUT_DEQUEUE_TIMEOUT_US)) return
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
        if (activeCodec === codec) activeCodec = null
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    companion object {
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
