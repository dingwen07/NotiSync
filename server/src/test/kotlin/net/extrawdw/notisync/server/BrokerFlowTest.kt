package net.extrawdw.notisync.server

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.MirrorImportance
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RouteCapabilities
import net.extrawdw.notisync.protocol.RouteClaim
import net.extrawdw.notisync.protocol.RouteEnvironment
import net.extrawdw.notisync.protocol.SendResult
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.WsAuth
import net.extrawdw.notisync.protocol.WsChallenge
import net.extrawdw.notisync.protocol.WsKind
import net.extrawdw.notisync.protocol.WsMessage
import net.extrawdw.notisync.protocol.crypto.EnvelopeCrypto
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.HpkeKeyPair
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.RecipientKey
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class BrokerFlowTest {

    private fun cardBlob(signer: IdentitySigner, hpke: HpkeKeyPair, name: String): SignedBlob {
        val card = ClientCard(
            clientId = signer.clientId,
            identityPublicKey = signer.publicKeySpki,
            hpkePublicKeyset = hpke.publicKeyset,
            displayName = name,
            platform = "test",
            capabilities = listOf(Capability.CAPTURE, Capability.DISPLAY),
            createdAt = 1_750_000_000_000L,
        )
        val payload = ProtocolCodec.encodeToCbor(card)
        return SignedBlob(SignedType.CLIENT_CARD, signerId = signer.clientId, payload = payload, sig = signer.sign(payload))
    }

    private fun routeBlob(signer: IdentitySigner, token: String): SignedBlob {
        val claim = RouteClaim(
            clientId = signer.clientId,
            transport = TransportType.FCM,
            environment = RouteEnvironment.PRODUCTION,
            routeRef = token,
            capabilities = RouteCapabilities(inlinePayloadLimitBytes = 3072),
            epoch = 1,
            issuedAt = 1_750_000_000_000L,
        )
        val payload = ProtocolCodec.encodeToCbor(claim)
        return SignedBlob(SignedType.ROUTE_CLAIM, signerId = signer.clientId, payload = payload, sig = signer.sign(payload))
    }

    @Test
    fun storeAndForwardDeliversAndDecryptsOverWebSocket() = testApplication {
        val tmp = File.createTempFile("notisync-it", ".db").also { it.deleteOnExit() }
        System.setProperty("NOTISYNC_DB_PATH", tmp.absolutePath)
        System.setProperty("NOTISYNC_FCM_ENABLED", "false")
        application { module() }

        val http = createClient { install(WebSockets) }

        val sender = SoftwareIdentitySigner.generate()
        val senderHpke = Hpke.generateKeyPair()
        val recipient = SoftwareIdentitySigner.generate()
        val recipientHpke = Hpke.generateKeyPair()

        // 1. Upload both signed client cards.
        assertEquals(
            HttpStatusCode.OK,
            http.post("/v1/cards") { setBody(ProtocolCodec.encodeToCbor(cardBlob(sender, senderHpke, "Sender"))) }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            http.post("/v1/cards") { setBody(ProtocolCodec.encodeToCbor(cardBlob(recipient, recipientHpke, "Recipient"))) }.status,
        )

        // 2. The broker returns a verifiable card.
        val fetched = ProtocolCodec.decodeFromCbor<SignedBlob>(
            http.get("/v1/cards/${recipient.clientId.value}").readRawBytes()
        )
        assertNotNull(Verification.verifyClientCard(fetched))

        // 3. Register a (fake) FCM route claim for the recipient.
        assertEquals(
            HttpStatusCode.OK,
            http.post("/v1/routes") { setBody(ProtocolCodec.encodeToCbor(listOf(routeBlob(recipient, "fake-token")))) }.status,
        )

        // 4. Seal a notification to the recipient and send it while they are offline.
        val body = ProtocolCodec.encodeToCbor(
            CapturedNotification(
                sourceClientId = sender.clientId,
                sourceKey = "0|com.example.chat|7|null",
                packageName = "com.example.chat",
                appLabel = "Chat",
                title = "Alice",
                text = "Dinner at 7?",
                importance = MirrorImportance.HIGH,
                postTime = 1_750_000_000_000L,
            )
        )
        val envelope = EnvelopeCrypto.seal(
            signer = sender,
            typ = MessageType.NOTIFICATION,
            bodyPlaintext = body,
            recipients = listOf(RecipientKey(recipient.clientId, recipientHpke.publicKeyset)),
            messageId = "01J0IT0001",
            seq = 1L,
            createdAt = 1_750_000_000_000L,
        )
        val envBytes = ProtocolCodec.encodeToCbor(envelope)
        val sendResp = http.post("/v1/send") { setBody(envBytes) }
        val result = ProtocolCodec.decodeFromJson<SendResult>(sendResp.bodyAsText())
        // FCM is disabled in the test, so the recipient (offline) has no live route -> reported missing,
        // but the envelope is queued in the relay for delivery on connect.
        assertTrue("recipient should be reported missing while offline", recipient.clientId in result.missingRoutes)

        // 5. Connect as the recipient; the broker flushes the queued envelope after auth.
        http.webSocket("/v1/connect") {
            val challenge = ProtocolCodec.decodeFromJson<WsChallenge>((incoming.receive() as Frame.Text).readText())
            val sig = Base64.getEncoder().encodeToString(recipient.sign(challenge.nonce.toByteArray()))
            send(Frame.Text(ProtocolCodec.encodeToJson(WsAuth(recipient.clientId, challenge.nonce, sig))))

            val delivered = ProtocolCodec.decodeFromJson<WsMessage>((incoming.receive() as Frame.Text).readText())
            assertEquals(WsKind.DELIVER, delivered.kind)
            val receivedEnv = ProtocolCodec.decodeFromCbor<Envelope>(Base64.getDecoder().decode(delivered.envelopeB64!!))

            // 6. Source authenticity + E2E decryption succeed on the recipient.
            assertTrue(EnvelopeCrypto.verify(receivedEnv, sender.publicKeySpki))
            val plaintext = EnvelopeCrypto.open(receivedEnv, recipient.clientId, recipientHpke.privateKeyset)
            val notif = ProtocolCodec.decodeFromCbor<CapturedNotification>(plaintext)
            assertEquals("Dinner at 7?", notif.text)

            send(Frame.Text(ProtocolCodec.encodeToJson(WsMessage(kind = WsKind.ACK, messageId = receivedEnv.messageId))))
        }
    }
}
