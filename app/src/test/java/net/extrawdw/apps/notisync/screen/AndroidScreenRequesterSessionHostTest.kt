package net.extrawdw.apps.notisync.screen

import android.view.KeyEvent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ScreenMirrorCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidScreenRequesterSessionHostTest {
    @Test
    fun sameSourceIsIdempotentAndStaleStopCannotCloseCurrentAttempt() {
        val fixture = HostFixture(emptySet())
        try {
            val attempt = fixture.host.start(SOURCE)

            assertEquals(attempt, fixture.host.start(SOURCE))
            assertThrows(IllegalStateException::class.java) { fixture.host.start(OTHER_SOURCE) }
            waitUntil { fixture.host.state.value.phase == AndroidScreenHostPhase.CONNECTED }
            assertFalse(fixture.host.stopIfAttempt("stale-attempt"))
            assertTrue(fixture.host.sendKeyPress(KeyEvent.KEYCODE_BACK))
            waitUntil { fixture.control.size() == KEY_PRESS_BYTES }

            assertTrue(fixture.host.stopIfAttempt(attempt, "test stop"))
            assertEquals(AndroidScreenHostPhase.ENDED, fixture.host.state.value.phase)
            assertEquals(attempt, fixture.host.state.value.attemptId)
            assertEquals(SOURCE, fixture.host.state.value.sourceId)
            assertEquals("test stop", fixture.host.state.value.detail)
            waitUntil { fixture.session.closeCount.get() == 1 }
            assertEquals(listOf(attempt to "test stop"), fixture.ownerCloses)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun sourceVisibilityCommandIsSentOnlyWhenAdvertised() {
        val capable = HostFixture(setOf(Capability.SCREEN_MIRROR_VIDEO_VISIBILITY_V1))
        try {
            capable.host.start(SOURCE)
            waitUntil { capable.host.state.value.phase == AndroidScreenHostPhase.CONNECTED }
            waitUntil { capable.control.size() == VIDEO_VISIBILITY_FRAME_BYTES }
            assertArrayEquals(byteArrayOf(65, 0), capable.control.bytes())
        } finally {
            capable.close()
        }

        val legacy = HostFixture(emptySet())
        try {
            legacy.host.start(SOURCE)
            waitUntil { legacy.host.state.value.phase == AndroidScreenHostPhase.CONNECTED }
            Thread.sleep(100)
            assertEquals(0, legacy.control.size())
        } finally {
            legacy.close()
        }
    }

    @Test
    fun stopPublishesTerminalStateWithoutWaitingForTransportCleanup() {
        val cleanupGate = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        val fixture = HostFixture(
            capabilities = emptySet(),
            physicalCloseStarted = cleanupStarted,
            physicalCloseGate = cleanupGate,
        )
        val stopReturned = CountDownLatch(1)
        val stopResult = AtomicBoolean()
        try {
            val attempt = fixture.host.start(SOURCE)
            waitUntil { fixture.host.state.value.phase == AndroidScreenHostPhase.CONNECTED }

            Thread {
                stopResult.set(fixture.host.stopIfAttempt(attempt, "non-blocking stop"))
                stopReturned.countDown()
            }.start()

            assertTrue(cleanupStarted.await(1, TimeUnit.SECONDS))
            assertTrue("stop waited for transport cleanup", stopReturned.await(1, TimeUnit.SECONDS))
            assertTrue(stopResult.get())
            assertEquals(AndroidScreenHostPhase.ENDED, fixture.host.state.value.phase)
            assertEquals(attempt, fixture.host.state.value.attemptId)
        } finally {
            cleanupGate.countDown()
            fixture.close()
        }
    }

    @Test
    fun hostCloseRetainsTheExactTerminalSnapshotForHiddenViewers() {
        val fixture = HostFixture(emptySet())
        try {
            val attempt = fixture.host.start(SOURCE)
            waitUntil { fixture.host.state.value.phase == AndroidScreenHostPhase.CONNECTED }

            fixture.host.close()

            val terminal = fixture.host.state.value
            assertEquals(AndroidScreenHostPhase.ENDED, terminal.phase)
            assertEquals(attempt, terminal.attemptId)
            assertEquals(SOURCE, terminal.sourceId)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun manualRelayFallbackReplacesTheDirectAttemptForTheSameSource() {
        val fixture = HostFixture(emptySet())
        try {
            val directAttempt = fixture.host.start(SOURCE)
            waitUntil { fixture.host.state.value.phase == AndroidScreenHostPhase.CONNECTED }

            val relayAttempt = fixture.host.start(SOURCE, AndroidScreenConnectionMode.BROKER_RELAY)

            assertNotEquals(directAttempt, relayAttempt)
            waitUntil {
                fixture.host.state.value.phase == AndroidScreenHostPhase.CONNECTED &&
                    fixture.host.state.value.connectionMode == AndroidScreenConnectionMode.BROKER_RELAY
            }
            waitUntil { fixture.session.closeCount.get() == 1 }
            assertEquals(1, fixture.directOpens.get())
            assertEquals(1, fixture.relayOpens.get())
        } finally {
            fixture.close()
        }
    }

    private class HostFixture(
        capabilities: Set<Capability>,
        private val physicalCloseStarted: CountDownLatch? = null,
        private val physicalCloseGate: CountDownLatch? = null,
    ) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val control = RecordingOutputStream()
        val relayControl = RecordingOutputStream()
        val directOpens = AtomicInteger()
        val relayOpens = AtomicInteger()
        val session = FakeSession(
            sourceCapabilities = capabilities,
            controlOutput = control,
            closeStarted = physicalCloseStarted,
            closeGate = physicalCloseGate,
        )
        val relaySession = FakeSession(
            sourceCapabilities = capabilities,
            controlOutput = relayControl,
        )
        val ownerCloses = mutableListOf<Pair<String, String>>()
        val host = AndroidScreenRequesterSessionHost(
            scope = scope,
            hardwareDecoderName = { null },
            openSession = { _, _ ->
                directOpens.incrementAndGet()
                session
            },
            openRelaySession = { _, _ ->
                relayOpens.incrementAndGet()
                relaySession
            },
            closeOwner = { owner, detail ->
                synchronized(ownerCloses) { ownerCloses += owner to detail }
            },
            requesterState = {
                AndroidScreenRequesterState(phase = AndroidScreenRequesterPhase.CONNECTED)
            },
        )

        override fun close() {
            physicalCloseGate?.countDown()
            host.close()
            session.close()
            relaySession.close()
            scope.cancel()
        }
    }

    private class FakeSession(
        override val sourceCapabilities: Set<Capability>,
        override val controlOutput: OutputStream,
        private val closeStarted: CountDownLatch? = null,
        private val closeGate: CountDownLatch? = null,
    ) : AndroidScreenViewerSession {
        private val video = PreambleThenBlockingInputStream()
        override val sessionId = "screen:test"
        override val sourceId = SOURCE
        override val sourceName = "Test source"
        override val codec = ScreenMirrorCodec.H264
        override val videoInput: InputStream = video
        override val controlInput: InputStream = ByteArrayInputStream(byteArrayOf())
        val closeCount = AtomicInteger()
        private val closed = AtomicBoolean()

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                closeStarted?.countDown()
                closeGate?.await()
                closeCount.incrementAndGet()
                video.close()
                controlOutput.close()
            }
        }
    }

    private class RecordingOutputStream : OutputStream() {
        private val delegate = ByteArrayOutputStream()
        private var closed = false

        @Synchronized
        override fun write(value: Int) {
            check(!closed)
            delegate.write(value)
        }

        @Synchronized
        override fun write(data: ByteArray, offset: Int, length: Int) {
            check(!closed)
            delegate.write(data, offset, length)
        }

        @Synchronized
        override fun close() {
            closed = true
        }

        @Synchronized
        fun size(): Int = delegate.size()

        @Synchronized
        fun bytes(): ByteArray = delegate.toByteArray()
    }

    private class PreambleThenBlockingInputStream : InputStream() {
        private val prefix = ByteBuffer.allocate(ScrcpyVideoPreamble.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(H264_WIRE_ID)
            .putInt(Int.MIN_VALUE)
            .putInt(1080)
            .putInt(2400)
            .array()
        private var offset = 0
        private val lock = Object()

        @Volatile
        private var closed = false

        override fun read(): Int {
            val target = ByteArray(1)
            return if (read(target, 0, 1) < 0) -1 else target[0].toInt() and 0xff
        }

        override fun read(target: ByteArray, targetOffset: Int, length: Int): Int {
            synchronized(lock) {
                if (offset < prefix.size) {
                    val count = minOf(length, prefix.size - offset)
                    prefix.copyInto(target, targetOffset, offset, offset + count)
                    offset += count
                    return count
                }
                while (!closed) lock.wait()
                return -1
            }
        }

        override fun close() {
            synchronized(lock) {
                closed = true
                lock.notifyAll()
            }
        }
    }

    private fun waitUntil(timeoutMillis: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!condition()) {
            if (System.nanoTime() >= deadline) error("condition was not met before timeout")
            Thread.sleep(5)
        }
    }

    private companion object {
        val SOURCE = ClientId("source")
        val OTHER_SOURCE = ClientId("other-source")
        const val H264_WIRE_ID = 0x68_32_36_34
        const val KEY_PRESS_BYTES = 28
        const val VIDEO_VISIBILITY_FRAME_BYTES = 2
    }
}
