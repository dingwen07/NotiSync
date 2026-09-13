package net.extrawdw.notisync.peer.transport

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.extrawdw.notisync.peer.ports.NoIntegrityEvidenceProvider
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.IntegrityVerificationResponse
import net.extrawdw.notisync.protocol.LiveDeliveryDisposition
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.WsChallenge
import net.extrawdw.notisync.protocol.WsKind
import net.extrawdw.notisync.protocol.WsMessage
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrokerClientLiveDeliveryTest {
    @Test
    fun sessionChannelCancellationReconnectsAndAcknowledgesRedelivery() = runBlocking {
        withBroker { fixture ->
            val handled = AtomicInteger()
            // A session-owned channel can be cancelled while the receive job is still active.
            // Inject this failure deterministically, without racing a network close against an ACK.
            val closedSessionChannel = Channel<Unit>().apply {
                cancel(CancellationException("WebSocket session closed"))
            }
            val receiver = fixture.scope.launch {
                fixture.broker.runLiveDelivery {
                    if (handled.incrementAndGet() == 1) {
                        runBlocking { closedSessionChannel.send(Unit) }
                    }
                    LiveDeliveryDisposition.ACK
                }
            }
            try {
                withTimeout(10_000) { fixture.acknowledged.await() }

                assertEquals(2, handled.get())
                assertEquals(2, fixture.attempts.get())
                assertEquals(listOf(true, false, true), fixture.states.toList())
                assertTrue(receiver.isActive)
                assertTrue(fixture.parent.isActive)
            } finally {
                withTimeout(5_000) { receiver.cancelAndJoin() }
            }
            assertEquals(listOf(true, false, true, false), fixture.states.toList())
        }
    }

    @Test
    fun callerCancellationStopsReceiverAndReportsDisconnection() = runBlocking {
        withBroker { fixture ->
            val receiver = fixture.scope.launch {
                fixture.broker.runLiveDelivery { LiveDeliveryDisposition.ACK }
            }
            try {
                withTimeout(5_000) { fixture.acknowledged.await() }
            } finally {
                withTimeout(5_000) { receiver.cancelAndJoin() }
            }

            assertTrue(receiver.isCancelled)
            assertTrue(fixture.parent.isActive)
            assertEquals(1, fixture.attempts.get())
            assertEquals(listOf(true, false), fixture.states.toList())
        }
    }

    private suspend fun withBroker(block: suspend (Fixture) -> Unit) {
        val identity = SoftwareIdentitySigner.generate()
        val fixture = Fixture()
        val envelope = Envelope(
            typ = MessageType.NOTIFICATION,
            signerId = identity.clientId,
            messageId = "live-delivery-test",
            seq = 1,
            createdAt = 1,
            bodyCiphertext = byteArrayOf(1),
            recipients = emptyList(),
        )
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing {
                webSocket("/v2/connect") {
                    fixture.attempts.incrementAndGet()
                    send(Frame.Text(ProtocolCodec.encodeToJson(WsChallenge("test-nonce"))))
                    incoming.receive() // This fixture tests transport lifecycle, not authentication.
                    send(Frame.Text(ProtocolCodec.encodeToJson(WsMessage(
                        kind = WsKind.DELIVER,
                        envelopeB64 = Base64.getEncoder().encodeToString(ProtocolCodec.encodeToCbor(envelope)),
                    ))))
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val message = ProtocolCodec.decodeFromJson<WsMessage>(frame.readText())
                            if (message.kind == WsKind.ACK && message.messageId == envelope.messageId) {
                                fixture.acknowledged.complete(Unit)
                            }
                        }
                    }
                }
            }
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            fixture.broker = BrokerClient(
                signer = identity,
                operationalSigner = { error("unused by live delivery") },
                baseUrlProvider = { "http://127.0.0.1:$port" },
                integrity = NoIntegrityEvidenceProvider,
                clientKeyEpochProvider = { error("unused with cached auth") },
                tokenStore = object : AuthTokenStore {
                    override fun load() = IntegrityVerificationResponse(
                        token = "test-token", clientId = identity.clientId,
                        expiresAt = System.currentTimeMillis() + 7 * 86_400_000L,
                    )
                    override fun save(token: IntegrityVerificationResponse?) = Unit
                },
                scope = fixture.scope,
                onWebSocketConnectionChanged = { fixture.states.add(it) },
            )
            try {
                block(fixture)
            } finally {
                fixture.parent.cancelAndJoin()
                fixture.broker.close()
            }
        } finally {
            server.stop(0, 1_000)
        }
    }

    private class Fixture {
        val parent = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + parent)
        val states = CopyOnWriteArrayList<Boolean>()
        val attempts = AtomicInteger()
        val acknowledged = CompletableDeferred<Unit>()
        lateinit var broker: BrokerClient
    }
}
