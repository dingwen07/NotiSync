package net.extrawdw.notisync.server

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.testing.testApplication
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import java.net.ServerSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import net.extrawdw.notisync.peer.pairing.BrokerPairingClient
import net.extrawdw.notisync.peer.pairing.BrokerPairingLink
import net.extrawdw.notisync.peer.pairing.PairingPayloadCodec
import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.BrokerPairing
import net.extrawdw.notisync.protocol.PairingRelayRequest
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import net.extrawdw.notisync.server.pairing.PairingRelay
import net.extrawdw.notisync.server.pairing.pairingRelay
import org.junit.Assert.*
import org.junit.Test

class PairingRelayTest {
    @Test fun `real clients exchange cards through a local broker`() = withBroker { url ->
        withTimeout(15_000) {
            val hostIdentity = SoftwareIdentitySigner.generate()
            val clientIdentity = SoftwareIdentitySigner.generate()
            val hostCard = card(hostIdentity, "Workstation")
            val clientCard = card(clientIdentity, "Joining device")
            val ready = CompletableDeferred<BrokerPairingLink>()
            val host = async {
                BrokerPairingClient().use { it.host(url, hostCard) { link -> ready.complete(link) } }
            }
            val link = ready.await()
            val received = BrokerPairingClient().use { it.join(link, clientCard) }
            assertEquals(hostCard, received)
            assertEquals("Workstation", PairingPayloadCodec(clientIdentity.clientId).inspect(received).getOrThrow().displayName)
            val receivedOnHost = host.await()
            assertEquals(clientCard, receivedOnHost)
            assertEquals(clientIdentity.clientId, PairingPayloadCodec(hostIdentity.clientId).inspect(receivedOnHost).getOrThrow().clientId)
        }
    }

    @Test fun `wrong QR secret terminates both endpoints and session cannot be reused`() = withBroker { url ->
        val hostCard = card(SoftwareIdentitySigner.generate(), "Host")
        val clientCard = card(SoftwareIdentitySigner.generate(), "Client")
        supervisorScope {
            val ready = CompletableDeferred<BrokerPairingLink>()
            val host = async {
                runCatching {
                    BrokerPairingClient().use { it.host(url, hostCard) { link -> ready.complete(link) } }
                }
            }
            val link = ready.await()
            val wrong = link.copy(secret = BrokerPairingLink.generateSecret())
            assertTrue(runCatching { BrokerPairingClient().use { it.join(wrong, clientCard) } }.isFailure)
            assertTrue(host.await().isFailure)
            assertTrue(runCatching { BrokerPairingClient().use { it.join(link, clientCard) } }.isFailure)
        }
    }

    @Test fun `single join and bounded capacity do not allow another secret attempt`() {
        val relay = PairingRelay(maxSessions = 2, maxSessionsPerAddress = 1)
        val first = relay.create("address-a")
        assertSame(first, relay.join(first.id))
        assertThrows(IllegalArgumentException::class.java) { relay.join(first.id) }
        assertThrows(IllegalArgumentException::class.java) { relay.create("address-a") }
        val second = relay.create("address-b")
        assertThrows(IllegalArgumentException::class.java) { relay.create("address-c") }
        relay.remove(first)
        assertThrows(IllegalArgumentException::class.java) { relay.join(first.id) }
        assertNotNull(relay.create("address-a"))
        relay.remove(second)
    }

    @Test fun `waiting session expires and releases capacity`() {
        val relay = PairingRelay(maxSessions = 1)
        withBroker(relay, sessionMillis = 500) { url ->
            val ready = CompletableDeferred<BrokerPairingLink>()
            val result = runCatching {
                BrokerPairingClient().use { it.host(url, card(SoftwareIdentitySigner.generate(), "Host")) { link -> ready.complete(link) } }
            }
            assertTrue(result.isFailure)
            val link = ready.await()
            assertThrows(IllegalArgumentException::class.java) { relay.join(link.sessionId) }
            assertNotNull(relay.create("after-expiry"))
        }
    }

    @Test fun `host cancellation promptly releases the room while waiting for a scan`() {
        val relay = PairingRelay(maxSessions = 1)
        withBroker(relay) { url ->
            val ready = CompletableDeferred<BrokerPairingLink>()
            val host = async {
                BrokerPairingClient().use { it.host(url, card(SoftwareIdentitySigner.generate(), "Host")) { link -> ready.complete(link) } }
            }
            val link = ready.await()
            host.cancelAndJoin()
            withTimeout(2_000) {
                while (true) {
                    val replacement = runCatching { relay.create("replacement") }.getOrNull()
                    if (replacement != null) {
                        relay.remove(replacement)
                        break
                    }
                    delay(10)
                }
            }
            assertThrows(IllegalArgumentException::class.java) { relay.join(link.sessionId) }
        }
    }

    @Test fun `relay rejects malformed oversized and unsupported requests`() = testApplication {
        application {
            install(WebSockets)
            routing { pairingRelay() }
        }
        val ws = createClient { install(ClientWebSockets) }
        val badFrames = listOf(
            Frame.Text("not a binary pairing frame"),
            Frame.Binary(true, byteArrayOf(0)),
            Frame.Binary(true, ByteArray(BrokerPairing.MAX_FRAME_BYTES + 1)),
            Frame.Binary(true, ProtocolCodec.encodeToCbor(PairingRelayRequest(version = 999))),
            Frame.Binary(true, ProtocolCodec.encodeToCbor(PairingRelayRequest(sessionId = "unknown"))),
        )
        for (frame in badFrames) {
            ws.webSocket(BrokerPairing.PATH) {
                send(frame)
                val response = withTimeout(2_000) { incoming.receiveCatching().getOrNull() }
                assertTrue(response == null || response is Frame.Close)
            }
        }
    }

    private fun withBroker(
        relay: PairingRelay = PairingRelay(),
        sessionMillis: Long = 10_000,
        block: suspend kotlinx.coroutines.CoroutineScope.(String) -> Unit,
    ) = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            install(WebSockets)
            routing { pairingRelay(relay, sessionMillis) }
        }.start(wait = false)
        try { withTimeout(20_000) { block("http://127.0.0.1:$port") } }
        finally { server.stop(0, 1000) }
    }

    private fun card(identity: SoftwareIdentitySigner, name: String): String {
        val payload = ProtocolCodec.encodeToCbor(ClientCard(
            clientId = identity.clientId,
            identityPublicKey = identity.publicKeySpki,
            displayName = name,
            platform = "test",
            capabilities = emptyList(),
            createdAt = 1,
        ))
        return PairingPayloadCodec(identity.clientId).encode(SignedBlob(
            typ = SignedType.CLIENT_CARD,
            signerId = identity.clientId,
            payload = payload,
            sig = identity.sign(payload),
        ))
    }
}
