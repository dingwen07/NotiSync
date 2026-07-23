package net.extrawdw.apps.notisync.screen

import android.view.KeyEvent
import android.view.MotionEvent
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A screen-protocol-v1 touch after coordinates have been mapped into the source video space. */
internal data class AndroidScreenTouch(
    val action: Int,
    val pointerId: Long,
    val x: Int,
    val y: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val pressure: Float,
)

private val ANDROID_SCREEN_CONTROL_KEYS = setOf(
    KeyEvent.KEYCODE_BACK,
    KeyEvent.KEYCODE_HOME,
    KeyEvent.KEYCODE_APP_SWITCH,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_DEL,
    KeyEvent.KEYCODE_FORWARD_DEL,
    KeyEvent.KEYCODE_VOLUME_UP,
    KeyEvent.KEYCODE_VOLUME_DOWN,
)

/**
 * Serialized scrcpy-v1 control writer. One public operation becomes one OutputStream write so
 * touch batches and key down/up pairs cannot be interleaved by lifecycle and UI callbacks.
 */
internal class AndroidScreenControlWriter(
    private val output: OutputStream,
) : Closeable {
    private val writeLock = Any()
    private val closed = AtomicBoolean(false)

    @Throws(IOException::class)
    fun sendKeyPress(keyCode: Int) {
        writeFrames(
            keyFrame(KeyEvent.ACTION_DOWN, keyCode),
            keyFrame(KeyEvent.ACTION_UP, keyCode),
        )
    }

    /**
     * Writes one pinned scrcpy inject-text message: type, unsigned big-endian byte length, UTF-8.
     * The vendored server rejects inject-text payloads larger than 300 bytes.
     */
    @Throws(IOException::class)
    fun sendText(text: String) {
        val utf8 = text.toByteArray(StandardCharsets.UTF_8)
        require(utf8.size <= SCRCPY_INJECT_TEXT_MAX_BYTES) { "screen control text is too long" }
        writeFrames(
            ByteBuffer.allocate(TEXT_FRAME_HEADER_BYTES + utf8.size).order(ByteOrder.BIG_ENDIAN)
                .put(TYPE_INJECT_TEXT.toByte())
                .putInt(utf8.size)
                .put(utf8)
                .array(),
        )
    }

    @Throws(IOException::class)
    fun togglePower() {
        writeFrames(byteArrayOf(TYPE_TOGGLE_POWER.toByte()))
    }

    @Throws(IOException::class)
    fun expandNotificationPanel() {
        writeFrames(byteArrayOf(TYPE_EXPAND_NOTIFICATION_PANEL.toByte()))
    }

    /** Pause/resume source video production without closing either authenticated channel. */
    @Throws(IOException::class)
    fun setVideoVisible(visible: Boolean) {
        writeFrames(
            byteArrayOf(
                TYPE_SET_VIDEO_VISIBILITY.toByte(),
                if (visible) VIDEO_VISIBLE else VIDEO_HIDDEN,
            ),
        )
    }

    @Throws(IOException::class)
    fun sendTouches(touches: List<AndroidScreenTouch>) {
        require(touches.isNotEmpty()) { "touch batch is empty" }
        writeFrames(*touches.map(::touchFrame).toTypedArray())
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) output.close()
    }

    private fun writeFrames(vararg frames: ByteArray) {
        val byteCount = frames.sumOf(ByteArray::size)
        val batch = ByteArray(byteCount)
        var offset = 0
        for (frame in frames) {
            frame.copyInto(batch, destinationOffset = offset)
            offset += frame.size
        }
        synchronized(writeLock) {
            if (closed.get()) throw IOException("screen control channel is closed")
            output.write(batch)
            output.flush()
        }
    }

    private fun keyFrame(action: Int, keyCode: Int): ByteArray =
        ByteBuffer.allocate(KEY_FRAME_BYTES).order(ByteOrder.BIG_ENDIAN)
            .put(TYPE_INJECT_KEYCODE.toByte())
            .put(action.toByte())
            .putInt(keyCode)
            .putInt(0) // repeat
            .putInt(0) // meta state
            .array()

    private fun touchFrame(touch: AndroidScreenTouch): ByteArray {
        require(touch.action in TOUCH_ACTIONS) { "unsupported touch action" }
        require(touch.pointerId >= 0) { "negative pointer id" }
        require(touch.sourceWidth in 1..MAX_UNSIGNED_SHORT) { "invalid source width" }
        require(touch.sourceHeight in 1..MAX_UNSIGNED_SHORT) { "invalid source height" }
        require(touch.x in 0 until touch.sourceWidth) { "touch x is outside source" }
        require(touch.y in 0 until touch.sourceHeight) { "touch y is outside source" }
        require(touch.pressure.isFinite()) { "touch pressure is not finite" }
        val fixedPressure = when {
            touch.pressure <= 0f -> 0
            touch.pressure >= 1f -> MAX_UNSIGNED_SHORT
            else -> (touch.pressure * 65_536f).toInt().coerceAtMost(MAX_UNSIGNED_SHORT)
        }
        return ByteBuffer.allocate(TOUCH_FRAME_BYTES).order(ByteOrder.BIG_ENDIAN)
            .put(TYPE_INJECT_TOUCH.toByte())
            .put(touch.action.toByte())
            .putLong(touch.pointerId)
            .putInt(touch.x)
            .putInt(touch.y)
            .putShort(touch.sourceWidth.toShort())
            .putShort(touch.sourceHeight.toShort())
            .putShort(fixedPressure.toShort())
            .putInt(0) // action button: touchscreen, not mouse
            .putInt(0) // buttons
            .array()
    }

    companion object {
        private const val TYPE_INJECT_KEYCODE = 0
        private const val TYPE_INJECT_TEXT = 1
        private const val TYPE_INJECT_TOUCH = 2
        private const val TYPE_TOGGLE_POWER = 64
        private const val TYPE_SET_VIDEO_VISIBILITY = 65
        private const val TYPE_EXPAND_NOTIFICATION_PANEL = 66
        private const val VIDEO_HIDDEN: Byte = 0
        private const val VIDEO_VISIBLE: Byte = 1
        private const val KEY_FRAME_BYTES = 14
        private const val TEXT_FRAME_HEADER_BYTES = 5
        private const val TOUCH_FRAME_BYTES = 32
        private const val MAX_UNSIGNED_SHORT = 0xffff

        private val TOUCH_ACTIONS = setOf(
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_CANCEL,
        )
    }
}

