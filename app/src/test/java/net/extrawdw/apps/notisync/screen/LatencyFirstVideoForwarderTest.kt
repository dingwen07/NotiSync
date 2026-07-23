package net.extrawdw.apps.notisync.screen

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import net.extrawdw.notisync.protocol.ScreenMirrorCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyFirstVideoForwarderTest {
    @Test
    fun healthyForwardingPreservesScrcpyRecordBytes() {
        val records = ByteBuffer.allocate(12 + 2 + 12 + 3)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(1L shl 62)
            .putInt(2)
            .put(byteArrayOf(10, 11))
            .putLong((1L shl 61) or 42L)
            .putInt(3)
            .put(byteArrayOf(20, 21, 22))
            .array()
        val output = ByteArrayOutputStream()

        LatencyFirstVideoForwarder(
            input = ByteArrayInputStream(records),
            output = output,
            preamble = h264Preamble(),
            codec = ScreenMirrorCodec.H264,
            targetBitrateBps = 8_000_000,
            recoverVideo = { false },
        ).forward()

        assertArrayEquals(records, output.toByteArray())
    }

    @Test
    fun slowNetworkWriterRequestsLowerBitrateSyncFrame() {
        val records = ByteArrayOutputStream().apply {
            write(packetBytes(codecConfig = true, keyFrame = false, pts = 0, value = 1))
            repeat(8) { index ->
                write(packetBytes(codecConfig = false, keyFrame = false, pts = index + 1L, value = index + 2))
            }
            write(packetBytes(codecConfig = false, keyFrame = true, pts = 99, value = 99))
        }.toByteArray()
        val decisions = Collections.synchronizedList(mutableListOf<VideoRecoveryDecision>())

        LatencyFirstVideoForwarder(
            input = ByteArrayInputStream(records),
            output = SlowOutputStream(45),
            preamble = h264Preamble(),
            codec = ScreenMirrorCodec.H264,
            targetBitrateBps = 8_000_000,
            recoverVideo = { decision -> decisions.add(decision); true },
        ).forward()

        assertTrue(decisions.isNotEmpty())
        assertTrue(decisions.any { it.bitrateBps < it.previousBitrateBps })
    }

    @Test
    fun congestionWithoutAcceptedRecoveryPreservesPredictiveFrames() {
        val records = ByteArrayOutputStream().apply {
            write(packetBytes(codecConfig = true, keyFrame = false, pts = 0, value = 1))
            repeat(12) { index ->
                write(
                    packetBytes(
                        codecConfig = false,
                        keyFrame = index == 0,
                        pts = index + 1L,
                        value = index + 2,
                    ),
                )
            }
        }.toByteArray()
        val output = SlowOutputStream(45)

        LatencyFirstVideoForwarder(
            input = ByteArrayInputStream(records),
            output = output,
            preamble = h264Preamble(),
            codec = ScreenMirrorCodec.H264,
            targetBitrateBps = 8_000_000,
            recoverVideo = { false },
        ).forward()

        assertArrayEquals(records, output.toByteArray())
    }

    @Test
    fun congestionUsesMeasuredNetworkBudgetAndRecoversGradually() {
        val controller = AdaptiveVideoBitrateController(8_000_000)

        val congested = requireNotNull(
            controller.onCongestion(
                reason = VideoCongestionReason.WRITE_STALL,
                writtenBytes = 100_000,
                writeDurationNanos = 1_000_000_000L,
                nowNanos = 1_000_000_000L,
            ),
        )

        assertEquals(640_000, congested.bitrateBps)
        assertEquals(8_000_000, congested.previousBitrateBps)
        assertNull(
            controller.onCongestion(
                VideoCongestionReason.WRITE_STALL,
                writtenBytes = 10_000,
                writeDurationNanos = 100_000_000L,
                nowNanos = 1_100_000_000L,
            ),
        )
        assertNull(controller.onHealthyWrite(16_099_999_999L))
        assertEquals(800_000, controller.onHealthyWrite(16_100_000_000L)?.bitrateBps)
    }

    @Test
    fun closeDoesNotWaitForeverForStuckNetworkWriter() {
        val sink = BlockingVideoSink()
        val forwarder = LatencyFirstVideoForwarder(
            input = ByteArrayInputStream(
                packetBytes(codecConfig = false, keyFrame = true, pts = 1, value = 7),
            ),
            sink = sink,
            preamble = h264Preamble(),
            codec = ScreenMirrorCodec.H264,
            targetBitrateBps = 8_000_000,
            recoverVideo = { false },
            writerJoinTimeoutMillis = 50,
        )
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            runCatching { forwarder.forward() }.onFailure(failure::set)
        }.apply { start() }

        try {
            assertTrue(sink.started.await(1, TimeUnit.SECONDS))
            forwarder.close()
            worker.join(1_000)

            assertFalse("forwarder remained owned by a stuck network writer", worker.isAlive)
            assertTrue(failure.get() is IOException)
        } finally {
            sink.release.countDown()
            worker.join(1_000)
        }
    }

    @Test
    fun queueDropsPredictiveBacklogUntilFreshKeyFrame() {
        var now = 1L
        val queue = LatestDecodableVideoQueue { now++ }
        val config = packet(codecConfig = true, keyFrame = false, value = 1)
        assertNull(queue.tryEnqueue(config))
        assertNull(queue.tryEnqueue(packet(false, false, 2)))
        assertNull(queue.tryEnqueue(packet(false, false, 3)))
        assertNull(queue.tryEnqueue(packet(false, false, 4)))
        assertTrue(queue.tryEnqueue(packet(false, false, 5)) != null)

        queue.dropUntilNextKeyFrame()
        assertNull(queue.tryEnqueue(packet(false, false, 6)))
        assertNull(queue.tryEnqueue(packet(false, true, 7)))

        val replayedConfig = queue.take()
        val freshKeyFrame = queue.take()
        assertTrue(requireNotNull(replayedConfig).codecConfig)
        assertTrue(requireNotNull(freshKeyFrame).keyFrame)
        assertEquals(7, freshKeyFrame.payload.single().toInt())
        queue.finish()
        assertNull(queue.take())
    }

    @Test
    fun recoveryWakesReaderBlockedByFullVideoQueue() {
        var now = 1L
        val queue = LatestDecodableVideoQueue { now++ }
        assertNull(queue.tryEnqueue(packet(codecConfig = true, keyFrame = false, value = 1)))
        assertNull(queue.tryEnqueue(packet(codecConfig = false, keyFrame = true, value = 2)))
        assertNull(queue.tryEnqueue(packet(codecConfig = false, keyFrame = false, value = 3)))
        assertNull(queue.tryEnqueue(packet(codecConfig = false, keyFrame = false, value = 4)))
        val rejected = requireNotNull(
            queue.tryEnqueue(packet(codecConfig = false, keyFrame = false, value = 5)),
        )
        val enqueueStarted = CountDownLatch(1)
        val enqueueFinished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val reader = Thread {
            enqueueStarted.countDown()
            runCatching { queue.enqueueBlocking(rejected) }
                .onFailure(failure::set)
            enqueueFinished.countDown()
        }.apply { start() }

        try {
            assertTrue(enqueueStarted.await(1, TimeUnit.SECONDS))
            assertFalse(enqueueFinished.await(100, TimeUnit.MILLISECONDS))
            assertTrue(queue.isProducerBlocked())

            queue.dropUntilNextKeyFrame()

            assertTrue("video reader remained blocked after recovery", enqueueFinished.await(1, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertTrue(queue.isDroppingUntilKeyFrame())
            assertNull(queue.tryEnqueue(packet(codecConfig = false, keyFrame = true, value = 6)))
            assertFalse(queue.isDroppingUntilKeyFrame())
            assertTrue(requireNotNull(queue.take()).codecConfig)
            assertTrue(requireNotNull(queue.take()).keyFrame)
        } finally {
            queue.finish()
            reader.join(1_000)
        }
    }

    @Test
    fun encoderSessionBoundaryWaitsForItsOwnKeyFrame() {
        var now = 1L
        val queue = LatestDecodableVideoQueue { now++ }
        assertNull(queue.tryEnqueue(packet(codecConfig = true, keyFrame = false, value = 1)))
        assertNull(queue.tryEnqueue(packet(codecConfig = false, keyFrame = true, value = 2)))
        assertNull(
            queue.tryEnqueue(
                ScrcpyVideoRecord.Session(
                    dimensions = ScrcpySessionDimensions(width = 1080, height = 2400),
                    clientResize = false,
                ),
            ),
        )

        assertTrue(queue.isDroppingUntilKeyFrame())
        assertNull(queue.tryEnqueue(packet(codecConfig = false, keyFrame = false, value = 3)))
        assertNull(queue.tryEnqueue(packet(codecConfig = true, keyFrame = false, value = 4)))
        assertNull(queue.tryEnqueue(packet(codecConfig = false, keyFrame = true, value = 5)))

        assertFalse(queue.isDroppingUntilKeyFrame())
        assertTrue(requireNotNull(queue.take()).session)
        assertTrue(requireNotNull(queue.take()).codecConfig)
        assertTrue(requireNotNull(queue.take()).keyFrame)
    }

    private fun packet(codecConfig: Boolean, keyFrame: Boolean, value: Int) =
        ScrcpyVideoRecord.Packet(
            data = byteArrayOf(value.toByte()),
            presentationTimeUs = if (codecConfig) 0L else value.toLong(),
            codecConfig = codecConfig,
            keyFrame = keyFrame,
        )

    private fun h264Preamble(): ByteArray = ByteBuffer.allocate(ScrcpyVideoPreamble.SIZE_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(0x68_32_36_34)
        .putInt(Int.MIN_VALUE)
        .putInt(1080)
        .putInt(2400)
        .array()

    private fun packetBytes(
        codecConfig: Boolean,
        keyFrame: Boolean,
        pts: Long,
        value: Int,
    ): ByteArray {
        var flags = if (codecConfig) 1L shl 62 else pts
        if (keyFrame) flags = flags or (1L shl 61)
        return ByteBuffer.allocate(13)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(flags)
            .putInt(1)
            .put(value.toByte())
            .array()
    }

    private class SlowOutputStream(private val delayMillis: Long) : ByteArrayOutputStream() {
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            Thread.sleep(delayMillis)
            super.write(bytes, offset, length)
        }
    }

    private class BlockingVideoSink : VideoRecordSink {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun writePreamble(bytes: ByteArray) = Unit

        override fun writeRecord(record: QueuedVideoRecord) {
            started.countDown()
            while (release.count > 0) {
                try {
                    release.await(1, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    // Model a platform writer that ignores coroutine/thread interruption.
                }
            }
        }

        override fun flush() = Unit
        override fun close() = Unit
    }
}
