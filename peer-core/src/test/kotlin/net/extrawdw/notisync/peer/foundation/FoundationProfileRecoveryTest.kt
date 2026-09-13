package net.extrawdw.notisync.peer.foundation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.peer.channel.ChannelLogger
import net.extrawdw.notisync.peer.channel.DeliveryOutcome
import net.extrawdw.notisync.peer.channel.SecureChannel
import net.extrawdw.notisync.peer.ports.TrustPersistence
import net.extrawdw.notisync.peer.trust.TrustStore
import net.extrawdw.notisync.protocol.*
import net.extrawdw.notisync.protocol.crypto.*
import org.junit.Assert.*
import org.junit.Test

class FoundationProfileRecoveryTest {
    @Test
    fun authenticatedProfileBootstrapsCardlessPeerAndKeepsRevisionAcrossReload() {
        val receiver = Node()
        val sender = Node()
        receiver.trust(sender)
        val update = ProfileUpdate(sender.id, "Recovered name", "desktop", listOf(Capability.SSH_AGENT_V1), 30L)

        assertEquals(DeliveryOutcome.HANDLED, receiver.receive(sender, DataSync(DataSyncKind.PROFILE, profile = update)))
        assertNull(receiver.trust.cardFor(sender.id))
        assertEquals("Recovered name", receiver.trust.displayName(sender.id))
        assertEquals("desktop", receiver.trust.peerPlatform(sender.id))
        assertEquals(listOf(Capability.SSH_AGENT_V1), receiver.trust.peerCapabilities(sender.id))

        val reloaded = TrustStore(receiver.disk, receiver.identity) { 100L }
        assertFalse(reloaded.quarantined.value)
        assertFalse(reloaded.applyProfile(update.copy(displayName = "Stale", updatedAt = 29L)))
        assertFalse(reloaded.applyProfile(update.copy(displayName = "Duplicate")))
        assertEquals("Recovered name", reloaded.displayName(sender.id))
        // Receiving the older CARD later must not roll the recovered profile back.
        assertTrue(reloaded.applyCard(sender.id, sender.card(createdAt = 20L)))
        assertEquals("Recovered name", reloaded.displayName(sender.id))
        assertTrue(reloaded.applyCard(sender.id, sender.card(createdAt = 40L)))
        assertFalse(reloaded.applyProfile(update.copy(updatedAt = 39L)))
        assertTrue(reloaded.applyProfile(update.copy(displayName = "Newer", updatedAt = 41L)))
        assertEquals("Newer", reloaded.displayName(sender.id))
    }

    @Test
    fun cardlessProfilesStillRequireTrustedAuthenticatedSubject() {
        val receiver = Node()
        val sender = Node()
        val other = Node()
        val update = ProfileUpdate(sender.id, "Claimed name", "test", emptyList(), 30L)

        assertEquals(DeliveryOutcome.DROPPED, receiver.receive(sender, DataSync(DataSyncKind.PROFILE, profile = update)))
        receiver.trust(sender)
        receiver.trust(other)
        receiver.receive(sender, DataSync(DataSyncKind.PROFILE, profile = update.copy(clientId = other.id)))
        assertNull(receiver.trust.displayName(other.id))
        receiver.trust.revokeLocal(sender.id, 40L)
        assertEquals(DeliveryOutcome.DROPPED, receiver.receive(sender, DataSync(DataSyncKind.PROFILE, profile = update)))
        assertNull(receiver.trust.displayName(sender.id))
    }

    @Test
    fun missingSelfCardIsRepairedDirectlyAndOnlyToRequestingPeer() = runBlocking {
        val owner = Node()
        val requester = Node()
        val bystander = Node()
        owner.trust(requester)
        owner.trust(bystander)
        requester.trust(owner)

        owner.foundation.broadcastTrust()
        assertEquals(0, owner.cardBuilds) // No self CARD minting or broadcast during routine anti-entropy.
        owner.transport.sent.clear()
        requester.foundation.broadcastTrust()
        owner.channel.deliver(requester.transport.sent.first())

        assertEquals(1, owner.cardBuilds)
        val reply = owner.transport.sent.single()
        assertEquals(listOf(requester.id), reply.recipientIds())
        assertEquals(1, reply.signerEpoch) // Ordinary CARD transport uses the operational signing key.
        val sync = ProtocolCodec.decodeFromCbor<DataSync>(
            EnvelopeCrypto.open(reply, requester.id, requester.hpke.privateKeyset)
        )
        assertEquals(owner.id, sync.card?.clientId)
        assertEquals(SignedType.CLIENT_CARD, sync.card?.card?.typ)
        assertNull(sync.card?.epochBlob) // CARD repair does not mint another epoch certificate.

        assertEquals(DeliveryOutcome.HANDLED, requester.channel.deliver(reply))
        assertEquals("Device", requester.trust.displayName(owner.id))
        assertNotNull(requester.trust.cardFor(owner.id))
        owner.transport.sent.clear()
        owner.receive(requester, DataSync(DataSyncKind.TRUST, trust = requester.trust.buildTrustTable()), identitySigned = true)
        assertEquals(1, owner.cardBuilds) // The next table advertises the repaired CARD.
        assertTrue(owner.transport.sent.isEmpty())
    }