/** Map a coordinate within the aspect-fit SurfaceView into the encoded source coordinate space. */
internal fun mapAndroidScreenTouchCoordinate(
    coordinate: Float,
    viewExtent: Int,
    sourceExtent: Int,
): Int {
    require(viewExtent > 0 && sourceExtent > 0)
    if (!coordinate.isFinite()) return 0
    return (coordinate * sourceExtent / viewExtent)
        .toInt()
        .coerceIn(0, sourceExtent - 1)
}

/**
 * One bounded, ordered I/O lane for an Android viewer's control channel.
 *
 * Android rejects socket traffic on the main thread. Touch callbacks therefore enqueue immutable
 * frames here; adjacent MOVE batches are coalesced while DOWN/UP/CANCEL and function keys are never
 * silently discarded or reordered. A saturated essential-only queue fails the session closed.
 */
internal class AndroidScreenControlDispatcher(
    private val writer: AndroidScreenControlWriter,
    private val onFailure: (Throwable) -> Unit,
) : Closeable {
    private sealed interface Command {
        val coalescibleMove: Boolean get() = false
        val terminalTouch: Boolean get() = false
        fun write(writer: AndroidScreenControlWriter)

        data class Key(val keyCode: Int) : Command {
            override fun write(writer: AndroidScreenControlWriter) = writer.sendKeyPress(keyCode)
        }

        // Intentionally not a data class: its generated toString() would expose typed secrets.
        class Text(private val text: String) : Command {
            override fun write(writer: AndroidScreenControlWriter) = writer.sendText(text)
        }

        data object Power : Command {
            override fun write(writer: AndroidScreenControlWriter) = writer.togglePower()
        }

        data object ExpandNotificationPanel : Command {
            override fun write(writer: AndroidScreenControlWriter) = writer.expandNotificationPanel()
        }

        data class VideoVisibility(val visible: Boolean) : Command {
            override fun write(writer: AndroidScreenControlWriter) = writer.setVideoVisible(visible)
        }

        data class Touches(val touches: List<AndroidScreenTouch>) : Command {
            override val coalescibleMove: Boolean =
                touches.isNotEmpty() && touches.all { it.action == MotionEvent.ACTION_MOVE }
            override val terminalTouch: Boolean = touches.any {
                it.action == MotionEvent.ACTION_UP || it.action == MotionEvent.ACTION_CANCEL
            }

            override fun write(writer: AndroidScreenControlWriter) = writer.sendTouches(touches)
        }
    }

    private val lock = Any()
    private val queue = ArrayDeque<Command>()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "notisync-screen-control-${THREAD_ID.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
    private val closeExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "notisync-screen-control-close-${THREAD_ID.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
    private var closed = false
    private var drainScheduled = false

    /** Reject unsupported UI input synchronously without poisoning the control session. */
    fun sendKeyPress(keyCode: Int): Boolean {
        if (keyCode !in ANDROID_SCREEN_CONTROL_KEYS) return false
        return enqueue(Command.Key(keyCode))
    }

    /** Reject malformed/oversized IME commits before they can fail the control session. */
    fun sendText(text: String): Boolean {
        if (text.isEmpty()) return true
        if (!isScrcpyInjectTextSizeAllowed(text)) return false
        return enqueue(Command.Text(text))
    }

    fun togglePower(): Boolean = enqueue(Command.Power)

    fun expandNotificationPanel(): Boolean = enqueue(Command.ExpandNotificationPanel)

    fun setVideoVisible(visible: Boolean): Boolean = enqueue(Command.VideoVisibility(visible))

    fun sendTouches(touches: List<AndroidScreenTouch>): Boolean =
        enqueue(Command.Touches(touches.toList()))

    private fun enqueue(command: Command): Boolean {
        var scheduleDrain = false
        var saturated = false
        synchronized(lock) {
            if (closed) return false

            if (command.terminalTouch) {
                queue.removeAll { it.coalescibleMove }
            }
            if (command.coalescibleMove && queue.peekLast()?.coalescibleMove == true) {
                queue.removeLast()
                queue.addLast(command)
                return true
            }
            if (queue.size >= MAX_QUEUED_COMMANDS) {
                val iterator = queue.iterator()
                while (iterator.hasNext()) {
                    if (iterator.next().coalescibleMove) {
                        iterator.remove()
                        break
                    }
                }
            }
            if (queue.size >= MAX_QUEUED_COMMANDS) {
                if (command.coalescibleMove) return false
                saturated = true
            } else {
                queue.addLast(command)
                if (!drainScheduled) {
                    drainScheduled = true
                    scheduleDrain = true
                }
            }
        }

        if (saturated) {
            fail(IOException("screen control queue is saturated"))
            return false
        }
        if (scheduleDrain) {
            runCatching { executor.execute(::drain) }.onFailure(::fail)
        }
        return true
    }

    private fun drain() {
        while (true) {
            val command = synchronized(lock) {
                if (closed || queue.isEmpty()) {
                    drainScheduled = false
                    null
                } else {
                    queue.removeFirst()
                }
            } ?: return
            try {
                command.write(writer)
            } catch (error: Throwable) {
                fail(error)
                return
            }
        }
    }

    private fun fail(error: Throwable) {
        val notify = synchronized(lock) {
            if (closed) false else {
                closed = true
                queue.clear()
                true
            }
        }
        if (!notify) return
        executor.shutdownNow()
        closeWriterAsync()
        onFailure(error)
    }

    override fun close() {
        val closeWriter = synchronized(lock) {
            if (closed) false else {
                closed = true
                queue.clear()
                true
            }
        }
        if (!closeWriter) return
        // Closing from another I/O lane can unblock a stalled write without touching the UI thread.
        executor.shutdownNow()
        closeWriterAsync()
    }

    private fun closeWriterAsync() {
        runCatching {
            closeExecutor.execute { runCatching { writer.close() } }
        }
        closeExecutor.shutdown()
    }

    private companion object {
        const val MAX_QUEUED_COMMANDS = 64
        val THREAD_ID = AtomicInteger()
    }
}

/** Must stay aligned with ControlMessageReader.INJECT_TEXT_MAX_LENGTH in the pinned server. */
internal const val SCRCPY_INJECT_TEXT_MAX_BYTES = 300

/**
 * Computes the UTF-8 size without allocating an unbounded byte array on the UI/InputConnection
 * caller. Java's UTF-8 encoder replaces an unpaired surrogate with a single-byte question mark.
 */
internal fun isScrcpyInjectTextSizeAllowed(text: String): Boolean {
    var utf8Bytes = 0
    var index = 0
    while (index < text.length) {
        val current = text[index]
        utf8Bytes += when {
            current.code <= 0x7f -> 1
            current.code <= 0x7ff -> 2
            Character.isHighSurrogate(current) &&
                index + 1 < text.length && Character.isLowSurrogate(text[index + 1]) -> {
                index++
                4
            }
            Character.isSurrogate(current) -> 1
            else -> 3
        }
        if (utf8Bytes > SCRCPY_INJECT_TEXT_MAX_BYTES) return false
        index++
    }
    return true
}
