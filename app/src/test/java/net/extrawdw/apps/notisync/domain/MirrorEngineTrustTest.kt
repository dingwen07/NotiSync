package net.extrawdw.apps.notisync.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.ActivityLog
import net.extrawdw.apps.notisync.data.IncomingTrustResult
import net.extrawdw.apps.notisync.data.Peer
import net.extrawdw.apps.notisync.data.TrustPrompt
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SendResult
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.TrustStatus
import net.extrawdw.notisync.protocol.TrustTable
import net.extrawdw.notisync.protocol.TrustTableEntry
import net.extrawdw.notisync.protocol.Transport
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.crypto.EnvelopeCrypto
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.RecipientKey
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class MirrorEngineTrustTest {

    private val enc = Base64.getEncoder()

    private class CapturingTransport : Transport {
        override val type = TransportType.WEBSOCKET
        val sent = mutableListOf<Envelope>()
        override suspend fun send(envelope: Envelope, urgency: Urgency): SendResult { sent.add(envelope); return SendResult(true) }
        override suspend fun publishCard(card: SignedBlob) = Unit
        override suspend fun publishRoutes(routes: List<SignedBlob>) = Unit
        override suspend fun fetchCard(clientId: ClientId): SignedBlob? = null
        override suspend fun uploadPrivateAsset(sourceClientId: ClientId, assetId: String, ciphertext: ByteArray) = true
        override suspend fun fetchPrivateAsset(sourceClientId: ClientId, assetId: String): ByteArray? = null
        override fun incoming(): Flow<Envelope> = emptyFlow()
    }

    private val noopRenderer = object : MirrorRenderer {
        override fun render(notif: CapturedNotification) = Unit
        override fun clear(sourceClientId: ClientId, sourceKey: String) = Unit
    }

    private fun peerFor(signer: IdentitySigner, hpkePublicKeyset: ByteArray) = Peer(
        clientId = signer.clientId, displayName = "peer", platform = "android",
        identityPublicKeyB64 = enc.encodeToString(signer.publicKeySpki),
        hpkePublicKeysetB64 = enc.encodeToString(hpkePublicKeyset), addedAt = 1L,
    )

    @Test
    fun broadcastTrust_sealsTrustTableToPeers() = runBlocking {
        val me = SoftwareIdentitySigner.generate(); val myHpke = Hpke.generateKeyPair()
        val peerSigner = SoftwareIdentitySigner.generate(); val peerHpke = Hpke.generateKeyPair()
        val transport = CapturingTransport()
        val table = TrustTable(listOf(TrustTableEntry(ClientId("x"), TrustStatus.TRUSTED, 5L, keyAvailable = true)))
        val engine = MirrorEngine(
            signer = me, myHpkePrivateKeyset = myHpke.privateKeyset, transport = transport,
            peersProvider = { listOf(peerFor(peerSigner, peerHpke.publicKeyset)) }, renderer = noopRenderer,
            activityLog = ActivityLog(), scope = CoroutineScope(Dispatchers.Unconfined),
            trustTableProvider = { table },
        )

        engine.broadcastTrust()

        assertEquals(1, transport.sent.size)
        val env = transport.sent.single()
        assertEquals(MessageType.DATA_SYNC, env.typ)
        val body = EnvelopeCrypto.open(env, peerSigner.clientId, peerHpke.privateKeyset)
        val decoded = ProtocolCodec.decodeFromCbor<DataSync>(body)
        assertEquals(DataSyncKind.TRUST, decoded.kind)
        assertEquals(ClientId("x"), decoded.trust?.entries?.single()?.clientId)
    }

    @Test
    fun broadcastTrust_pushesOwnCardsAlongsideTable() = runBlocking {
        val me = SoftwareIdentitySigner.generate(); val myHpke = Hpke.generateKeyPair()
        val peerSigner = SoftwareIdentitySigner.generate(); val peerHpke = Hpke.generateKeyPair()
        val transport = CapturingTransport()
        val card = SignedBlob(typ = SignedType.CLIENT_CARD, signerId = ClientId("x"), payload = byteArrayOf(1), sig = byteArrayOf(2))
        val engine = MirrorEngine(
            signer = me, myHpkePrivateKeyset = myHpke.privateKeyset, transport = transport,
            peersProvider = { listOf(peerFor(peerSigner, peerHpke.publicKeyset)) }, renderer = noopRenderer,
            activityLog = ActivityLog(), scope = CoroutineScope(Dispatchers.Unconfined),
            trustTableProvider = { TrustTable(emptyList()) },
            ownCardsProvider = { listOf(card) },
        )

        engine.broadcastTrust()

        assertEquals(2, transport.sent.size) // a TRUST table + a CARD push
        val kinds = transport.sent.map {
            ProtocolCodec.decodeFromCbor<DataSync>(EnvelopeCrypto.open(it, peerSigner.clientId, peerHpke.privateKeyset)).kind
        }
        assertEquals(listOf(DataSyncKind.TRUST, DataSyncKind.CARD), kinds)
        val pushed = ProtocolCodec.decodeFromCbor<DataSync>(EnvelopeCrypto.open(transport.sent[1], peerSigner.clientId, peerHpke.privateKeyset)).card
        assertEquals(ClientId("x"), pushed?.clientId)
    }

    @Test
    fun receivingTrustTable_invokesHandlerAndOffersCards() = runBlocking {
        val me = SoftwareIdentitySigner.generate(); val myHpke = Hpke.generateKeyPair()
        val sender = SoftwareIdentitySigner.generate(); val senderHpke = Hpke.generateKeyPair()
        val transport = CapturingTransport()
        val seenTables = mutableListOf<Pair<ClientId, TrustTable>>()
        val prompts = mutableListOf<TrustPrompt>()
        // The card we (the receiver) will offer back to the sender as a keyless repair.
        val offered = SignedBlob(typ = SignedType.CLIENT_CARD, signerId = ClientId("x"), payload = byteArrayOf(1), sig = byteArrayOf(2))

        val engine = MirrorEngine(
            signer = me, myHpkePrivateKeyset = myHpke.privateKeyset, transport = transport,
            peersProvider = { listOf(peerFor(sender, senderHpke.publicKeyset)) }, renderer = noopRenderer,
            activityLog = ActivityLog(), scope = CoroutineScope(Dispatchers.Unconfined),
            onTrustTable = { s, t -> seenTables.add(s to t); IncomingTrustResult(listOf(ClientId("x") to TrustPrompt.NEW_TRUST), listOf(offered)) },
            onTrustPrompt = { _, p, _ -> prompts.add(p) },
        )

        val table = TrustTable(listOf(TrustTableEntry(ClientId("x"), TrustStatus.TRUSTED, 5L, keyAvailable = false)))
        val env = EnvelopeCrypto.seal(
            sender, MessageType.DATA_SYNC,
            ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.TRUST, trust = table)),
            listOf(RecipientKey(me.clientId, myHpke.publicKeyset)), "t1", 1L, 1L,
        )
        engine.handleEnvelope(env)

        assertEquals(1, seenTables.size)
        assertEquals(sender.clientId, seenTables.single().first)
        assertEquals(listOf(TrustPrompt.NEW_TRUST), prompts)
        // The offered card was sealed back to the sender as a CARD delivery.
        val cardEnv = transport.sent.single()
        val cardBody = ProtocolCodec.decodeFromCbor<DataSync>(EnvelopeCrypto.open(cardEnv, sender.clientId, senderHpke.privateKeyset))
        assertEquals(DataSyncKind.CARD, cardBody.kind)
        assertEquals(ClientId("x"), cardBody.card?.clientId)
    }

    @Test
    fun receivingCardDelivery_invokesHandler() = runBlocking {
        val me = SoftwareIdentitySigner.generate(); val myHpke = Hpke.generateKeyPair()
        val sender = SoftwareIdentitySigner.generate(); val senderHpke = Hpke.generateKeyPair()
        val delivered = mutableListOf<ClientId>()
        val card = SignedBlob(typ = SignedType.CLIENT_CARD, signerId = ClientId("x"), payload = byteArrayOf(1), sig = byteArrayOf(2))

        val engine = MirrorEngine(
            signer = me, myHpkePrivateKeyset = myHpke.privateKeyset, transport = CapturingTransport(),
            peersProvider = { listOf(peerFor(sender, senderHpke.publicKeyset)) }, renderer = noopRenderer,
            activityLog = ActivityLog(), scope = CoroutineScope(Dispatchers.Unconfined),
            onCardDelivery = { id, _ -> delivered.add(id); true },
        )

        val env = EnvelopeCrypto.seal(
            sender, MessageType.DATA_SYNC,
            ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.CARD, card = net.extrawdw.notisync.protocol.CardDelivery(ClientId("x"), card))),
            listOf(RecipientKey(me.clientId, myHpke.publicKeyset)), "c1", 1L, 1L,
        )
        engine.handleEnvelope(env)

        assertEquals(listOf(ClientId("x")), delivered)
        assertTrue(true)
    }
}