    @Test
    fun selfCardRepairHonorsTrustScopeAndMissingMaterialFlag() {
        val owner = Node()
        val requester = Node()
        owner.trust(requester)
        fun request(status: TrustStatus, available: Boolean = false) = DataSync(
            DataSyncKind.TRUST,
            trust = TrustTable(listOf(TrustTableEntry(owner.id, status, 10L, available, epoch = 1))),
        )

        owner.receive(requester, request(TrustStatus.TRUSTED, available = true), identitySigned = true)
        owner.receive(requester, request(TrustStatus.REVOKED), identitySigned = true)
        owner.receive(requester, request(TrustStatus.PENDING_REVOKE), identitySigned = true)
        owner.receive(requester, request(TrustStatus.TRUSTED), identitySigned = false)
        assertEquals(0, owner.cardBuilds)
        assertTrue(owner.transport.sent.isEmpty())

        // An authenticated "other" contact cannot request own-mesh CARD material.
        owner.trust(requester, ownDevice = false)
        owner.receive(requester, request(TrustStatus.TRUSTED), identitySigned = true)
        assertEquals(0, owner.cardBuilds)
        assertTrue(owner.transport.sent.isEmpty())
    }

    private class Node {
        val identity = SoftwareIdentitySigner.generate()
        val id = identity.clientId
        val operational = SoftwareOperationalSigner.generate(id, 1)
        val hpke = Hpke.generateKeyPair()
        val disk = MemoryPersistence()
        val trust = TrustStore(disk, identity) { 100L }
        val transport = CapturingTransport()
        val channel = SecureChannel(
            signer = identity,
            operationalSigner = { operational },
            myHpkePrivate = { hpke.privateKeyset },
            transport = transport,
            directory = TrustPeerDirectory(trust),
            log = ChannelLogger { },
            now = { 100L },
        )
        var cardBuilds = 0
        val foundation = FoundationEngine(
            channel = channel,
            trust = trust,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onTrustPrompt = { _, _, _ -> },
            onAsset = { _, _ -> },
            selfKeyEpoch = ::epoch,
            selfCard = { cardBuilds++; card() },
            now = { 100L },
        ).also { it.register() }
        private var sequence = 0L

        fun trust(peer: Node, ownDevice: Boolean = true) {
            if (trust.statusOf(peer.id) != null) {
                // Explicit reclassification for the authorization test.
                trust.addLocal(peer.card(), 20L, ownDevice)
            } else {
                trust.applyIncomingTable(
                    ClientId("introducer"),
                    TrustTable(listOf(TrustTableEntry(peer.id, TrustStatus.TRUSTED, 10L, false, ownDevice))),
                    10L,
                ) { _, _ -> true }
            }
            assertTrue(trust.applyKeyEpoch(peer.id, peer.epoch()) || trust.peerEpoch(peer.id) == 1)
        }

        fun receive(sender: Node, sync: DataSync, identitySigned: Boolean = false): DeliveryOutcome {
            val payload = ProtocolCodec.encodeToCbor(sync)
            val recipients = listOf(RecipientKey(id, hpke.publicKeyset, 1))
            val sequence = ++sequence
            val envelope = if (identitySigned) {
                EnvelopeCrypto.seal(sender.identity, MessageType.DATA_SYNC, payload, recipients, "incoming-$sequence", sequence, 100L)
            } else {
                EnvelopeCrypto.seal(sender.operational, MessageType.DATA_SYNC, payload, recipients, "incoming-$sequence", sequence, 100L)
            }
            return channel.deliver(envelope)
        }

        fun card(createdAt: Long = 10L): SignedBlob = signed(SignedType.CLIENT_CARD, ProtocolCodec.encodeToCbor(
            ClientCard(clientId = id, identityPublicKey = identity.publicKeySpki, displayName = "Device",
                platform = "test", capabilities = emptyList(), createdAt = createdAt),
        ))

        fun epoch(): SignedBlob = signed(SignedType.KEY_EPOCH, ProtocolCodec.encodeToCbor(
            ClientKeyEpoch(clientId = id, identityPublicKey = identity.publicKeySpki, epoch = 1,
                operationalSigningKey = operational.operationalPublicKeySpki, hpkePublicKey = hpke.publicKeyset,
                purposes = listOf(Purpose.ENVELOPE_SIGN, Purpose.REQUEST_AUTH, Purpose.HPKE_SEAL),
                notBefore = 0L, notAfter = Long.MAX_VALUE, minEpoch = 1),
        ))

        private fun signed(type: String, payload: ByteArray) =
            SignedBlob(typ = type, signerId = id, payload = payload, sig = identity.sign(payload))
    }

    private class MemoryPersistence : TrustPersistence {
        private val values = mutableMapOf<String, String?>()
        override fun read(key: String): String? = values[key]
        override fun write(values: Map<String, String?>) { this.values.putAll(values) }
    }

    private class CapturingTransport : Transport {
        val sent = mutableListOf<Envelope>()
        override val type = TransportType.WEBSOCKET
        override suspend fun publishKeyEpoch(keyEpoch: SignedBlob) = Unit
        override suspend fun publishRoutes(routes: List<SignedBlob>) = Unit
        override suspend fun fetchKeyEpoch(clientId: ClientId, epoch: Int?): SignedBlob? = null
        override suspend fun send(envelope: Envelope, urgency: Urgency): SendResult {
            sent += envelope
            return SendResult(accepted = true)
        }
        override suspend fun runLiveDelivery(onEnvelope: (Envelope) -> LiveDeliveryDisposition) = Unit
        override suspend fun uploadPrivateAsset(sourceClientId: ClientId, assetId: String, ciphertext: ByteArray) = false
        override suspend fun fetchPrivateAsset(sourceClientId: ClientId, assetId: String): ByteArray? = null
    }
}
