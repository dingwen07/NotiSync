package net.extrawdw.apps.notisync.screen

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.screen.PskRegistry
import net.extrawdw.notisync.screen.ScreenChannel
import net.extrawdw.notisync.screen.ScreenConnectionCandidate
import net.extrawdw.notisync.screen.ScreenSessionListener
import net.extrawdw.notisync.screen.SecureChannelPair
import net.extrawdw.notisync.screen.SecureSessionChannel
import net.extrawdw.notisync.screen.SessionDescriptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AndroidScreenListenerOwnershipTest {
    @Test
    fun successfulRaceClosesLoserPairAndPreservesWinnerLifetime() = runBlocking {
        val firstPair = TestPair("first")
        val secondPair = TestPair("second")
        val bothStarted = CountDownLatch(2)
        val releaseBoth = CountDownLatch(1)
        val releaseFailure = AtomicReference<Throwable>()
        val first = TestListener(accept = {
            bothStarted.countDown()
            releaseBoth.awaitOrThrow()
            firstPair.pair
        })
        val second = TestListener(accept = {
            bothStarted.countDown()
            releaseBoth.awaitOrThrow()
            secondPair.pair
        })
        val releaser = thread(isDaemon = true, name = "screen-listener-test-release") {
            try {
                bothStarted.awaitOrThrow()
            } catch (error: Throwable) {
                releaseFailure.set(error)
            } finally {
                releaseBoth.countDown()
            }
        }
        val registry = PskRegistry()

        try {
            val accepted = acceptFirstPair(
                listeners = listOf(first, second),
                sessionId = SESSION_ID,
                registry = registry,
                timeout = TEST_TIMEOUT,
            )
            releaser.join(TEST_TIMEOUT.toMillis())
            releaseFailure.get()?.let { throw AssertionError("listeners did not enter race", it) }

            val winnerPair = if (accepted.listener === first) firstPair else secondPair
            val loserPair = if (accepted.listener === first) secondPair else firstPair
            val winnerListener = if (accepted.listener === first) first else second
            val loserListener = if (accepted.listener === first) second else first

            assertFalse("returned pair was closed before handoff", winnerPair.isClosed)
            assertFalse("winning listener lifetime was not preserved", winnerListener.isClosed)
            assertTrue("losing listener was not closed", loserListener.isClosed)
            assertTrue("losing authenticated pair leaked", loserPair.isClosed)

            accepted.listener.close()
            accepted.pair.close()
        } finally {
            releaseBoth.countDown()
            releaser.join(TEST_TIMEOUT.toMillis())
            first.close()
            second.close()
            firstPair.pair.close()
            secondPair.pair.close()
            registry.close()
        }
    }

    @Test
    fun loserFailureAfterQueueCloseDoesNotCancelWinner() = runBlocking {
        val winnerPair = TestPair("queue-close-winner")
        val loserStarted = CountDownLatch(1)
        val releaseLoser = CountDownLatch(1)
        val winner = TestListener(accept = {
            loserStarted.awaitOrThrow()
            winnerPair.pair
        })
        val loser = TestListener(
            accept = {
                loserStarted.countDown()
                releaseLoser.awaitOrThrow()
                throw IOException("expected loser shutdown")
            },
            onClose = { releaseLoser.countDown() },
        )
        val registry = PskRegistry()

        try {
            val accepted = acceptFirstPair(
                listeners = listOf(winner, loser),
                sessionId = SESSION_ID,
                registry = registry,
                timeout = TEST_TIMEOUT,
            )

            assertTrue("wrong listener won", accepted.listener === winner)
            assertFalse("winner pair was closed", winnerPair.isClosed)
            assertFalse("winner listener was closed", winner.isClosed)
            assertTrue("loser was not closed", loser.isClosed)

            accepted.listener.close()
            accepted.pair.close()
        } finally {
            releaseLoser.countDown()
            winner.close()
            loser.close()
            winnerPair.pair.close()
            registry.close()
        }
    }

    @Test
    fun cancellationWhileStoppingLoserClosesSelectedPairAndWinnerListener() = runBlocking {
        val selectedPair = TestPair("selected")
        val loserStarted = CountDownLatch(1)
        val loserCloseObserved = CountDownLatch(1)
        val allowLoserToFinish = CountDownLatch(1)
        val winner = TestListener(accept = {
            loserStarted.awaitOrThrow()
            selectedPair.pair
        })
        val loser = TestListener(
            accept = {
                loserStarted.countDown()
                allowLoserToFinish.awaitOrThrow()
                throw IOException("losing listener stopped")
            },
            onClose = { loserCloseObserved.countDown() },
        )
        val registry = PskRegistry()
        val request = async(Dispatchers.Default) {
            acceptFirstPair(
                listeners = listOf(winner, loser),
                sessionId = SESSION_ID,
                registry = registry,
                timeout = TEST_TIMEOUT,
            )
        }

        try {
            assertTrue(
                "winner was not selected and loser teardown did not begin",
                loserCloseObserved.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
            )
            request.cancel()
            allowLoserToFinish.countDown()
            try {
                request.await()
                fail("cancelled listener race returned a pair")
            } catch (_: CancellationException) {
                // Expected: cancellation arrived before ownership reached the caller.
            }

            assertTrue("selected pair leaked after cancellation", selectedPair.isClosed)
            assertTrue("winning listener leaked after cancellation", winner.isClosed)
            assertTrue("losing listener was not closed", loser.isClosed)
        } finally {
            request.cancel()
            allowLoserToFinish.countDown()
            winner.close()
            loser.close()
            selectedPair.pair.close()
            registry.close()
        }
    }

    @Test
    fun producerClosesPairWhenCancellationPreventsQueueHandoff() = runBlocking {
        val producedPair = TestPair("producer")
        val acceptStarted = CountDownLatch(1)
        val allowAcceptToReturn = CountDownLatch(1)
        val listener = TestListener(
            accept = {
                acceptStarted.countDown()
                allowAcceptToReturn.awaitOrThrow()
                producedPair.pair
            },
            onClose = { allowAcceptToReturn.countDown() },
        )
        val registry = PskRegistry()
        val request = async(Dispatchers.Default) {
            acceptFirstPair(
                listeners = listOf(listener),
                sessionId = SESSION_ID,
                registry = registry,
                timeout = TEST_TIMEOUT,
            )
        }

        try {
            assertTrue(
                "listener did not begin accepting",
                acceptStarted.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
            )
            request.cancel()
            try {
                request.await()
                fail("cancelled listener race returned a pair")
            } catch (_: CancellationException) {
                // Expected: the producer owns and closes a pair that it cannot enqueue.
            }

            assertTrue("listener was not closed during cancellation", listener.isClosed)
            assertTrue("producer-owned pair leaked after failed queue handoff", producedPair.isClosed)
        } finally {
            request.cancel()
            allowAcceptToReturn.countDown()
            listener.close()
            producedPair.pair.close()
            registry.close()
        }
    }

    private class TestListener(
        private val accept: () -> SecureChannelPair,
        private val onClose: () -> Unit = {},
    ) : ScreenSessionListener {
        private val closed = AtomicBoolean()

        override val candidates: List<ScreenConnectionCandidate> = emptyList()
        val isClosed: Boolean get() = closed.get()

        override fun acceptPair(
            sessionId: String,
            registry: PskRegistry,
            timeout: Duration,
            handshakeTimeout: Duration,
            maximumAcceptedSockets: Int,
        ): SecureChannelPair = accept()

        override fun close() {
            if (closed.compareAndSet(false, true)) onClose()
        }
    }

    private class TestPair(name: String) {
        private val videoSocket = Socket()
        private val controlSocket = Socket()
        private val descriptor = SessionDescriptor(
            sessionId = "$SESSION_ID-$name",
            sourcePeerId = "source",
            requesterPeerId = "requester",
            issuedAtEpochMillis = 1_000L,
            expiresAtEpochMillis = 61_000L,
            codec = "h264",
            controlEnabled = true,
            clipboardEnabled = false,
            maxDimension = 1_920,
            maxFps = 60,
            videoBitrateBps = 8_000_000,
        )

        val pair = SecureChannelPair(
            video = testChannel(ScreenChannel.VIDEO, videoSocket),
            control = testChannel(ScreenChannel.CONTROL, controlSocket),
        )
        val isClosed: Boolean get() = videoSocket.isClosed && controlSocket.isClosed

        private fun testChannel(channel: ScreenChannel, socket: Socket): SecureSessionChannel {
            val constructor = SecureSessionChannel::class.java.declaredConstructors.single()
            constructor.isAccessible = true
            val closeProtocol: () -> Unit = {}
            return constructor.newInstance(
                descriptor,
                channel,
                InputStream.nullInputStream(),
                OutputStream.nullOutputStream(),
                closeProtocol,
                socket,
            ) as SecureSessionChannel
        }
    }

    private companion object {
        const val SESSION_ID = "ownership-session"
        val TEST_TIMEOUT: Duration = Duration.ofSeconds(5)

        fun CountDownLatch.awaitOrThrow() {
            if (!await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw IOException("test latch timed out")
            }
        }
    }
}
