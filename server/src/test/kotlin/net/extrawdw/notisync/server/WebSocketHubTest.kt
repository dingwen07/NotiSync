package net.extrawdw.notisync.server

import io.ktor.server.application.ApplicationCall
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.readText
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.server.delivery.WebSocketHub
import net.extrawdw.notisync.server.delivery.WsConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSocketHubTest {
    private val clientId = ClientId("test-recipient")

    @Test
    fun cancelledSenderLeavesBlockedRecipientRegisteredAndUsable() = runBlocking {
        val hub = WebSocketHub()
        val session = TestSession()
        try {
            hub.register(WsConnection(clientId, session))
            session.outgoing.send(Frame.Text("occupy slot"))
            var returnedNormally = false
            val sender = launch(start = CoroutineStart.UNDISPATCHED) {
                hub.deliverText(clientId, "cancelled send")
                returnedNormally = true
            }
            assertFalse(sender.isCompleted)

            sender.cancelAndJoin()

            assertFalse(returnedNormally)
            assertTrue(hub.isOnline(clientId))
            assertTrue(session.job.isActive)
            assertEquals("occupy slot", (session.outgoing.receive() as Frame.Text).readText())
            assertTrue(hub.deliverText(clientId, "next send"))
            assertEquals("next send", (session.outgoing.receive() as Frame.Text).readText())
        } finally {
            session.job.cancel()
        }
    }

    @Test
    fun cancelledRecipientChannelEvictsAndTerminatesSessionWithoutCancellingSender() = runBlocking {
        val hub = WebSocketHub()
        val session = TestSession()
        try {
            hub.register(WsConnection(clientId, session))
            session.outgoing.cancel(CancellationException("recipient channel closed"))

            assertFalse(hub.deliverText(clientId, "message"))

            assertFalse(hub.isOnline(clientId))
            assertTrue(session.job.isCancelled)
            assertTrue(coroutineContext[Job]!!.isActive)
        } finally {
            session.job.cancel()
        }
    }

    @Test
    fun failedRecipientSessionClosesWhileOtherSessionsContinueReceiving() = runBlocking {
        val hub = WebSocketHub()
        val broken = TestSession()
        val healthy = TestSession()
        try {
            hub.register(WsConnection(clientId, broken))
            hub.register(WsConnection(clientId, healthy))
            broken.outgoing.close(IOException("write failed"))

            assertTrue(hub.deliverText(clientId, "first"))

            assertTrue(broken.job.isCancelled)
            assertTrue(healthy.job.isActive)
            assertTrue(hub.isOnline(clientId))
            assertEquals("first", (healthy.outgoing.receive() as Frame.Text).readText())
            assertTrue(hub.deliverText(clientId, "second"))
            assertEquals("second", (healthy.outgoing.receive() as Frame.Text).readText())
        } finally {
            broken.job.cancel()
            healthy.job.cancel()
        }
    }

    /** Uses Ktor's default send implementation and real channels/cancellation without a network. */
    private class TestSession : DefaultWebSocketServerSession {
        val job = Job()
        override val coroutineContext = job
        override val call: ApplicationCall get() = error("no HTTP call in this session")
        override val incoming = Channel<Frame>()
        override val outgoing = Channel<Frame>(1)
        override val closeReason = CompletableDeferred<CloseReason?>()
        override val extensions = emptyList<WebSocketExtension<*>>()
        override var masking = false
        override var maxFrameSize = Long.MAX_VALUE
        override var pingIntervalMillis = 0L
        override var timeoutMillis = 0L
        @OptIn(io.ktor.utils.io.InternalAPI::class)
        override fun start(negotiatedExtensions: List<WebSocketExtension<*>>) = Unit
        override suspend fun flush() = Unit
        @Suppress("OVERRIDE_DEPRECATION")
        override fun terminate() { job.cancel() }
    }
}
