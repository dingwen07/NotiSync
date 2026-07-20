package net.extrawdw.apps.notisync.screen

import android.view.KeyEvent
import android.view.MotionEvent
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        require(keyCode in ALLOWED_NAVIGATION_KEYS) { "unsupported navigation key" }
        writeFrames(
            keyFrame(KeyEvent.ACTION_DOWN, keyCode),
            keyFrame(KeyEvent.ACTION_UP, keyCode),
        )
    }

    @Throws(IOException::class)
    fun togglePower() {
        writeFrames(byteArrayOf(TYPE_TOGGLE_POWER.toByte()))
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
        private const val TYPE_INJECT_TOUCH = 2
        private const val TYPE_TOGGLE_POWER = 64
        private const val KEY_FRAME_BYTES = 14
        private const val TOUCH_FRAME_BYTES = 32
        private const val MAX_UNSIGNED_SHORT = 0xffff

        private val ALLOWED_NAVIGATION_KEYS = setOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
        )
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
        fun write(writer: AndroidScreenControlWriter)

        data class Key(val keyCode: Int) : Command {
            override fun write(writer: AndroidScreenControlWriter) = writer.sendKeyPress(keyCode)
        }

        data object Power : Command {
            override fun write(writer: AndroidScreenControlWriter) = writer.togglePower()
        }

        data class Touches(val touches: List<AndroidScreenTouch>) : Command {
            override val coalescibleMove: Boolean =
                touches.isNotEmpty() && touches.all { it.action == MotionEvent.ACTION_MOVE }

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

    fun sendKeyPress(keyCode: Int): Boolean = enqueue(Command.Key(keyCode))

    fun togglePower(): Boolean = enqueue(Command.Power)

    fun sendTouches(touches: List<AndroidScreenTouch>): Boolean =
        enqueue(Command.Touches(touches.toList()))

    private fun enqueue(command: Command): Boolean {
        var scheduleDrain = false
        var saturated = false
        synchronized(lock) {
            if (closed) return false

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
