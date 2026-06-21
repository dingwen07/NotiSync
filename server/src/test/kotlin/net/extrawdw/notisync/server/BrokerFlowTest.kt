package net.extrawdw.notisync.server

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.Base32
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.MirrorImportance
import net.extrawdw.notisync.protocol.PlayIntegrityVerificationRequest
import net.extrawdw.notisync.protocol.PlayIntegrityVerificationResponse
import net.extrawdw.notisync.protocol.VerificationStatusResponse
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
import net.extrawdw.notisync.protocol.crypto.HttpRequestSigning
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.PlayIntegrityBinding
import net.extrawdw.notisync.protocol.crypto.ProofOfWork
import net.extrawdw.notisync.protocol.crypto.RecipientKey
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class BrokerFlowTest {

    @After
    fun clearServerProperties() {
        listOf(
            "NOTISYNC_DB_PATH",
            "NOTISYNC_FCM_ENABLED",
            "NOTISYNC_MAX_ASSET_BYTES",
            "NOTISYNC_PLAY_INTEGRITY_ENABLED",
            "NOTISYNC_JWT_PRIVATE_KEY_PATH",
        ).forEach(System::clearProperty)
    }

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
        System.setProperty("NOTISYNC_PLAY_INTEGRITY_ENABLED", "false")
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

    @Test
    fun privateAsset_storeFetchOverwriteBadIdOversize() = testApplication {
        val tmp = File.createTempFile("notisync-assets", ".db").also { it.deleteOnExit() }
        System.setProperty("NOTISYNC_DB_PATH", tmp.absolutePath)
        System.setProperty("NOTISYNC_FCM_ENABLED", "false")
        System.setProperty("NOTISYNC_PLAY_INTEGRITY_ENABLED", "false")
        System.setProperty("NOTISYNC_MAX_ASSET_BYTES", "64")
        try {
            application { module() }

            val src = SoftwareIdentitySigner.generate().clientId
            val assetId = Base32.encode(ByteArray(24) { (it + 1).toByte() }) // 39-char opaque id
            val ciphertext = ByteArray(40) { it.toByte() }

            // 1. Store succeeds, then fetch returns the exact ciphertext.
            assertEquals(
                HttpStatusCode.OK,
                client.post("/v1/assets/${src.value}/$assetId") { setBody(ciphertext) }.status,
            )
            val fetched = client.get("/v1/assets/${src.value}/$assetId")
            assertEquals(HttpStatusCode.OK, fetched.status)
            assertArrayEquals(ciphertext, fetched.readRawBytes())

            // 2. Overwrite is rejected (first-writer-wins on an unguessable id) and the original survives.
            assertEquals(
                HttpStatusCode.Conflict,
                client.post("/v1/assets/${src.value}/$assetId") { setBody(ByteArray(8)) }.status,
            )
            assertArrayEquals(ciphertext, client.get("/v1/assets/${src.value}/$assetId").readRawBytes())

            // 3. A non-opaque id (not Base32 of 24 bytes) is rejected — the content-derived-id guard.
            assertEquals(
                HttpStatusCode.BadRequest,
                client.post("/v1/assets/${src.value}/notanopaqueid") { setBody(ciphertext) }.status,
            )

            // 4. Oversize is rejected before buffering (Content-Length guard, max=64).
            assertEquals(
                HttpStatusCode.PayloadTooLarge,
                client.post("/v1/assets/${src.value}/$assetId") { setBody(ByteArray(100)) }.status,
            )

            // 5. Unknown asset -> 404.
            val other = Base32.encode(ByteArray(24) { (it + 9).toByte() })
            assertEquals(HttpStatusCode.NotFound, client.get("/v1/assets/${src.value}/$other").status)
        } finally {
            System.clearProperty("NOTISYNC_MAX_ASSET_BYTES")
        }
    }

    @Test
    fun integrityVerificationIssuesJwtForSignedRequests() = testApplication {
        val tmp = File.createTempFile("notisync-auth", ".db").also { it.deleteOnExit() }
        val jwtKey = File.createTempFile("notisync-jwt", ".pem").also { it.delete() }
        System.setProperty("NOTISYNC_DB_PATH", tmp.absolutePath)
        System.setProperty("NOTISYNC_FCM_ENABLED", "false")
        System.setProperty("NOTISYNC_PLAY_INTEGRITY_ENABLED", "true")
        System.setProperty("NOTISYNC_JWT_PRIVATE_KEY_PATH", jwtKey.absolutePath)
        // Fake decoder so the full verdict pipeline runs without a real Google call; it echoes the
        // requestHash carried in the (test-supplied) integrity token and reports all-good verdicts.
        val decoder = object : PlayIntegrityDecoder {
            override suspend fun decode(integrityToken: String) = IntegrityPayload(
                requestPackageName = "net.extrawdw.apps.notisync",
                requestHash = integrityToken,
                timestampMillis = System.currentTimeMillis(),
                appLicensingVerdict = "LICENSED",
                appRecognitionVerdict = "PLAY_RECOGNIZED",
                appPackageName = "net.extrawdw.apps.notisync",
                deviceRecognitionVerdict = listOf("MEETS_DEVICE_INTEGRITY"),
                deviceActivityLevel = "LEVEL_1",
                playProtectVerdict = "NO_ISSUES",
            )
        }
        application { brokerModule(decoder) }

        val signer = SoftwareIdentitySigner.generate()
        val hpke = Hpke.generateKeyPair()
        val card = cardBlob(signer, hpke, "Signed")
        val cardBytes = ProtocolCodec.encodeToCbor(card)

        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/cards") { setBody(cardBytes) }.status)

        // Unauthenticated status discovery: attestation required, not yet verified.
        val statusBefore = ProtocolCodec.decodeFromJson<VerificationStatusResponse>(client.get("/v1/status").bodyAsText())
        assertEquals(true, statusBefore.playIntegrityRequired)
        assertEquals(false, statusBefore.verified)

        val requestNonce = HttpRequestSigning.newNonce()
        val requestHash = PlayIntegrityBinding.requestHash(signer.clientId, requestNonce)
        val verifyRequest = PlayIntegrityVerificationRequest(
            clientId = signer.clientId,
            requestNonce = requestNonce,
            requestHash = requestHash,
            integrityToken = requestHash, // the fake decoder echoes this back as the token's requestHash
            clientCard = card,
        )
        val verifyBody = ProtocolCodec.encodeToJson(verifyRequest).toByteArray(Charsets.UTF_8)
        val verifyResponse = client.post("/v1/integrity/verify") {
            contentType(ContentType.Application.Json)
            signedHeaders(signer, "POST", "/v1/integrity/verify", verifyBody, pow = true)
            setBody(verifyBody)
        }
        assertEquals(HttpStatusCode.OK, verifyResponse.status)
        val token = ProtocolCodec.decodeFromJson<PlayIntegrityVerificationResponse>(verifyResponse.bodyAsText()).token

        // With the bearer presented, status now reports this client as verified.
        val statusAfter = ProtocolCodec.decodeFromJson<VerificationStatusResponse>(
            client.get("/v1/status") { header(HttpHeaders.Authorization, "Bearer $token") }.bodyAsText()
        )
        assertEquals(true, statusAfter.verified)
        assertEquals(signer.clientId, statusAfter.clientId)

        assertEquals(
            HttpStatusCode.OK,
            client.post("/v1/cards") {
                signedHeaders(signer, "POST", "/v1/cards", cardBytes, token)
                setBody(cardBytes)
            }.status,
        )

        val fetched = client.get("/v1/cards/${signer.clientId.value}") {
            signedHeaders(signer, "GET", "/v1/cards/${signer.clientId.value}", ByteArray(0), token)
        }
        assertEquals(HttpStatusCode.OK, fetched.status)
        assertNotNull(Verification.verifyClientCard(ProtocolCodec.decodeFromCbor<SignedBlob>(fetched.readRawBytes())))
    }

    @Test
    fun jwtKeyPairSurvivesMissingPublicSidecar() {
        val keyFile = File.createTempFile("notisync-jwtrec", ".pem").also { it.delete() }
        val pubFile = File("${keyFile.absolutePath}.pub")
        System.setProperty("NOTISYNC_JWT_PRIVATE_KEY_PATH", keyFile.absolutePath)
        try {
            val first = JwtIssuer.load(ServerConfig.fromEnv())
            val token = first.issue(ClientId("abc")).token
            assertTrue(pubFile.delete()) // lose the public sidecar

            val second = JwtIssuer.load(ServerConfig.fromEnv()) // must reuse the private key, not regenerate
            assertNotNull(second.verify(token)) // same key => the previously issued token still verifies
            assertTrue(pubFile.isFile) // sidecar re-derived from the private key
        } finally {
            System.clearProperty("NOTISYNC_JWT_PRIVATE_KEY_PATH")
            keyFile.delete()
            pubFile.delete()
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.signedHeaders(
        signer: IdentitySigner,
        method: String,
        pathAndQuery: String,
        body: ByteArray,
        bearerToken: String? = null,
        pow: Boolean = false,
    ) {
        bearerToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        val signed = HttpRequestSigning.sign(signer, method, pathAndQuery, body)
        header(HttpRequestSigning.HEADER_CLIENT_ID, signed.clientId.value)
        header(HttpRequestSigning.HEADER_TIMESTAMP, signed.timestampMillis.toString())
        header(HttpRequestSigning.HEADER_NONCE, signed.nonce)
        header(HttpRequestSigning.HEADER_CONTENT_SHA256, signed.contentSha256)
        header(HttpRequestSigning.HEADER_SIGNATURE, signed.signature)
        if (pow) {
            val powTimestamp = System.currentTimeMillis()
            val powNonce = ProofOfWork.solve(signed.signature, powTimestamp)
            header(ProofOfWork.HEADER_NONCE, powNonce)
            header(ProofOfWork.HEADER_TIMESTAMP, powTimestamp.toString())
        }
    }
}
