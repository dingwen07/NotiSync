package net.extrawdw.notisync.peer.channel

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.HotspotAction
import net.extrawdw.notisync.protocol.HotspotResult
import net.extrawdw.notisync.protocol.HotspotSnapshot
import net.extrawdw.notisync.protocol.HotspotState
import net.extrawdw.notisync.protocol.HotspotSync
import net.extrawdw.notisync.protocol.LiveDeliveryDisposition
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SendResult
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.Transport
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.crypto.EnvelopeCrypto
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.OperationalSigner
import net.extrawdw.notisync.protocol.crypto.RecipientKey
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import net.extrawdw.notisync.protocol.crypto.SoftwareOperationalSigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureChannelOutboundDispatcherTest {
    @Test
    fun hotspotStatusUsesOneEnvelopeAndActivityReceiptWithActualRecipientCount() = runBlocking {
        val identity = SoftwareIdentitySigner.generate()
        val controllers = (1..3).associate { ClientId("controller-$it") to Hpke.generateKeyPair() }
        val brokenPeer = ClientId("unsealable-controller")
        val data = DataSync(DataSyncKind.HOTSPOT, hotspot = HotspotSync(
            action = HotspotAction.STATUS, hotspotDeviceId = identity.clientId,
            issuedAt = 100_000, expiresAt = 160_000, requestId = "controller-request",
            snapshot = HotspotSnapshot(HotspotState.ENABLED, "Test Wi-Fi", "test-password", 2, true),
            result = HotspotResult.OK,
        ))
        for (includeBrokenPeer in listOf(false, true)) {
            val signatures = mutableListOf<String>()
            val operational = RecordingOperationalSigner(SoftwareOperationalSigner.generate(identity.clientId, 1), signatures)
            val keys = controllers.map { (id, key) -> RecipientKey(id, key.publicKeyset) } +
                if (includeBrokenPeer) listOf(RecipientKey(brokenPeer, byteArrayOf(1, 2, 3))) else emptyList()
            val audience = Recipients.OnlySet(keys.map { it.clientId }.toSet())
            val transport = RecordingTransport()
            val receipts = mutableListOf<Triple<MessageType, DataSync, Int>>()
            val channel = SecureChannel(
                signer = identity, operationalSigner = { operational }, myHpkePrivate = { null },
                transport = transport, log = ChannelLogger { }, now = { 100_000 },
                directory = object : PeerDirectory {
                    override fun resolveSender(id: ClientId, signerEpoch: Int): SenderKey? = null
                    override fun recipients(scope: Recipients): List<RecipientKey> {
                        assertEquals(audience, scope)
                        return keys
                    }
                },
                onSent = { type, body, count -> receipts += Triple(type, ProtocolCodec.decodeFromCbor<DataSync>(body), count) },
            )
            assertEquals(controllers.size,
                channel.send(MessageType.DATA_SYNC, ProtocolCodec.encodeToCbor(data), audience, Urgency.NORMAL))
            assertEquals(listOf(Triple(MessageType.DATA_SYNC, data, controllers.size)), receipts)
            assertEquals(1, signatures.size)
            val (envelope, urgency) = transport.sent.single()
            assertEquals(Urgency.NORMAL, urgency)
            assertEquals(controllers.keys.toList(), envelope.recipientIds())
            assertTrue(EnvelopeCrypto.verify(envelope, operational.operationalPublicKeySpki))
            for ((id, key) in controllers) {
                assertEquals(data, ProtocolCodec.decodeFromCbor<DataSync>(EnvelopeCrypto.open(envelope, id, key.privateKeyset)))
            }
        }
    }

    @Test
    fun hotspotRefreshFanoutSignsAndSendsOneNormalPriorityEnvelopeForAllProviders() = runBlocking {
        val identity = SoftwareIdentitySigner.generate()
        val signatures = mutableListOf<String>()
        val operational = RecordingOperationalSigner(SoftwareOperationalSigner.generate(identity.clientId, 1), signatures)
        val providers = (1..3).associate { ClientId("provider-$it") to Hpke.generateKeyPair() }
        val audience = Recipients.OwnMeshFiltered(
            requiredCapabilities = setOf(Capability.HOTSPOT_PROVIDER_V1), requireCapabilityRoutingV1 = true,
        )
        val transport = RecordingTransport()
        val channel = SecureChannel(
            signer = identity, operationalSigner = { operational }, myHpkePrivate = { null },
            transport = transport, log = ChannelLogger { }, now = { 100_000 },
            directory = object : PeerDirectory {
                override fun resolveSender(id: ClientId, signerEpoch: Int): SenderKey? = null
                override fun recipients(scope: Recipients): List<RecipientKey> {
                    assertEquals(audience, scope)
                    return providers.map { (id, key) -> RecipientKey(id, key.publicKeyset) }
                }
            },
        )
        val data = DataSync(DataSyncKind.HOTSPOT, hotspot = HotspotSync(
            action = HotspotAction.REFRESH, issuedAt = 100_000, expiresAt = 160_000, requestId = "startup",
        ))
        assertEquals(providers.size,
            channel.send(MessageType.DATA_SYNC, ProtocolCodec.encodeToCbor(data), audience, Urgency.NORMAL))
        assertEquals(1, signatures.size)
        val (envelope, urgency) = transport.sent.single()
        assertEquals(Urgency.NORMAL, urgency)
        assertEquals(providers.keys.toList(), envelope.recipientIds())
        assertTrue(EnvelopeCrypto.verify(envelope, operational.operationalPublicKeySpki))
        for ((id, key) in providers) {
            assertEquals(data, ProtocolCodec.decodeFromCbor<DataSync>(EnvelopeCrypto.open(envelope, id, key.privateKeyset)))
        }
    }

    @Test
    fun outboundSigningAndTransportNeverInheritTheCallerThread() = runBlocking {
        val callerThread = Thread.currentThread().name
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, WORKER_THREAD_NAME)
        }
        executor.asCoroutineDispatcher().use { dispatcher ->
            val identity = SoftwareIdentitySigner.generate()
            val signingThreads = CopyOnWriteArrayList<String>()
            val operational = RecordingOperationalSigner(
                SoftwareOperationalSigner.generate(identity.clientId, 1),
                signingThreads,
            )
            val transport = RecordingTransport()
            val recipient = RecipientKey(ClientId("recipient"), Hpke.generateKeyPair().publicKeyset)
            val observedSends = mutableListOf<Pair<MessageType, Int>>()
            val channel = SecureChannel(
                signer = identity,
                operationalSigner = { operational },
                myHpkePrivate = { null },
                transport = transport,
                directory = FixedDirectory(recipient),
                log = ChannelLogger { },
                now = { 1_750_000_000_000L },
                outboundDispatcher = dispatcher,
                onSent = { type, _, count ->
                    assertEquals(observedSends.size + 1, transport.sendThreads.size)
                    observedSends += type to count
                    // Presentation failures must not turn an accepted message into a send failure.
                    error("activity observer failed")
                },
            )

            channel.send(
                MessageType.DATA_SYNC,
                byteArrayOf(1),
                Recipients.OwnMesh,
                Urgency.HIGH,
            )
            channel.sendAllStrict(
                MessageType.NOTIFICATION,
                listOf(OutboundItem("stable-id", byteArrayOf(2))),
                Recipients.OwnMesh,
                Urgency.HIGH,
            ) { }

            assertEquals(
                listOf(MessageType.DATA_SYNC to 1, MessageType.NOTIFICATION to 1),
                observedSends,
            )
            assertEquals(2, signingThreads.size)
            assertTrue(signingThreads.all { it.startsWith(WORKER_THREAD_NAME) })
            assertEquals(2, transport.sendThreads.size)
            assertTrue(transport.sendThreads.all { it.startsWith(WORKER_THREAD_NAME) })
            assertFalse(callerThread.startsWith(WORKER_THREAD_NAME))
        }
    }

    private class RecordingOperationalSigner(
        private val delegate: OperationalSigner,
        private val threads: MutableList<String>,
    ) : OperationalSigner {
        override val operationalPublicKeySpki = delegate.operationalPublicKeySpki
        override val clientId = delegate.clientId
        override val signerEpoch = delegate.signerEpoch

        override fun sign(data: ByteArray): ByteArray {
            threads += Thread.currentThread().name
            return delegate.sign(data)
        }
    }

    private class FixedDirectory(private val recipient: RecipientKey) : PeerDirectory {
        override fun resolveSender(id: ClientId, signerEpoch: Int): SenderKey? = null
        override fun recipients(scope: Recipients): List<RecipientKey> = listOf(recipient)
    }

    private class RecordingTransport : Transport {
        override val type = TransportType.WEBSOCKET
        val sendThreads = CopyOnWriteArrayList<String>()
        val sent = CopyOnWriteArrayList<Pair<Envelope, Urgency>>()

        override suspend fun publishKeyEpoch(keyEpoch: SignedBlob) = Unit
        override suspend fun publishRoutes(routes: List<SignedBlob>) = Unit
        override suspend fun fetchKeyEpoch(clientId: ClientId, epoch: Int?): SignedBlob? = null
        override suspend fun send(envelope: Envelope, urgency: Urgency): SendResult {
            sendThreads += Thread.currentThread().name
            sent += envelope to urgency
            return SendResult(accepted = true)
        }
        override suspend fun runLiveDelivery(onEnvelope: (Envelope) -> LiveDeliveryDisposition) = Unit
        override suspend fun uploadPrivateAsset(
            sourceClientId: ClientId,
            assetId: String,
            ciphertext: ByteArray,
        ) = false
        override suspend fun fetchPrivateAsset(sourceClientId: ClientId, assetId: String): ByteArray? = null
    }

    private companion object {
        const val WORKER_THREAD_NAME = "keystore-outbound-worker"
    }
}
