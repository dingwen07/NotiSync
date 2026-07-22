package net.extrawdw.notisync.server

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import net.extrawdw.notisync.protocol.ScreenRelayVideoWire
import net.extrawdw.notisync.server.delivery.ScreenRelayVideoQueue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenRelayVideoQueueTest {
    @Test
    fun overflowDropsPredictiveHistoryUntilFreshKeyFrame() = runTest {
        val queue = ScreenRelayVideoQueue(ScreenRelayVideoWire.MAX_MESSAGE_BYTES)
        val first = frame(ScreenRelayVideoWire.FLAG_DELTA, 0)
        val overflow = frame(ScreenRelayVideoWire.FLAG_DELTA, 1)

        assertNull(queue.enqueue(first).congestedThroughSequence)
        assertEquals(1L, queue.enqueue(overflow).congestedThroughSequence)
        assertEquals(
            2L,
            queue.enqueue(frame(ScreenRelayVideoWire.FLAG_DELTA, 2)).congestedThroughSequence,
        )
        val key = frame(ScreenRelayVideoWire.FLAG_KEY_FRAME, 3)
        assertNull(queue.enqueue(key).congestedThroughSequence)
        assertArrayEquals(key, queue.take())
        queue.close()
    }

    @Test
    fun keyAndConfigurationApplyBackpressureInsteadOfBeingDropped() = runTest {
        val queue = ScreenRelayVideoQueue(ScreenRelayVideoWire.MAX_MESSAGE_BYTES)
        val key = frame(ScreenRelayVideoWire.FLAG_KEY_FRAME, 0)
        val config = frame(ScreenRelayVideoWire.FLAG_CODEC_CONFIG, 1)
        queue.enqueue(key)

        val waiting = async { queue.enqueue(config) }
        testScheduler.runCurrent()
        assertArrayEquals(key, queue.take())
        assertNull(waiting.await().congestedThroughSequence)
        assertArrayEquals(config, queue.take())
        queue.close()
    }

    private fun frame(flags: Int, sequence: Long): ByteArray {
        val plaintextBytes = ScreenRelayVideoWire.MAX_FRAGMENT_PLAINTEXT_BYTES
        val header = ScreenRelayVideoWire.encodeHeader(
            flags = flags,
            recordSequence = sequence,
            recordBytes = plaintextBytes,
            fragmentOffset = 0,
            fragmentBytes = plaintextBytes,
        )
        return header + ByteArray(plaintextBytes + ScreenRelayVideoWire.AEAD_TAG_BYTES)
    }
}
