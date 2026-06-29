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
import net.extrawdw.notisync.protocol.AttestationType
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.Base32
import net.extrawdw.notisync.protocol.CipherSuite
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ClientKeyEpoch
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.MirrorImportance
import net.extrawdw.notisync.protocol.IntegrityVerificationRequest
import net.extrawdw.notisync.protocol.Purpose
import net.extrawdw.notisync.protocol.IntegrityVerificationResponse
import net.extrawdw.notisync.protocol.VerificationStatusResponse
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RelayAck
import net.extrawdw.notisync.protocol.RelayPending
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
import net.extrawdw.notisync.protocol.crypto.OperationalSigner
import net.extrawdw.notisync.protocol.crypto.ProofOfWork
import net.extrawdw.notisync.protocol.crypto.RecipientKey
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import net.extrawdw.notisync.protocol.crypto.SoftwareOperationalSigner
import net.extrawdw.notisync.server.auth.JwtIssuer
import net.extrawdw.notisync.server.broker.Broker
import net.extrawdw.notisync.server.crypto.Verification
import net.extrawdw.notisync.server.data.PrivateAssetStore
import net.extrawdw.notisync.server.http.brokerModule
import net.extrawdw.notisync.server.http.module
import net.extrawdw.notisync.server.integrity.AppCheckJwks
import net.extrawdw.notisync.server.integrity.MetricsSnapshot
import net.extrawdw.notisync.server.data.RelayStore
import net.extrawdw.notisync.server.data.RouteStore
import net.extrawdw.notisync.server.data.EpochStore
import net.extrawdw.notisync.server.data.NotiSyncDb
import net.extrawdw.notisync.server.data.StoredEpoch
import net.extrawdw.notisync.server.delivery.DisabledPushTransport
import net.extrawdw.notisync.server.delivery.WebSocketHub
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * NS2 broker flow tests, served entirely under `/v2`. There are no client cards: a client's identity is
 * learned from its self-authenticating [ClientKeyEpoch] (uploaded at attestation or via POST /v2/keyepoch),
 * and the broker resolves [Broker.clientSpki] from the latest stored key-epoch.
 */
class BrokerFlowTest {

    @After
    fun clearServerProperties() {
        listOf(
            "NOTISYNC_DB_PATH",
            "NOTISYNC_APNS_ENABLED",
            "NOTISYNC_APNS_KEY_ID",
            "NOTISYNC_APNS_PRIVATE_KEY_PATH",
            "NOTISYNC_APNS_TEAM_ID",
            "NOTISYNC_APNS_TOPIC",
            "NOTISYNC_FCM_ENABLED",
            "NOTISYNC_MAX_ASSET_BYTES",
            "NOTISYNC_SECURITY_ENABLED",
            "NOTISYNC_INTEGRITY_REQUIRED",
            "NOTISYNC_APPCHECK_ENABLED",
            "NOTISYNC_APPCHECK_PROJECT_NUMBER",
            "NOTISYNC_APPCHECK_APP_IDS",
            "NOTISYNC_JWT_PRIVATE_KEY_PATH",
        ).forEach(System::clearProperty)
    }

    private fun routeBlob(
        signer: IdentitySigner,
        routeRef: String,
        transport: TransportType = TransportType.FCM,
        environment: RouteEnvironment = RouteEnvironment.PRODUCTION,
        epoch: Int = 1,
    ): SignedBlob {
        val claim = RouteClaim(
            clientId = signer.clientId,
            transport = transport,
            environment = environment,
            routeRef = routeRef,
            capabilities = RouteCapabilities(inlinePayloadLimitBytes = 3072),
            epoch = epoch,
            issuedAt = 1_750_000_000_000L,
        )
        val payload = ProtocolCodec.encodeToCbor(claim)
        return SignedBlob(SignedType.ROUTE_CLAIM, signerId = signer.clientId, payload = payload, sig = signer.sign(payload))
    }

    private fun keyEpochBlob(
        identity: IdentitySigner,
        op: OperationalSigner,
        hpke: HpkeKeyPair,
        epoch: Int,
        minEpoch: Int = epoch,
        notBefore: Long = 0L,
        notAfter: Long = Long.MAX_VALUE,
        purposes: List<Purpose> = listOf(Purpose.ENVELOPE_SIGN, Purpose.REQUEST_AUTH, Purpose.HPKE_SEAL),
    ): SignedBlob {
        val ke = ClientKeyEpoch(
            suite = CipherSuite.NS2.id,
            clientId = identity.clientId,
            identityPublicKey = identity.publicKeySpki,
            epoch = epoch,
            operationalSigningKey = op.operationalPublicKeySpki,
            hpkePublicKey = hpke.publicKeyset,
            purposes = purposes,
            notBefore = notBefore,
            notAfter = notAfter,
            minEpoch = minEpoch,
        )
        val payload = ProtocolCodec.encodeToCbor(ke)
        return SignedBlob(SignedType.KEY_EPOCH, signerId = identity.clientId, payload = payload, sig = identity.sign(payload))
    }

    /** Register a client's identity with the broker by publishing an epoch-1 key-epoch (auth-disabled tests). */
    private suspend fun io.ktor.client.HttpClient.register(signer: SoftwareIdentitySigner, hpke: HpkeKeyPair) {
        val op = SoftwareOperationalSigner.generate(signer.clientId, signerEpoch = 1)
        val ke = ProtocolCodec.encodeToCbor(keyEpochBlob(signer, op, hpke, epoch = 1))
        assertEquals(HttpStatusCode.OK, post("/v2/keyepoch") { setBody(ke) }.status)
    }

    @Test
    fun operationalKeyEpoch_uploadResolveThenFloorRejectsOlderEpoch() = testApplication {
        val tmp = File.createTempFile("notisync-epoch", ".db").also { it.deleteOnExit() }
        val jwtKey = File.createTempFile("notisync-epochjwt", ".pem").also { it.delete() }
        System.setProperty("NOTISYNC_DB_PATH", tmp.absolutePath)
        System.setProperty("NOTISYNC_FCM_ENABLED", "false")
        System.setProperty("NOTISYNC_SECURITY_ENABLED", "true")
        System.setProperty("NOTISYNC_JWT_PRIVATE_KEY_PATH", jwtKey.absolutePath)
        application { brokerModule() }

        val signer = SoftwareIdentitySigner.generate()
        val hpke = Hpke.generateKeyPair()
        val op1 = SoftwareOperationalSigner.generate(signer.clientId, signerEpoch = 1)

        // Attest carrying the epoch-1 key-epoch — this registers identity + epoch 1 and returns a bearer.
        val token = client.attest(signer, keyEpochBlob(signer, op1, hpke, epoch = 1))

        // A request signed with the OPERATIONAL key (epoch 1) is accepted.
        val getPath = "/v2/keyepoch/${signer.clientId.value}"
        assertEquals(
            HttpStatusCode.OK,
            client.get(getPath) { operationalSignedHeaders(op1, "GET", getPath, ByteArray(0), token) }.status,
        )

        // The key-epoch is served back for peer pull and re-verifies self-contained.
        val keFetched = ProtocolCodec.decodeFromCbor<SignedBlob>(
            client.get(getPath) { signedHeaders(signer, "GET", getPath, ByteArray(0), token) }.readRawBytes()
        )
        assertNotNull(Verification.verifyKeyEpoch(keFetched))

        // Advancing to epoch 3 (minEpoch 3) raises the floor; a stale epoch-2 publish is then rejected...
        val op3 = SoftwareOperationalSigner.generate(signer.clientId, signerEpoch = 3)
        val ke3 = ProtocolCodec.encodeToCbor(keyEpochBlob(signer, op3, hpke, epoch = 3))
        assertEquals(
            HttpStatusCode.OK,
            client.post("/v2/keyepoch") { signedHeaders(signer, "POST", "/v2/keyepoch", ke3, token); setBody(ke3) }.status,
        )
        val op2 = SoftwareOperationalSigner.generate(signer.clientId, signerEpoch = 2)
        val ke2 = ProtocolCodec.encodeToCbor(keyEpochBlob(signer, op2, hpke, epoch = 2))
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/v2/keyepoch") { signedHeaders(signer, "POST", "/v2/keyepoch", ke2, token); setBody(ke2) }.status,
        )

