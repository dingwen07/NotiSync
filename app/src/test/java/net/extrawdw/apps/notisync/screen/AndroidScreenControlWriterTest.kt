package net.extrawdw.apps.notisync.screen

import android.view.KeyEvent
import android.view.MotionEvent
import com.genymobile.scrcpy.control.ControlMessage
import com.genymobile.scrcpy.control.ControlMessageReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidScreenControlWriterTest {
    @Test
    fun `navigation press writes an adjacent big-endian down and up pair`() {
        val output = ByteArrayOutputStream()
        AndroidScreenControlWriter(output).sendKeyPress(KeyEvent.KEYCODE_HOME)

        val bytes = output.toByteArray()
        assertEquals(28, bytes.size)
        assertKeyFrame(bytes.copyOfRange(0, 14), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME)
        assertKeyFrame(bytes.copyOfRange(14, 28), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME)
    }

    @Test
    fun `committed text uses pinned scrcpy utf8 framing`() {
        val output = ByteArrayOutputStream()
        val text = "paßwørd"
        val expectedText = text.toByteArray(StandardCharsets.UTF_8)

        AndroidScreenControlWriter(output).sendText(text)

        val frame = ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.BIG_ENDIAN)
        assertEquals(1, frame.get().toInt())
        assertEquals(expectedText.size, frame.int)
        val actualText = ByteArray(frame.remaining())
        frame.get(actualText)
        assertArrayEquals(expectedText, actualText)

        val parsed = ControlMessageReader(ByteArrayInputStream(output.toByteArray())).read { true }
        assertEquals(ControlMessage.TYPE_INJECT_TEXT, parsed.type)
        assertEquals(text, parsed.text)
    }

    @Test
    fun `committed text enforces vendored 300 byte limit`() {
        val acceptedOutput = ByteArrayOutputStream()
        AndroidScreenControlWriter(acceptedOutput).sendText("é".repeat(150))
        assertEquals(305, acceptedOutput.size())

        val rejectedOutput = ByteArrayOutputStream()
        val rejectedWriter = AndroidScreenControlWriter(rejectedOutput)
        assertThrows(IllegalArgumentException::class.java) {
            rejectedWriter.sendText("é".repeat(151))
        }
        assertEquals(0, rejectedOutput.size())
    }

    @Test
    fun `keyboard enter and delete use adjacent key pairs`() {
        val output = ByteArrayOutputStream()
        val writer = AndroidScreenControlWriter(output)

        writer.sendKeyPress(KeyEvent.KEYCODE_DEL)
        writer.sendKeyPress(KeyEvent.KEYCODE_ENTER)

        val bytes = output.toByteArray()
        assertEquals(56, bytes.size)
        assertKeyFrame(bytes.copyOfRange(0, 14), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
        assertKeyFrame(bytes.copyOfRange(14, 28), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)
        assertKeyFrame(bytes.copyOfRange(28, 42), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
        assertKeyFrame(bytes.copyOfRange(42, 56), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
    }

    @Test
    fun `multitouch batch preserves pointers and scrcpy fixed point pressure`() {
        val output = ByteArrayOutputStream()
        AndroidScreenControlWriter(output).sendTouches(
            listOf(
                AndroidScreenTouch(MotionEvent.ACTION_MOVE, 3, 99, 199, 1080, 2400, 0.5f),
                AndroidScreenTouch(MotionEvent.ACTION_MOVE, 7, 500, 900, 1080, 2400, 1f),
            ),
        )

        val bytes = output.toByteArray()
        assertEquals(64, bytes.size)
        val first = ByteBuffer.wrap(bytes, 0, 32).order(ByteOrder.BIG_ENDIAN)
        assertEquals(2, first.get().toInt())
        assertEquals(MotionEvent.ACTION_MOVE, first.get().toInt())
        assertEquals(3L, first.long)
        assertEquals(99, first.int)
        assertEquals(199, first.int)
        assertEquals(1080, first.short.toInt() and 0xffff)
        assertEquals(2400, first.short.toInt() and 0xffff)
        assertEquals(0x8000, first.short.toInt() and 0xffff)
        val second = ByteBuffer.wrap(bytes, 32, 32).slice().order(ByteOrder.BIG_ENDIAN)
        second.position(22)
        assertEquals(0xffff, second.short.toInt() and 0xffff)
    }

    @Test
    fun `power is the bounded NotiSync extension and close is fail closed`() {
        val output = ByteArrayOutputStream()
        val writer = AndroidScreenControlWriter(output)
        writer.togglePower()
        assertArrayEquals(byteArrayOf(64), output.toByteArray())
        writer.close()
        assertThrows(IOException::class.java) { writer.togglePower() }
    }

    @Test
    fun `video visibility uses a strict bounded NotiSync v1 frame`() {
        val output = ByteArrayOutputStream()
        val writer = AndroidScreenControlWriter(output)

        writer.setVideoVisible(false)
        writer.setVideoVisible(true)

        assertArrayEquals(byteArrayOf(65, 0, 65, 1), output.toByteArray())
        val reader = ControlMessageReader(ByteArrayInputStream(output.toByteArray()))
        val hidden = reader.read { true }
        val visible = reader.read { true }
        assertEquals(ControlMessage.TYPE_SET_VIDEO_VISIBILITY, hidden.type)
        assertFalse(hidden.isVideoVisible)
        assertEquals(ControlMessage.TYPE_SET_VIDEO_VISIBILITY, visible.type)
        assertTrue(visible.isVideoVisible)
    }

    @Test
    fun `concurrent operations remain whole output writes`() {
        val writes = Collections.synchronizedList(mutableListOf<ByteArray>())
        val output = object : ByteArrayOutputStream() {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                Thread.yield()
                writes += buffer.copyOfRange(offset, offset + length)
                super.write(buffer, offset, length)
            }
        }
        val writer = AndroidScreenControlWriter(output)
        val start = CountDownLatch(1)
        val workers = listOf(
            thread(start = true) { start.await(); writer.sendKeyPress(KeyEvent.KEYCODE_BACK) },
            thread(start = true) { start.await(); writer.togglePower() },
        )
        start.countDown()
        workers.forEach(Thread::join)

        assertEquals(2, writes.size)
        assertTrue(writes.any { it.size == 28 })
        assertTrue(writes.any { it.contentEquals(byteArrayOf(64)) })
    }

    @Test
    fun `surface coordinates clamp into source bounds`() {
        assertEquals(0, mapAndroidScreenTouchCoordinate(-20f, 500, 1080))
        assertEquals(540, mapAndroidScreenTouchCoordinate(250f, 500, 1080))
        assertEquals(1079, mapAndroidScreenTouchCoordinate(500f, 500, 1080))
        assertEquals(0, mapAndroidScreenTouchCoordinate(Float.NaN, 500, 1080))
    }

    @Test
    fun `dispatcher writes and closes away from its caller`() {
        val caller = Thread.currentThread()
        val writeThread = AtomicReference<Thread>()
        val closeThread = AtomicReference<Thread>()
        val written = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val output = object : OutputStream() {
            override fun write(value: Int) = Unit

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                writeThread.set(Thread.currentThread())
                written.countDown()
            }

            override fun close() {
                closeThread.set(Thread.currentThread())
                closed.countDown()
            }
        }
        val failures = AtomicInteger()
        val dispatcher = AndroidScreenControlDispatcher(AndroidScreenControlWriter(output)) {
            failures.incrementAndGet()
        }

        assertTrue(dispatcher.togglePower())
        assertTrue("control write did not finish", written.await(5, TimeUnit.SECONDS))
        dispatcher.close()
        assertTrue("control close did not finish", closed.await(5, TimeUnit.SECONDS))

        assertTrue(writeThread.get() !== caller)
        assertTrue(writeThread.get().name.startsWith("notisync-screen-control-"))
        assertTrue(closeThread.get() !== caller)
        assertTrue(closeThread.get().name.startsWith("notisync-screen-control-close-"))
        assertEquals(0, failures.get())
        assertFalse(dispatcher.togglePower())
    }

    @Test
    fun `dispatcher preserves committed text delete and enter ordering`() {
        val writes = Collections.synchronizedList(mutableListOf<ByteArray>())
        val expectedWrites = CountDownLatch(3)
        val output = object : OutputStream() {
            override fun write(value: Int) = Unit

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                writes += buffer.copyOfRange(offset, offset + length)
                expectedWrites.countDown()
            }
        }
        val failure = AtomicReference<Throwable>()
        val dispatcher = AndroidScreenControlDispatcher(AndroidScreenControlWriter(output)) {
            failure.compareAndSet(null, it)
        }

        assertTrue(dispatcher.sendText("typed"))
        assertTrue(dispatcher.sendKeyPress(KeyEvent.KEYCODE_DEL))
        assertTrue(dispatcher.sendKeyPress(KeyEvent.KEYCODE_ENTER))
        assertTrue("keyboard writes did not finish", expectedWrites.await(5, TimeUnit.SECONDS))
        dispatcher.close()

        assertEquals(null, failure.get())
        assertEquals(3, writes.size)
        assertEquals(1, writes[0][0].toInt())
        assertEquals("typed", String(writes[0], 5, writes[0].size - 5, StandardCharsets.UTF_8))
        assertKeyFrame(writes[1].copyOfRange(0, 14), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
        assertKeyFrame(writes[1].copyOfRange(14, 28), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)
        assertKeyFrame(writes[2].copyOfRange(0, 14), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
        assertKeyFrame(writes[2].copyOfRange(14, 28), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
    }

    @Test
    fun `IME deletion forwards the requested bounded key count`() {
        val writes = Collections.synchronizedList(mutableListOf<ByteArray>())
        val expectedWrites = CountDownLatch(3)
        val output = object : OutputStream() {
            override fun write(value: Int) = Unit

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                writes += buffer.copyOfRange(offset, offset + length)
                expectedWrites.countDown()
            }
        }
        val failure = AtomicReference<Throwable>()
        val dispatcher = AndroidScreenControlDispatcher(AndroidScreenControlWriter(output)) {
            failure.compareAndSet(null, it)
        }

        assertTrue(forwardScreenImeDeletion(dispatcher, beforeLength = 2, afterLength = 1))
        assertTrue("IME deletion writes did not finish", expectedWrites.await(5, TimeUnit.SECONDS))
        dispatcher.close()

        assertEquals(null, failure.get())
        assertEquals(3, writes.size)
        assertKeyFrame(writes[0].copyOfRange(0, 14), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
        assertKeyFrame(writes[1].copyOfRange(0, 14), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
        assertKeyFrame(
            writes[2].copyOfRange(0, 14),
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_FORWARD_DEL,
        )
    }

    @Test
    fun `IME deletion rejects negative or unbounded requests`() {
        val writes = AtomicInteger()
        val dispatcher = AndroidScreenControlDispatcher(
            AndroidScreenControlWriter(
                object : OutputStream() {
                    override fun write(value: Int) {
                        writes.incrementAndGet()
                    }
                },
            ),
        ) { }

        assertFalse(forwardScreenImeDeletion(dispatcher, -1, 0))
        assertFalse(forwardScreenImeDeletion(dispatcher, 33, 0))
        dispatcher.close()
        assertEquals(0, writes.get())
    }

    @Test
    fun `dispatcher rejects oversized text without failing the channel`() {
        val writes = AtomicInteger()
        val failures = AtomicInteger()
        val output = object : OutputStream() {
            override fun write(value: Int) {
                writes.incrementAndGet()
            }
        }
        val dispatcher = AndroidScreenControlDispatcher(AndroidScreenControlWriter(output)) {
            failures.incrementAndGet()
        }

        assertFalse(dispatcher.sendText("€".repeat(101)))
        assertEquals(0, writes.get())
        assertEquals(0, failures.get())
        dispatcher.close()
    }

    @Test
    fun `terminal touch supersedes queued moves without reordering down and up`() {
        val writes = Collections.synchronizedList(mutableListOf<ByteArray>())
        val firstWrite = AtomicBoolean(true)
        val firstWriteStarted = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val expectedWrites = CountDownLatch(2)
        val output = object : OutputStream() {
            override fun write(value: Int) = Unit

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                writes += buffer.copyOfRange(offset, offset + length)
                expectedWrites.countDown()
                if (firstWrite.compareAndSet(true, false)) {
                    firstWriteStarted.countDown()
                    if (!releaseFirstWrite.await(5, TimeUnit.SECONDS)) {
                        throw IOException("test timed out waiting to release first write")
                    }
                }
            }
        }
        val failure = AtomicReference<Throwable>()
        val dispatcher = AndroidScreenControlDispatcher(AndroidScreenControlWriter(output)) {
            failure.compareAndSet(null, it)
        }

        assertTrue(dispatcher.sendTouches(listOf(touch(MotionEvent.ACTION_DOWN, x = 10))))
        assertTrue("DOWN did not enter the writer", firstWriteStarted.await(5, TimeUnit.SECONDS))
        assertTrue(dispatcher.sendTouches(listOf(touch(MotionEvent.ACTION_MOVE, x = 20))))
        assertTrue(dispatcher.sendTouches(listOf(touch(MotionEvent.ACTION_MOVE, x = 30))))
        assertTrue(dispatcher.sendTouches(listOf(touch(MotionEvent.ACTION_UP, x = 30))))
        releaseFirstWrite.countDown()

        assertTrue("coalesced control writes did not finish", expectedWrites.await(5, TimeUnit.SECONDS))
        dispatcher.close()
        assertEquals(null, failure.get())
        assertEquals(2, writes.size)
        assertTouchFrame(writes[0], MotionEvent.ACTION_DOWN, 10)
        assertTouchFrame(writes[1], MotionEvent.ACTION_UP, 30)
    }

    @Test
    fun `dispatcher preserves hide and show transitions for a fresh video boundary`() {
        val writes = Collections.synchronizedList(mutableListOf<ByteArray>())
        val firstWriteStarted = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val expectedWrites = CountDownLatch(3)
        val firstWrite = AtomicBoolean(true)
        val output = object : OutputStream() {
            override fun write(value: Int) = Unit

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                writes += buffer.copyOfRange(offset, offset + length)
                expectedWrites.countDown()
                if (firstWrite.compareAndSet(true, false)) {
                    firstWriteStarted.countDown()
                    if (!releaseFirstWrite.await(5, TimeUnit.SECONDS)) {
                        throw IOException("test timed out waiting to release first write")
                    }
                }
            }
        }
        val failure = AtomicReference<Throwable>()
        val dispatcher = AndroidScreenControlDispatcher(AndroidScreenControlWriter(output)) {
            failure.compareAndSet(null, it)
        }

        assertTrue(dispatcher.togglePower())
        assertTrue("first write did not start", firstWriteStarted.await(5, TimeUnit.SECONDS))
        assertTrue(dispatcher.setVideoVisible(false))
        assertTrue(dispatcher.setVideoVisible(true))
        releaseFirstWrite.countDown()

        assertTrue("visibility write did not finish", expectedWrites.await(5, TimeUnit.SECONDS))
        dispatcher.close()
        assertEquals(null, failure.get())
        assertEquals(3, writes.size)
        assertArrayEquals(byteArrayOf(65, 0), writes[1])
        assertArrayEquals(byteArrayOf(65, 1), writes[2])
    }

    @Test
    fun `dispatcher fails closed once when the writer throws`() {
        val expected = IOException("broken control channel")
        val outputClosed = CountDownLatch(1)
        val output = object : OutputStream() {
            override fun write(value: Int) = throw expected

            override fun write(buffer: ByteArray, offset: Int, length: Int) = throw expected

            override fun close() {
                outputClosed.countDown()
            }
        }
        val failure = AtomicReference<Throwable>()
        val failureCount = AtomicInteger()
        val failed = CountDownLatch(1)
        val dispatcher = AndroidScreenControlDispatcher(AndroidScreenControlWriter(output)) {
            failure.set(it)
            failureCount.incrementAndGet()
            failed.countDown()
        }

        assertTrue(dispatcher.togglePower())
        assertTrue("failure callback was not delivered", failed.await(5, TimeUnit.SECONDS))
        assertTrue("failed writer was not closed", outputClosed.await(5, TimeUnit.SECONDS))
        assertSame(expected, failure.get())
        assertEquals(1, failureCount.get())
        assertFalse(dispatcher.sendKeyPress(KeyEvent.KEYCODE_BACK))
        dispatcher.close()
        assertEquals(1, failureCount.get())
    }

    @Test(timeout = 5_000)
    fun `essential-only queue saturation fails without deadlocking a blocked write`() {
        val writeStarted = CountDownLatch(1)
        val outputClosed = CountDownLatch(1)
        val output = object : OutputStream() {
            override fun write(value: Int) = Unit

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                writeStarted.countDown()
                try {
                    CountDownLatch(1).await()
                } catch (interrupted: InterruptedException) {
                    throw IOException("interrupted blocked write", interrupted)
                }
            }

            override fun close() {
                outputClosed.countDown()
            }
        }
        val failure = AtomicReference<Throwable>()
        val failed = CountDownLatch(1)
        val dispatcher = AndroidScreenControlDispatcher(AndroidScreenControlWriter(output)) {
            failure.set(it)
            failed.countDown()
        }

        assertTrue(dispatcher.togglePower())
        assertTrue(writeStarted.await(2, TimeUnit.SECONDS))
        repeat(64) { assertTrue(dispatcher.togglePower()) }
        assertFalse(dispatcher.sendKeyPress(KeyEvent.KEYCODE_HOME))

        assertTrue(failed.await(2, TimeUnit.SECONDS))
        assertEquals("screen control queue is saturated", failure.get().message)
        assertTrue(outputClosed.await(2, TimeUnit.SECONDS))
        assertFalse(dispatcher.togglePower())
    }

    private fun assertKeyFrame(bytes: ByteArray, action: Int, keyCode: Int) {
        val frame = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0, frame.get().toInt())
        assertEquals(action, frame.get().toInt())
        assertEquals(keyCode, frame.int)
        assertEquals(0, frame.int)
        assertEquals(0, frame.int)
    }

    private fun touch(action: Int, x: Int) = AndroidScreenTouch(
        action = action,
        pointerId = 0,
        x = x,
        y = 40,
        sourceWidth = 100,
        sourceHeight = 200,
        pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f,
    )

    private fun assertTouchFrame(bytes: ByteArray, action: Int, x: Int) {
        assertEquals(32, bytes.size)
        val frame = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(2, frame.get().toInt())
        assertEquals(action, frame.get().toInt())
        assertEquals(0L, frame.long)
        assertEquals(x, frame.int)
    }
}