        // ...and a request signed with the now-floored epoch-1 key is no longer accepted.
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get(getPath) { operationalSignedHeaders(op1, "GET", getPath, ByteArray(0), token) }.status,
        )
    }

    @Test
    fun keyEpochValidityWindow_preWarmedRejectedUntilActiveAndExpiredRejected() = testApplication {
        val tmp = File.createTempFile("notisync-validity", ".db").also { it.deleteOnExit() }
        val jwtKey = File.createTempFile("notisync-validityjwt", ".pem").also { it.delete() }
        System.setProperty("NOTISYNC_DB_PATH", tmp.absolutePath)
        System.setProperty("NOTISYNC_FCM_ENABLED", "false")
        System.setProperty("NOTISYNC_SECURITY_ENABLED", "true")
        System.setProperty("NOTISYNC_JWT_PRIVATE_KEY_PATH", jwtKey.absolutePath)
        application { brokerModule() }

        val signer = SoftwareIdentitySigner.generate()
        val hpke = Hpke.generateKeyPair()
        val op1 = SoftwareOperationalSigner.generate(signer.clientId, signerEpoch = 1)
        val token = client.attest(signer, keyEpochBlob(signer, op1, hpke, epoch = 1))
        val getPath = "/v2/keyepoch/${signer.clientId.value}"

        // Active epoch 1 authenticates.
        assertEquals(
            HttpStatusCode.OK,
            client.get(getPath) { operationalSignedHeaders(op1, "GET", getPath, ByteArray(0), token) }.status,
        )

        // Pre-warm epoch 2 with a FUTURE notBefore, floor unchanged (minEpoch 1).
        val now = System.currentTimeMillis()
        val op2 = SoftwareOperationalSigner.generate(signer.clientId, signerEpoch = 2)
        val ke2 = ProtocolCodec.encodeToCbor(
            keyEpochBlob(signer, op2, hpke, epoch = 2, minEpoch = 1, notBefore = now + 3_600_000, notAfter = now + 604_800_000)
        )
        assertEquals(
            HttpStatusCode.OK,
            client.post("/v2/keyepoch") { signedHeaders(signer, "POST", "/v2/keyepoch", ke2, token); setBody(ke2) }.status,
        )

        // It IS served (peers pre-cache it before activation)...
        val servedBlob = ProtocolCodec.decodeFromCbor<SignedBlob>(
            client.get(getPath) { signedHeaders(signer, "GET", getPath, ByteArray(0), token) }.readRawBytes()
        )
        assertEquals(2, servedBlob.decode<ClientKeyEpoch>().epoch)
        // ...but a request signed with the pre-warmed key is rejected until notBefore.
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get(getPath) { operationalSignedHeaders(op2, "GET", getPath, ByteArray(0), token) }.status,
        )
        // Epoch 1 still authenticates during the overlap.
        assertEquals(
            HttpStatusCode.OK,
            client.get(getPath) { operationalSignedHeaders(op1, "GET", getPath, ByteArray(0), token) }.status,
        )

        // An expired epoch (notAfter in the past) is rejected for auth — bounding a compromised key.
        val op9 = SoftwareOperationalSigner.generate(signer.clientId, signerEpoch = 9)
        val ke9 = ProtocolCodec.encodeToCbor(
            keyEpochBlob(signer, op9, hpke, epoch = 9, minEpoch = 1, notBefore = 0L, notAfter = now - 3_600_000)
        )
        client.post("/v2/keyepoch") { signedHeaders(signer, "POST", "/v2/keyepoch", ke9, token); setBody(ke9) }
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get(getPath) { operationalSignedHeaders(op9, "GET", getPath, ByteArray(0), token) }.status,
        )
    }

    @Test
    fun epochStore_monotonicFloorAndPurgeKeepsLatest() = runBlocking {
        val tmp = File.createTempFile("notisync-epochstore", ".db").also { it.deleteOnExit() }
        System.setProperty("NOTISYNC_DB_PATH", tmp.absolutePath)
        System.setProperty("NOTISYNC_SECURITY_ENABLED", "false")
        val store = EpochStore(NotiSyncDb.connect(ServerConfig.fromEnv()))
        val cid = ClientId("client-epochstore")
        fun blob(n: Int) = ByteArray(4) { n.toByte() }

        assertTrue(store.put(StoredEpoch(cid, epoch = 1, minEpoch = 1, notBefore = 0L, notAfter = 1_000L, signedBlob = blob(1))))
        assertTrue(store.put(StoredEpoch(cid, epoch = 2, minEpoch = 1, notBefore = 0L, notAfter = 1_000L, signedBlob = blob(2))))
        assertEquals("overlap keeps the floor low", 1, store.floor(cid))
        assertTrue(store.put(StoredEpoch(cid, epoch = 3, minEpoch = 3, notBefore = 0L, notAfter = Long.MAX_VALUE, signedBlob = blob(3))))
        assertEquals("a higher minEpoch retires older epochs", 3, store.floor(cid))
        assertFalse("a below-floor epoch is rejected", store.put(StoredEpoch(cid, epoch = 2, minEpoch = 2, notBefore = 0L, notAfter = 1_000L, signedBlob = blob(2))))
        assertEquals(3, store.latest(cid)!!.epoch)

        // Purge well past the expired epochs (notAfter 1000) with zero grace: 1 & 2 go; epoch 3 (latest,
        // notAfter MAX) is kept, so the floor survives.
        assertEquals(2, store.purgeExpired(now = 10_000L, retentionGrace = 0L))
        assertNull(store.get(cid, 1))
        assertNull(store.get(cid, 2))
        assertNotNull(store.get(cid, 3))
        assertEquals("floor preserved by keeping the latest", 3, store.floor(cid))
    }

    @Test
    fun epochStore_floorDoesNotRegressViaStaleMinEpochOrGc() = runBlocking {
        val tmp = File.createTempFile("notisync-floor", ".db").also { it.deleteOnExit() }
        System.setProperty("NOTISYNC_DB_PATH", tmp.absolutePath)
        System.setProperty("NOTISYNC_SECURITY_ENABLED", "false")
        val store = EpochStore(NotiSyncDb.connect(ServerConfig.fromEnv()))
        val cid = ClientId("client-floor")
        fun blob(n: Int) = ByteArray(4) { n.toByte() }

        // Floor is 5; the epoch-5 row is set to expire so GC could be tempted to drop it.
        assertTrue(store.put(StoredEpoch(cid, epoch = 5, minEpoch = 5, notBefore = 0L, notAfter = 1_000L, signedBlob = blob(5))))
        assertEquals(5, store.floor(cid))

        // A later epoch carrying a STALE (below-floor) minEpoch is rejected — put can't lower the floor.
        assertFalse(store.put(StoredEpoch(cid, epoch = 6, minEpoch = 3, notBefore = 0L, notAfter = Long.MAX_VALUE, signedBlob = blob(6))))
        assertEquals(5, store.floor(cid))

        // A legitimate advance keeps minEpoch ≥ floor.
        assertTrue(store.put(StoredEpoch(cid, epoch = 6, minEpoch = 5, notBefore = 0L, notAfter = Long.MAX_VALUE, signedBlob = blob(6))))

        // GC of the expired epoch-5 row must NOT regress the floor (the highest-minEpoch row is protected).
        store.purgeExpired(now = 10_000L, retentionGrace = 0L)
        assertEquals("floor durable across GC", 5, store.floor(cid))
        assertNotNull(store.get(cid, 6))
    }

    @Test
    fun emptyRouteClaimClearsRouteSoClientCanRecoverLostRouteEpoch() = runBlocking {
        val tmp = File.createTempFile("notisync-route-reset", ".db").also { it.deleteOnExit() }
        System.setProperty("NOTISYNC_DB_PATH", tmp.absolutePath)
        System.setProperty("NOTISYNC_SECURITY_ENABLED", "false")
        val config = ServerConfig.fromEnv()
        val db = NotiSyncDb.connect(config)
        val routes = RouteStore(db)
        val broker = Broker(
            routes,
            RelayStore(db),
            PrivateAssetStore(db),
            EpochStore(db),
            WebSocketHub(),
            DisabledPushTransport,
            config,
        )
        val signer = SoftwareIdentitySigner.generate()
        val op = SoftwareOperationalSigner.generate(signer.clientId, signerEpoch = 1)
        val hpke = Hpke.generateKeyPair()
        assertTrue(broker.uploadKeyEpoch(keyEpochBlob(signer, op, hpke, epoch = 1)))

        assertEquals(1, broker.uploadRoutes(listOf(routeBlob(signer, "new-token", epoch = 2))))
        assertEquals("new-token", routes.routesFor(signer.clientId).single().routeRef)

        assertEquals(1, broker.uploadRoutes(listOf(routeBlob(signer, "", epoch = 1))))
        assertTrue(routes.routesFor(signer.clientId).isEmpty())

        assertEquals(1, broker.uploadRoutes(listOf(routeBlob(signer, "recovered-token", epoch = 1))))
        assertEquals("recovered-token", routes.routesFor(signer.clientId).single().routeRef)
    }

    @Test
    fun routeResetRequiresIdentitySignedHttpRequest() = testApplication {
        val tmp = File.createTempFile("notisync-route-reset-auth", ".db").also { it.deleteOnExit() }
        val jwtKey = File.createTempFile("notisync-route-reset-authjwt", ".pem").also { it.delete() }
        System.setProperty("NOTISYNC_DB_PATH", tmp.absolutePath)
        System.setProperty("NOTISYNC_FCM_ENABLED", "false")
        System.setProperty("NOTISYNC_SECURITY_ENABLED", "true")
        System.setProperty("NOTISYNC_JWT_PRIVATE_KEY_PATH", jwtKey.absolutePath)
        application { brokerModule() }

        val signer = SoftwareIdentitySigner.generate()
        val hpke = Hpke.generateKeyPair()
        val op = SoftwareOperationalSigner.generate(signer.clientId, signerEpoch = 1)
        val token = client.attest(signer, keyEpochBlob(signer, op, hpke, epoch = 1))
        val routes = RouteStore(NotiSyncDb.connect(ServerConfig.fromEnv()))

        val routeBody = ProtocolCodec.encodeToCbor(listOf(routeBlob(signer, "new-token", epoch = 2)))
        assertEquals(
            HttpStatusCode.OK,
            client.post("/v2/routes") {
                signedHeaders(signer, "POST", "/v2/routes", routeBody, token)
                setBody(routeBody)
            }.status,
        )
        assertEquals("new-token", routes.routesFor(signer.clientId).single().routeRef)

        val resetBody = ProtocolCodec.encodeToCbor(listOf(routeBlob(signer, "", epoch = 1)))
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/v2/routes") {
                operationalSignedHeaders(op, "POST", "/v2/routes", resetBody, token)
                setBody(resetBody)
            }.status,
        )
        assertEquals("new-token", routes.routesFor(signer.clientId).single().routeRef)

        assertEquals(
            HttpStatusCode.OK,
            client.post("/v2/routes") {
                signedHeaders(signer, "POST", "/v2/routes", resetBody, token)
                setBody(resetBody)
            }.status,
        )
        assertTrue(routes.routesFor(signer.clientId).isEmpty())

        val recoveredBody = ProtocolCodec.encodeToCbor(listOf(routeBlob(signer, "recovered-token", epoch = 1)))
        assertEquals(
            HttpStatusCode.OK,
            client.post("/v2/routes") {
                signedHeaders(signer, "POST", "/v2/routes", recoveredBody, token)
                setBody(recoveredBody)
            }.status,
        )
        assertEquals("recovered-token", routes.routesFor(signer.clientId).single().routeRef)
    }

    @Test
    fun operationalKey_withoutRequestAuthPurpose_isRejectedForRequestAuth() = testApplication {
        val tmp = File.createTempFile("notisync-purpose", ".db").also { it.deleteOnExit() }
        val jwtKey = File.createTempFile("notisync-purposejwt", ".pem").also { it.delete() }
        System.setProperty("NOTISYNC_DB_PATH", tmp.absolutePath)
        System.setProperty("NOTISYNC_FCM_ENABLED", "false")
        System.setProperty("NOTISYNC_SECURITY_ENABLED", "true")
        System.setProperty("NOTISYNC_JWT_PRIVATE_KEY_PATH", jwtKey.absolutePath)
        application { brokerModule() }

        val signer = SoftwareIdentitySigner.generate()
        val hpke = Hpke.generateKeyPair()
        // A key-epoch authorized only for ENVELOPE_SIGN/HPKE_SEAL — NOT REQUEST_AUTH.
        val op = SoftwareOperationalSigner.generate(signer.clientId, signerEpoch = 1)
        val token = client.attest(
            signer,
            keyEpochBlob(signer, op, hpke, epoch = 1, purposes = listOf(Purpose.ENVELOPE_SIGN, Purpose.HPKE_SEAL)),
        )

        // Stored/served, but a request-auth signature with it is refused (closed-by-default purpose scope).
        val getPath = "/v2/keyepoch/${signer.clientId.value}"
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get(getPath) { operationalSignedHeaders(op, "GET", getPath, ByteArray(0), token) }.status,
        )
    }

    @Test
    fun integrityVerify_staleKeyEpochIsRejectedNotSilentlyTokenized() = testApplication {
        val tmp = File.createTempFile("notisync-staleke", ".db").also { it.deleteOnExit() }
        val jwtKey = File.createTempFile("notisync-stalekejwt", ".pem").also { it.delete() }
        System.setProperty("NOTISYNC_DB_PATH", tmp.absolutePath)
        System.setProperty("NOTISYNC_FCM_ENABLED", "false")
        System.setProperty("NOTISYNC_SECURITY_ENABLED", "true")
        System.setProperty("NOTISYNC_JWT_PRIVATE_KEY_PATH", jwtKey.absolutePath)
        application { brokerModule() }

        val signer = SoftwareIdentitySigner.generate()
        val hpke = Hpke.generateKeyPair()

        // First contact at epoch 3 raises the broker's monotonic floor to 3.
        val op3 = SoftwareOperationalSigner.generate(signer.clientId, signerEpoch = 3)
        val token = client.attest(signer, keyEpochBlob(signer, op3, hpke, epoch = 3))

        // Re-attesting while carrying a STALE (below-floor) epoch-1 key-epoch must be REFUSED with a
        // conflict — not silently handed a bearer for an epoch the broker rejected (uploadKeyEpoch == false).
        val op1 = SoftwareOperationalSigner.generate(signer.clientId, signerEpoch = 1)
        val staleBody = ProtocolCodec.encodeToJson(
            IntegrityVerificationRequest(
                clientId = signer.clientId,
                attestationType = AttestationType.FIREBASE_APP_CHECK,
                clientKeyEpoch = keyEpochBlob(signer, op1, hpke, epoch = 1),
            )
        ).toByteArray(Charsets.UTF_8)
        val resp = client.post("/v2/integrity/verify") {
            contentType(ContentType.Application.Json)
            signedHeaders(signer, "POST", "/v2/integrity/verify", staleBody, pow = true)
            setBody(staleBody)
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)

        // The stale epoch was NOT stored: the latest served key-epoch is still epoch 3 (floor intact).
        val getPath = "/v2/keyepoch/${signer.clientId.value}"
        val served = ProtocolCodec.decodeFromCbor<SignedBlob>(
            client.get(getPath) { signedHeaders(signer, "GET", getPath, ByteArray(0), token) }.readRawBytes()
        )
        assertEquals(3, served.decode<ClientKeyEpoch>().epoch)
    }

    @Test
    fun healthProbesAreServedUnversionedAtRoot() = testApplication {
        val tmp = File.createTempFile("notisync-health", ".db").also { it.deleteOnExit() }
        val jwtKey = File.createTempFile("notisync-healthjwt", ".pem").also { it.delete() }
        System.setProperty("NOTISYNC_DB_PATH", tmp.absolutePath)
        System.setProperty("NOTISYNC_FCM_ENABLED", "false")
        System.setProperty("NOTISYNC_SECURITY_ENABLED", "false")
        System.setProperty("NOTISYNC_JWT_PRIVATE_KEY_PATH", jwtKey.absolutePath)
        application { brokerModule() }

        // Liveness/readiness live at the unversioned root so probes survive an API version bump; the
        // /v2 prefix must NOT capture them.
        assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
        assertEquals(HttpStatusCode.OK, client.get("/readyz").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v2/healthz").status)
    }

    /**
     * Attest (carrying [keyEpoch] so the broker learns identity) and return the issued bearer token. Tests
     * using this run with NOTISYNC_SECURITY_ENABLED=true and NOTISYNC_INTEGRITY_REQUIRED unset (false), so a
     * validly-signed first-contact is issued a bearer without a real attestation token. The App-Check-required
     * path is covered separately by [attestAppCheck] / the integrity-required tests below.
     */
    private suspend fun io.ktor.client.HttpClient.attest(signer: SoftwareIdentitySigner, keyEpoch: SignedBlob): String {
        val verifyBody = ProtocolCodec.encodeToJson(
            IntegrityVerificationRequest(
                clientId = signer.clientId,
                attestationType = AttestationType.FIREBASE_APP_CHECK,
                clientKeyEpoch = keyEpoch,
            )
        ).toByteArray(Charsets.UTF_8)
        val resp = post("/v2/integrity/verify") {
            contentType(ContentType.Application.Json)
            signedHeaders(signer, "POST", "/v2/integrity/verify", verifyBody, pow = true)
            setBody(verifyBody)
        }
        return ProtocolCodec.decodeFromJson<IntegrityVerificationResponse>(resp.bodyAsText()).token
    }

    // --- App Check (firebaseAppCheck) attestation method, verified locally against an injected JWKS ---

    private val appCheckProjectNumber = "987654321"
    private val appCheckAppId = "1:987654321:android:notisync"
    private val appCheckKid = "broker-test-kid"
    private val appCheckKeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val appCheckJwks = object : AppCheckJwks {
        override fun key(kid: String): RSAPublicKey? =
            if (kid == appCheckKid) appCheckKeyPair.public as RSAPublicKey else null
    }

    private fun mintAppCheckToken(): String {
        val url = Base64.getUrlEncoder().withoutPadding()
        val now = System.currentTimeMillis() / 1000
        val header = """{"alg":"RS256","typ":"JWT","kid":"$appCheckKid"}"""
        val claims = """{"iss":"https://firebaseappcheck.googleapis.com/$appCheckProjectNumber",""" +
            """"aud":["projects/$appCheckProjectNumber"],"sub":"$appCheckAppId","exp":${now + 1800},"iat":$now}"""
        val signingInput = "${url.encodeToString(header.toByteArray())}.${url.encodeToString(claims.toByteArray())}"
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(appCheckKeyPair.private); update(signingInput.toByteArray(Charsets.US_ASCII)); sign()
        }
        return "$signingInput.${url.encodeToString(sig)}"
    }

    /** Attest via App Check with a freshly-minted, valid token and return the issued bearer. Caller must have
     *  enabled App Check (NOTISYNC_APPCHECK_* + the injected [appCheckJwks]) before building the module. */
    private suspend fun io.ktor.client.HttpClient.attestAppCheck(signer: SoftwareIdentitySigner, keyEpoch: SignedBlob): String {
        val verifyBody = ProtocolCodec.encodeToJson(
            IntegrityVerificationRequest(
                clientId = signer.clientId,
                attestationType = AttestationType.FIREBASE_APP_CHECK,
                attestationToken = mintAppCheckToken(),
                clientKeyEpoch = keyEpoch,
            )
        ).toByteArray(Charsets.UTF_8)
        val resp = post("/v2/integrity/verify") {
            contentType(ContentType.Application.Json)
            signedHeaders(signer, "POST", "/v2/integrity/verify", verifyBody, pow = true)
            setBody(verifyBody)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        return ProtocolCodec.decodeFromJson<IntegrityVerificationResponse>(resp.bodyAsText()).token
    }

    @Test
    fun integrityVerify_viaAppCheck_issuesJwtAndStatusAdvertisesMethods() = testApplication {
        val tmp = File.createTempFile("notisync-appcheck", ".db").also { it.deleteOnExit() }
        val jwtKey = File.createTempFile("notisync-appcheckjwt", ".pem").also { it.delete() }
        System.setProperty("NOTISYNC_DB_PATH", tmp.absolutePath)
        System.setProperty("NOTISYNC_FCM_ENABLED", "false")
        System.setProperty("NOTISYNC_SECURITY_ENABLED", "true")
        System.setProperty("NOTISYNC_INTEGRITY_REQUIRED", "true") // strict: a passing attestation is mandatory
        System.setProperty("NOTISYNC_APPCHECK_ENABLED", "true")
        System.setProperty("NOTISYNC_APPCHECK_PROJECT_NUMBER", appCheckProjectNumber)
        System.setProperty("NOTISYNC_APPCHECK_APP_IDS", appCheckAppId)
        System.setProperty("NOTISYNC_JWT_PRIVATE_KEY_PATH", jwtKey.absolutePath)
        application { brokerModule(appCheckJwks) }

        // Unauthenticated discovery advertises App Check as the (only) accepted method + the PoW difficulty.
        // Play Integrity is retired, so it must NOT be advertised.
        val status = ProtocolCodec.decodeFromJson<VerificationStatusResponse>(client.get("/v2/status").bodyAsText())
        assertTrue(AttestationType.FIREBASE_APP_CHECK in status.acceptedAttestationMethods)
        assertFalse(AttestationType.PLAY_INTEGRITY in status.acceptedAttestationMethods)
        assertTrue(status.integrityRequired)
        assertEquals(4, status.powDifficulty)

        val signer = SoftwareIdentitySigner.generate()
        val hpke = Hpke.generateKeyPair()
        val op = SoftwareOperationalSigner.generate(signer.clientId, signerEpoch = 1)

        // Attest with a valid App Check token — identity-signed request + PoW — and get the broker bearer.
        val token = client.attestAppCheck(signer, keyEpochBlob(signer, op, hpke, epoch = 1))

        // The issued broker bearer authenticates a subsequent call.
        val getPath = "/v2/keyepoch/${signer.clientId.value}"
        assertEquals(
            HttpStatusCode.OK,
            client.get(getPath) { signedHeaders(signer, "GET", getPath, ByteArray(0), token) }.status,
        )
    }

    @Test
    fun integrityVerify_requiredButAttestationMissing_isRejected() = testApplication {
        val tmp = File.createTempFile("notisync-integ-req", ".db").also { it.deleteOnExit() }
        val jwtKey = File.createTempFile("notisync-integ-reqjwt", ".pem").also { it.delete() }
        System.setProperty("NOTISYNC_DB_PATH", tmp.absolutePath)
        System.setProperty("NOTISYNC_FCM_ENABLED", "false")
        System.setProperty("NOTISYNC_SECURITY_ENABLED", "true")
        System.setProperty("NOTISYNC_INTEGRITY_REQUIRED", "true")
        System.setProperty("NOTISYNC_APPCHECK_ENABLED", "true")
        System.setProperty("NOTISYNC_APPCHECK_PROJECT_NUMBER", appCheckProjectNumber)
        System.setProperty("NOTISYNC_APPCHECK_APP_IDS", appCheckAppId)
        System.setProperty("NOTISYNC_JWT_PRIVATE_KEY_PATH", jwtKey.absolutePath)
        application { brokerModule(appCheckJwks) }

        val signer = SoftwareIdentitySigner.generate()
        val hpke = Hpke.generateKeyPair()
        val op = SoftwareOperationalSigner.generate(signer.clientId, signerEpoch = 1)

        // A validly-signed request with NO App Check token is refused when integrity is required (no bearer).
        val verifyBody = ProtocolCodec.encodeToJson(
            IntegrityVerificationRequest(
                clientId = signer.clientId,
                attestationType = AttestationType.FIREBASE_APP_CHECK,
                clientKeyEpoch = keyEpochBlob(signer, op, hpke, epoch = 1),
            )
        ).toByteArray(Charsets.UTF_8)
        val resp = client.post("/v2/integrity/verify") {
            contentType(ContentType.Application.Json)
            signedHeaders(signer, "POST", "/v2/integrity/verify", verifyBody, pow = true)
            setBody(verifyBody)
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun integrityVerify_notRequired_issuesBearerWithoutAttestationToken() = testApplication {
        val tmp = File.createTempFile("notisync-integ-opt", ".db").also { it.deleteOnExit() }
        val jwtKey = File.createTempFile("notisync-integ-optjwt", ".pem").also { it.delete() }
        System.setProperty("NOTISYNC_DB_PATH", tmp.absolutePath)
        System.setProperty("NOTISYNC_FCM_ENABLED", "false")
        System.setProperty("NOTISYNC_SECURITY_ENABLED", "true")
        // NOTISYNC_INTEGRITY_REQUIRED unset → defaults false: signing is enforced, attestation is optional.
        System.setProperty("NOTISYNC_JWT_PRIVATE_KEY_PATH", jwtKey.absolutePath)
        application { brokerModule() }

        val status = ProtocolCodec.decodeFromJson<VerificationStatusResponse>(client.get("/v2/status").bodyAsText())
        assertTrue("security on ⇒ legacy playIntegrityRequired flag true", status.playIntegrityRequired)
        assertFalse("integrity not required", status.integrityRequired)

        val signer = SoftwareIdentitySigner.generate()
        val hpke = Hpke.generateKeyPair()
        val op = SoftwareOperationalSigner.generate(signer.clientId, signerEpoch = 1)

        // No attestation token, but a validly-signed first-contact still mints a usable bearer.
        val token = client.attest(signer, keyEpochBlob(signer, op, hpke, epoch = 1))
        val getPath = "/v2/keyepoch/${signer.clientId.value}"
        assertEquals(
            HttpStatusCode.OK,
            client.get(getPath) { signedHeaders(signer, "GET", getPath, ByteArray(0), token) }.status,
        )
    }

    @Test
    fun metricsEndpoint_basicAuthGatesSnapshot() = testApplication {
        val tmp = File.createTempFile("notisync-metrics", ".db").also { it.deleteOnExit() }
        val jwtKey = File.createTempFile("notisync-metricsjwt", ".pem").also { it.delete() }
        System.setProperty("NOTISYNC_DB_PATH", tmp.absolutePath)
        System.setProperty("NOTISYNC_FCM_ENABLED", "false")
        System.setProperty("NOTISYNC_SECURITY_ENABLED", "true")
        System.setProperty("NOTISYNC_APPCHECK_ENABLED", "true")
        System.setProperty("NOTISYNC_APPCHECK_PROJECT_NUMBER", appCheckProjectNumber)
        System.setProperty("NOTISYNC_APPCHECK_APP_IDS", appCheckAppId)
        System.setProperty("NOTISYNC_JWT_PRIVATE_KEY_PATH", jwtKey.absolutePath)
        System.setProperty("NOTISYNC_METRICS_USER", "ops")
        System.setProperty("NOTISYNC_METRICS_PASSWORD", "s3cret")
        try {
            application { brokerModule(appCheckJwks) }
            fun basic(creds: String) = "Basic " + Base64.getEncoder().encodeToString(creds.toByteArray())

            // Missing and wrong credentials are both rejected.
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v2/integrity/metrics").status)
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/v2/integrity/metrics") { header(HttpHeaders.Authorization, basic("ops:wrong")) }.status,
            )

            // Drive one App Check attestation so the snapshot has something to report.
            val signer = SoftwareIdentitySigner.generate()
            val hpke = Hpke.generateKeyPair()
            val op = SoftwareOperationalSigner.generate(signer.clientId, signerEpoch = 1)
            client.attestAppCheck(signer, keyEpochBlob(signer, op, hpke, epoch = 1))

            val resp = client.get("/v2/integrity/metrics") { header(HttpHeaders.Authorization, basic("ops:s3cret")) }
            assertEquals(HttpStatusCode.OK, resp.status)
            val snap = ProtocolCodec.decodeFromJson<MetricsSnapshot>(resp.bodyAsText())
            val acAccepted = snap.buckets.sumOf { it.methods[AttestationType.FIREBASE_APP_CHECK]?.accepted ?: 0 }
            assertTrue("an App Check accept should be recorded", acAccepted >= 1)
            assertTrue("recent log includes the attest", snap.recent.any { it.method == AttestationType.FIREBASE_APP_CHECK })
        } finally {
            listOf("NOTISYNC_METRICS_USER", "NOTISYNC_METRICS_PASSWORD").forEach(System::clearProperty)
        }
    }

    @Test
    fun storeAndForwardDeliversAndDecryptsOverWebSocket() = testApplication {
        val tmp = File.createTempFile("notisync-it", ".db").also { it.deleteOnExit() }
        System.setProperty("NOTISYNC_DB_PATH", tmp.absolutePath)
        System.setProperty("NOTISYNC_FCM_ENABLED", "false")
        System.setProperty("NOTISYNC_SECURITY_ENABLED", "false")
        application { module() }

        val http = createClient { install(WebSockets) }

        val sender = SoftwareIdentitySigner.generate()
        val senderHpke = Hpke.generateKeyPair()
        val recipient = SoftwareIdentitySigner.generate()
        val recipientHpke = Hpke.generateKeyPair()

        // 1. Register both identities (publish their key-epochs).
        http.register(sender, senderHpke)
        http.register(recipient, recipientHpke)

        // 2. The broker returns a verifiable key-epoch for the recipient.
        val fetched = ProtocolCodec.decodeFromCbor<SignedBlob>(
            http.get("/v2/keyepoch/${recipient.clientId.value}").readRawBytes()
        )
        assertNotNull(Verification.verifyKeyEpoch(fetched))

        // 3. Register a (fake) FCM route claim for the recipient.
        assertEquals(
            HttpStatusCode.OK,
            http.post("/v2/routes") { setBody(ProtocolCodec.encodeToCbor(listOf(routeBlob(recipient, "fake-token")))) }.status,
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
        val sendResp = http.post("/v2/send") { setBody(envBytes) }
        val result = ProtocolCodec.decodeFromJson<SendResult>(sendResp.bodyAsText())
        // FCM is disabled in the test, so the recipient (offline) has no live route -> reported missing,
        // but the envelope is queued in the relay for delivery on connect.
        assertTrue("recipient should be reported missing while offline", recipient.clientId in result.missingRoutes)

        // 5. Connect as the recipient; the broker flushes the queued envelope after auth.
        http.webSocket("/v2/connect") {
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
    fun apnsRouteClaimQueuesRelayWhenProviderDisabled() = testApplication {
        val tmp = File.createTempFile("notisync-apns-route", ".db").also { it.deleteOnExit() }
        System.setProperty("NOTISYNC_DB_PATH", tmp.absolutePath)
        System.setProperty("NOTISYNC_APNS_ENABLED", "false")
        System.setProperty("NOTISYNC_FCM_ENABLED", "false")
        System.setProperty("NOTISYNC_SECURITY_ENABLED", "false")
        application { module() }

        val sender = SoftwareIdentitySigner.generate()
        val senderHpke = Hpke.generateKeyPair()
        val recipient = SoftwareIdentitySigner.generate()
        val recipientHpke = Hpke.generateKeyPair()

        client.register(sender, senderHpke)
        client.register(recipient, recipientHpke)

        assertEquals(
            HttpStatusCode.OK,
            client.post("/v2/routes") {
                setBody(
                    ProtocolCodec.encodeToCbor(
                        listOf(routeBlob(recipient, "abcd1234", TransportType.APNS, RouteEnvironment.DEVELOPMENT))
                    )
                )
            }.status,
        )

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
            messageId = "01J0APNS001",
            seq = 1L,
            createdAt = 1_750_000_000_000L,
        )

        val sendResp = client.post("/v2/send") { setBody(ProtocolCodec.encodeToCbor(envelope)) }
        val result = ProtocolCodec.decodeFromJson<SendResult>(sendResp.bodyAsText())
        assertTrue(recipient.clientId in result.missingRoutes)

        val pending = ProtocolCodec.decodeFromJson<RelayPending>(
            client.get("/v2/relay") { signedHeaders(recipient, "GET", "/v2/relay", ByteArray(0)) }.bodyAsText()
        )
        assertEquals(listOf(envelope.messageId), pending.messageIds)
    }

    @Test
    fun relayFetch_signedOnly_returnsThenAcksSingleMessage() = testApplication {
        val tmp = File.createTempFile("notisync-relayfetch", ".db").also { it.deleteOnExit() }
        System.setProperty("NOTISYNC_DB_PATH", tmp.absolutePath)
        System.setProperty("NOTISYNC_FCM_ENABLED", "false")
        System.setProperty("NOTISYNC_SECURITY_ENABLED", "false")
        application { module() }

        val sender = SoftwareIdentitySigner.generate()
        val senderHpke = Hpke.generateKeyPair()
        val recipient = SoftwareIdentitySigner.generate()
        val recipientHpke = Hpke.generateKeyPair()

        client.register(sender, senderHpke)
        client.register(recipient, recipientHpke)
        client.post("/v2/routes") { setBody(ProtocolCodec.encodeToCbor(listOf(routeBlob(recipient, "fake-token")))) }

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
            messageId = "01J0RELAY01",
            seq = 1L,
            createdAt = 1_750_000_000_000L,
        )
        client.post("/v2/send") { setBody(ProtocolCodec.encodeToCbor(envelope)) }

        // 0. The signed drain-list endpoint reports exactly this queued id (the WorkManager backstop's input).
        val pendingBefore = ProtocolCodec.decodeFromJson<RelayPending>(
            client.get("/v2/relay") { signedHeaders(recipient, "GET", "/v2/relay", ByteArray(0)) }.bodyAsText()
        )
        assertEquals(listOf(envelope.messageId), pendingBefore.messageIds)

        // 1. A signature-only GET (no JWT bearer) returns exactly that envelope; it decrypts on the recipient.
        val path = "/v2/relay/${envelope.messageId}"
        val fetched = client.get(path) { signedHeaders(recipient, "GET", path, ByteArray(0)) }
        assertEquals(HttpStatusCode.OK, fetched.status)
        val receivedEnv = ProtocolCodec.decodeFromCbor<Envelope>(fetched.readRawBytes())
        assertTrue(EnvelopeCrypto.verify(receivedEnv, sender.publicKeySpki))
        val plaintext = EnvelopeCrypto.open(receivedEnv, recipient.clientId, recipientHpke.privateKeyset)
        assertEquals("Dinner at 7?", ProtocolCodec.decodeFromCbor<CapturedNotification>(plaintext).text)

        // 2. The broker acked/dropped it on read, so a second fetch of the same id is a miss...
        assertEquals(
            HttpStatusCode.NotFound,
            client.get(path) { signedHeaders(recipient, "GET", path, ByteArray(0)) }.status,
        )
        // ...and the drain list is now empty.
        val pendingAfter = ProtocolCodec.decodeFromJson<RelayPending>(
            client.get("/v2/relay") { signedHeaders(recipient, "GET", "/v2/relay", ByteArray(0)) }.bodyAsText()
        )
        assertTrue(pendingAfter.messageIds.isEmpty())

        // 3. An unknown message id is a miss too (and only the recipient's own relay is reachable).
        val unknown = "/v2/relay/01J0NOPE99"
        assertEquals(
            HttpStatusCode.NotFound,
            client.get(unknown) { signedHeaders(recipient, "GET", unknown, ByteArray(0)) }.status,
        )
    }

    @Test
    fun relayFetch_withPeekHeaderLeavesMessageQueuedUntilExplicitAck() = testApplication {
        val tmp = File.createTempFile("notisync-relaypeek", ".db").also { it.deleteOnExit() }
        System.setProperty("NOTISYNC_DB_PATH", tmp.absolutePath)
        System.setProperty("NOTISYNC_FCM_ENABLED", "false")
        System.setProperty("NOTISYNC_SECURITY_ENABLED", "false")
        application { module() }

        val sender = SoftwareIdentitySigner.generate()
        val senderHpke = Hpke.generateKeyPair()
        val recipient = SoftwareIdentitySigner.generate()
        val recipientHpke = Hpke.generateKeyPair()

        client.register(sender, senderHpke)
        client.register(recipient, recipientHpke)
        client.post("/v2/routes") { setBody(ProtocolCodec.encodeToCbor(listOf(routeBlob(recipient, "fake-token")))) }

        val body = ProtocolCodec.encodeToCbor(
            CapturedNotification(
                sourceClientId = sender.clientId,
                sourceKey = "0|com.example.chat|8|null",
                packageName = "com.example.chat",
                appLabel = "Chat",
                title = "Alice",
                text = "Peek first, ack later",
                importance = MirrorImportance.HIGH,
                postTime = 1_750_000_000_000L,
            )
        )
        val envelope = EnvelopeCrypto.seal(
            signer = sender,
            typ = MessageType.NOTIFICATION,
            bodyPlaintext = body,
            recipients = listOf(RecipientKey(recipient.clientId, recipientHpke.publicKeyset)),
            messageId = "01J0PEEK001",
            seq = 1L,
            createdAt = 1_750_000_000_000L,
        )
        client.post("/v2/send") { setBody(ProtocolCodec.encodeToCbor(envelope)) }

        val path = "/v2/relay/${envelope.messageId}"
        val peeked = client.get(path) {
            signedHeaders(recipient, "GET", path, ByteArray(0))
            header("Peek", "true")
        }
        assertEquals(HttpStatusCode.OK, peeked.status)
        val receivedEnv = ProtocolCodec.decodeFromCbor<Envelope>(peeked.readRawBytes())
        assertEquals(envelope.messageId, receivedEnv.messageId)

        val pendingAfterPeek = ProtocolCodec.decodeFromJson<RelayPending>(
            client.get("/v2/relay") { signedHeaders(recipient, "GET", "/v2/relay", ByteArray(0)) }.bodyAsText()
        )
        assertEquals(listOf(envelope.messageId), pendingAfterPeek.messageIds)

        val ackBody = ProtocolCodec.encodeToJson(RelayAck(listOf(envelope.messageId))).toByteArray(Charsets.UTF_8)
        val ackResp = client.post("/v2/relay/ack") {
            contentType(ContentType.Application.Json)
            signedHeaders(recipient, "POST", "/v2/relay/ack", ackBody)
            setBody(ackBody)
        }
        assertEquals(HttpStatusCode.OK, ackResp.status)

        val pendingAfterAck = ProtocolCodec.decodeFromJson<RelayPending>(
            client.get("/v2/relay") { signedHeaders(recipient, "GET", "/v2/relay", ByteArray(0)) }.bodyAsText()
        )
        assertTrue(pendingAfterAck.messageIds.isEmpty())
    }

    @Test
    fun relayBatchAck_signedOnly_dropsOnlyTheNamedMessages() = testApplication {
        val tmp = File.createTempFile("notisync-batchack", ".db").also { it.deleteOnExit() }
        System.setProperty("NOTISYNC_DB_PATH", tmp.absolutePath)
        System.setProperty("NOTISYNC_FCM_ENABLED", "false")
        System.setProperty("NOTISYNC_SECURITY_ENABLED", "false")
        application { module() }

        val sender = SoftwareIdentitySigner.generate()
        val senderHpke = Hpke.generateKeyPair()
        val recipient = SoftwareIdentitySigner.generate()
        val recipientHpke = Hpke.generateKeyPair()

        client.register(sender, senderHpke)
        client.register(recipient, recipientHpke)
        client.post("/v2/routes") { setBody(ProtocolCodec.encodeToCbor(listOf(routeBlob(recipient, "fake-token")))) }

        suspend fun queue(messageId: String) {
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
                messageId = messageId,
                seq = 1L,
                createdAt = 1_750_000_000_000L,
            )
            client.post("/v2/send") { setBody(ProtocolCodec.encodeToCbor(envelope)) }
        }
        queue("01J0ACK0001"); queue("01J0ACK0002"); queue("01J0ACK0003")

        val pendingBefore = ProtocolCodec.decodeFromJson<RelayPending>(
            client.get("/v2/relay") { signedHeaders(recipient, "GET", "/v2/relay", ByteArray(0)) }.bodyAsText()
        )
        assertEquals(setOf("01J0ACK0001", "01J0ACK0002", "01J0ACK0003"), pendingBefore.messageIds.toSet())

        // Batch-ack two of the three with a signature-only POST (no JWT) — the inline-push / dismissal path.
        val ackBody = ProtocolCodec.encodeToJson(RelayAck(listOf("01J0ACK0001", "01J0ACK0002"))).toByteArray(Charsets.UTF_8)
        val ackResp = client.post("/v2/relay/ack") {
            contentType(ContentType.Application.Json)
            signedHeaders(recipient, "POST", "/v2/relay/ack", ackBody)
            setBody(ackBody)
        }
        assertEquals(HttpStatusCode.OK, ackResp.status)

        // Only the un-acked id remains queued.
        val pendingAfter = ProtocolCodec.decodeFromJson<RelayPending>(
            client.get("/v2/relay") { signedHeaders(recipient, "GET", "/v2/relay", ByteArray(0)) }.bodyAsText()
        )
        assertEquals(listOf("01J0ACK0003"), pendingAfter.messageIds)
    }

    @Test
    fun multiRecipientSend_stripsForeignKeyMaterialButStillVerifiesAndDecrypts() = testApplication {
        val tmp = File.createTempFile("notisync-strip", ".db").also { it.deleteOnExit() }
        System.setProperty("NOTISYNC_DB_PATH", tmp.absolutePath)
        System.setProperty("NOTISYNC_FCM_ENABLED", "false")
        System.setProperty("NOTISYNC_SECURITY_ENABLED", "false")
        application { module() }

        val sender = SoftwareIdentitySigner.generate()
        val senderHpke = Hpke.generateKeyPair()
        val alice = SoftwareIdentitySigner.generate()
        val aliceHpke = Hpke.generateKeyPair()
        val bob = SoftwareIdentitySigner.generate()
        val bobHpke = Hpke.generateKeyPair()

        client.register(sender, senderHpke)
        client.register(alice, aliceHpke)
        client.register(bob, bobHpke)
        client.post("/v2/routes") { setBody(ProtocolCodec.encodeToCbor(listOf(routeBlob(alice, "alice-token")))) }
        client.post("/v2/routes") { setBody(ProtocolCodec.encodeToCbor(listOf(routeBlob(bob, "bob-token")))) }

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
            recipients = listOf(
                RecipientKey(alice.clientId, aliceHpke.publicKeyset),
                RecipientKey(bob.clientId, bobHpke.publicKeyset),
            ),
            messageId = "01J0STRIP01",
            seq = 1L,
            createdAt = 1_750_000_000_000L,
        )
        val fullBytes = ProtocolCodec.encodeToCbor(envelope)
        client.post("/v2/send") { setBody(fullBytes) }

        // Each recipient pulls their own queued copy from the relay.
        val relayPath = "/v2/relay/${envelope.messageId}"
        val aliceResp = client.get(relayPath) { signedHeaders(alice, "GET", relayPath, ByteArray(0)) }
        assertEquals(HttpStatusCode.OK, aliceResp.status)
        val aliceEnv = ProtocolCodec.decodeFromCbor<Envelope>(aliceResp.readRawBytes())
        val bobResp = client.get(relayPath) { signedHeaders(bob, "GET", relayPath, ByteArray(0)) }
        assertEquals(HttpStatusCode.OK, bobResp.status)
        val bobEnv = ProtocolCodec.decodeFromCbor<Envelope>(bobResp.readRawBytes())

        // Alice's copy: her sealedDek is intact, Bob's is blanked — and vice versa.
        fun keyFor(env: Envelope, id: ClientId) = env.recipients.first { it.recipientId == id }.sealedDek
        assertTrue("alice keeps her own key", keyFor(aliceEnv, alice.clientId).isNotEmpty())
        assertEquals("bob's key stripped from alice's copy", 0, keyFor(aliceEnv, bob.clientId).size)
        assertTrue("bob keeps his own key", keyFor(bobEnv, bob.clientId).isNotEmpty())
        assertEquals("alice's key stripped from bob's copy", 0, keyFor(bobEnv, alice.clientId).size)

        // Signature still verifies (authBytes commits to ids + body hash, not the sealed DEKs)...
        assertTrue(EnvelopeCrypto.verify(aliceEnv, sender.publicKeySpki))
        assertTrue(EnvelopeCrypto.verify(bobEnv, sender.publicKeySpki))
        // ...and each recipient still decrypts with their own key.
        assertEquals(
            "Dinner at 7?",
            ProtocolCodec.decodeFromCbor<CapturedNotification>(
                EnvelopeCrypto.open(aliceEnv, alice.clientId, aliceHpke.privateKeyset)
            ).text,
        )
        assertEquals(
            "Dinner at 7?",
            ProtocolCodec.decodeFromCbor<CapturedNotification>(
                EnvelopeCrypto.open(bobEnv, bob.clientId, bobHpke.privateKeyset)
            ).text,
        )
        // The per-recipient copy is smaller than the full two-key envelope.
        assertTrue("stripped copy should be smaller", ProtocolCodec.encodeToCbor(aliceEnv).size < fullBytes.size)
    }

    @Test
    fun privateAsset_storeFetchOverwriteBadIdOversize() = testApplication {
        val tmp = File.createTempFile("notisync-assets", ".db").also { it.deleteOnExit() }
        System.setProperty("NOTISYNC_DB_PATH", tmp.absolutePath)
        System.setProperty("NOTISYNC_FCM_ENABLED", "false")
        System.setProperty("NOTISYNC_SECURITY_ENABLED", "false")
        System.setProperty("NOTISYNC_MAX_ASSET_BYTES", "64")
        try {
            application { module() }

            val src = SoftwareIdentitySigner.generate().clientId
            val assetId = Base32.encode(ByteArray(24) { (it + 1).toByte() }) // 39-char opaque id
            val ciphertext = ByteArray(40) { it.toByte() }

            // 1. Store succeeds, then fetch returns the exact ciphertext.
            assertEquals(
                HttpStatusCode.OK,
                client.post("/v2/assets/${src.value}/$assetId") { setBody(ciphertext) }.status,
            )
            val fetched = client.get("/v2/assets/${src.value}/$assetId")
            assertEquals(HttpStatusCode.OK, fetched.status)
            assertArrayEquals(ciphertext, fetched.readRawBytes())

            // 2. Overwrite is rejected (first-writer-wins on an unguessable id) and the original survives.
            assertEquals(
                HttpStatusCode.Conflict,
                client.post("/v2/assets/${src.value}/$assetId") { setBody(ByteArray(8)) }.status,
            )
            assertArrayEquals(ciphertext, client.get("/v2/assets/${src.value}/$assetId").readRawBytes())

            // 3. A non-opaque id (not Base32 of 24 bytes) is rejected — the content-derived-id guard.
            assertEquals(
                HttpStatusCode.BadRequest,
                client.post("/v2/assets/${src.value}/notanopaqueid") { setBody(ciphertext) }.status,
            )

            // 4. Oversize is rejected before buffering (Content-Length guard, max=64).
            assertEquals(
                HttpStatusCode.PayloadTooLarge,
                client.post("/v2/assets/${src.value}/$assetId") { setBody(ByteArray(100)) }.status,
            )

            // 5. Unknown asset -> 404.
            val other = Base32.encode(ByteArray(24) { (it + 9).toByte() })
            assertEquals(HttpStatusCode.NotFound, client.get("/v2/assets/${src.value}/$other").status)
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
        System.setProperty("NOTISYNC_SECURITY_ENABLED", "true")
        System.setProperty("NOTISYNC_JWT_PRIVATE_KEY_PATH", jwtKey.absolutePath)
        application { brokerModule() }

        val signer = SoftwareIdentitySigner.generate()
        val hpke = Hpke.generateKeyPair()
        val op = SoftwareOperationalSigner.generate(signer.clientId, signerEpoch = 1)
        val ke = keyEpochBlob(signer, op, hpke, epoch = 1)
        val keBytes = ProtocolCodec.encodeToCbor(ke)

        // Publishing a key-epoch without a bearer is rejected when attestation is enabled.
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v2/keyepoch") { setBody(keBytes) }.status)

        // Unauthenticated status discovery: broker is secured (legacy playIntegrityRequired flag), not yet verified.
        val statusBefore = ProtocolCodec.decodeFromJson<VerificationStatusResponse>(client.get("/v2/status").bodyAsText())
        assertEquals(true, statusBefore.playIntegrityRequired)
        assertEquals(false, statusBefore.verified)

        val verifyRequest = IntegrityVerificationRequest(
            clientId = signer.clientId,
            attestationType = AttestationType.FIREBASE_APP_CHECK,
            clientKeyEpoch = ke,
        )
        val verifyBody = ProtocolCodec.encodeToJson(verifyRequest).toByteArray(Charsets.UTF_8)
        val verifyResponse = client.post("/v2/integrity/verify") {
            contentType(ContentType.Application.Json)
            signedHeaders(signer, "POST", "/v2/integrity/verify", verifyBody, pow = true)
            setBody(verifyBody)
        }
        assertEquals(HttpStatusCode.OK, verifyResponse.status)
        val token = ProtocolCodec.decodeFromJson<IntegrityVerificationResponse>(verifyResponse.bodyAsText()).token

        // With the bearer presented, status now reports this client as verified.
        val statusAfter = ProtocolCodec.decodeFromJson<VerificationStatusResponse>(
            client.get("/v2/status") { header(HttpHeaders.Authorization, "Bearer $token") }.bodyAsText()
        )
        assertEquals(true, statusAfter.verified)
        assertEquals(signer.clientId, statusAfter.clientId)

        // A subsequent key-epoch publish with the bearer + identity signature is accepted.
        assertEquals(
            HttpStatusCode.OK,
            client.post("/v2/keyepoch") {
                signedHeaders(signer, "POST", "/v2/keyepoch", keBytes, token)
                setBody(keBytes)
            }.status,
        )

        val fetched = client.get("/v2/keyepoch/${signer.clientId.value}") {
            signedHeaders(signer, "GET", "/v2/keyepoch/${signer.clientId.value}", ByteArray(0), token)
        }
        assertEquals(HttpStatusCode.OK, fetched.status)
        assertNotNull(Verification.verifyKeyEpoch(ProtocolCodec.decodeFromCbor<SignedBlob>(fetched.readRawBytes())))
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
        header(HttpRequestSigning.HEADER_SIGNER_EPOCH, signed.signerEpoch.toString())
        if (pow) {
            val powTimestamp = System.currentTimeMillis()
            val powNonce = ProofOfWork.solve(signed.signature, powTimestamp)
            header(ProofOfWork.HEADER_NONCE, powNonce)
            header(ProofOfWork.HEADER_TIMESTAMP, powTimestamp.toString())
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.operationalSignedHeaders(
        signer: OperationalSigner,
        method: String,
        pathAndQuery: String,
        body: ByteArray,
        bearerToken: String? = null,
    ) {
        bearerToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        val signed = HttpRequestSigning.sign(signer, method, pathAndQuery, body)
        header(HttpRequestSigning.HEADER_CLIENT_ID, signed.clientId.value)
        header(HttpRequestSigning.HEADER_TIMESTAMP, signed.timestampMillis.toString())
        header(HttpRequestSigning.HEADER_NONCE, signed.nonce)
        header(HttpRequestSigning.HEADER_CONTENT_SHA256, signed.contentSha256)
        header(HttpRequestSigning.HEADER_SIGNATURE, signed.signature)
        header(HttpRequestSigning.HEADER_SIGNER_EPOCH, signed.signerEpoch.toString())
    }
}
