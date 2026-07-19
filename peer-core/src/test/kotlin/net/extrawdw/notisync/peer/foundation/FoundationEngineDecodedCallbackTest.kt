package net.extrawdw.notisync.peer.foundation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import net.extrawdw.notisync.peer.channel.ChannelLogger
import net.extrawdw.notisync.peer.channel.DeliveryOutcome
import net.extrawdw.notisync.peer.channel.InboundMessage
import net.extrawdw.notisync.peer.channel.PeerDirectory
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.SecureChannel
import net.extrawdw.notisync.peer.channel.SenderKey
import net.extrawdw.notisync.peer.trust.IncomingTrustResult
import net.extrawdw.notisync.peer.trust.Peer
import net.extrawdw.notisync.peer.trust.TrustState
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.LiveDeliveryDisposition
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SendResult
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.Transport
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.TrustTable
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.crypto.EnvelopeCrypto
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.RecipientKey
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import net.extrawdw.notisync.protocol.crypto.SoftwareOperationalSigner
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FoundationEngineDecodedCallbackTest {
    @Test
    fun decodedCallbackReceivesSoleDecodedObjectBeforeSubkindHandler() {
        val receiver = SoftwareIdentitySigner.generate()
        val receiverHpke = Hpke.generateKeyPair()
        val sender = SoftwareIdentitySigner.generate()
        val channel = SecureChannel(
            signer = receiver,
            operationalSigner = { SoftwareOperationalSigner.generate(receiver.clientId, 1) },
            myHpkePrivate = { receiverHpke.privateKeyset },
            transport = NoopTransport,
            directory = object : PeerDirectory {
                override fun resolveSender(id: ClientId, signerEpoch: Int): SenderKey? =
                    if (id == sender.clientId && signerEpoch == 0) SenderKey(sender.publicKeySpki, true) else null

                override fun recipients(scope: Recipients): List<RecipientKey> = emptyList()
            },
            log = ChannelLogger { },
        )
        val order = mutableListOf<String>()
        var observedMessage: InboundMessage? = null
        var observedDecoded: DataSync? = null
        var handledDecoded: DataSync? = null
        FoundationEngine(
            channel = channel,
            trust = EmptyTrustState,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onTrustPrompt = { _, _, _ -> },
            onAsset = { _, _ -> },
            onRunSync = { _, sync ->
                order += "run"
                handledDecoded = sync
            },
            onDecodedDataSync = { message, sync ->
                order += "decoded"
                observedMessage = message
                observedDecoded = sync
            },
        ).register()
        val envelope = EnvelopeCrypto.seal(
            sender,
            MessageType.DATA_SYNC,
            ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.RUN)),
            listOf(RecipientKey(receiver.clientId, receiverHpke.publicKeyset)),
            "decoded-once",
            1L,
            1L,
        )

        assertEquals(DeliveryOutcome.HANDLED, channel.deliver(envelope))
        assertEquals(listOf("decoded", "run"), order)
        assertEquals("decoded-once", observedMessage?.messageId)
        assertSame(observedDecoded, handledDecoded)
    }

    @Test
    fun malformedCallbackReceivesAuthenticatedOpaqueDataSyncWithoutSemanticDispatch() {
        val receiver = SoftwareIdentitySigner.generate()
        val receiverHpke = Hpke.generateKeyPair()
        val sender = SoftwareIdentitySigner.generate()
        val channel = SecureChannel(
            signer = receiver,
            operationalSigner = { SoftwareOperationalSigner.generate(receiver.clientId, 1) },
            myHpkePrivate = { receiverHpke.privateKeyset },
            transport = NoopTransport,
            directory = object : PeerDirectory {
                override fun resolveSender(id: ClientId, signerEpoch: Int): SenderKey? =
                    if (id == sender.clientId && signerEpoch == 0) SenderKey(sender.publicKeySpki, true) else null

                override fun recipients(scope: Recipients): List<RecipientKey> = emptyList()
            },
            log = ChannelLogger { },
        )
        val malformedBody = byteArrayOf(0x7f)
        var observed: InboundMessage? = null
        var semanticDispatches = 0
        FoundationEngine(
            channel = channel,
            trust = EmptyTrustState,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onTrustPrompt = { _, _, _ -> },
            onAsset = { _, _ -> },
            onRunSync = { _, _ -> semanticDispatches++ },
            onDecodedDataSync = { _, _ -> semanticDispatches++ },
            onMalformedDataSync = { observed = it },
        ).register()
        val envelope = EnvelopeCrypto.seal(
            sender,
            MessageType.DATA_SYNC,
            malformedBody,
            listOf(RecipientKey(receiver.clientId, receiverHpke.publicKeyset)),
            "malformed-opaque",
            1L,
            1L,
        )

        assertEquals(DeliveryOutcome.HANDLED, channel.deliver(envelope))
        assertEquals("malformed-opaque", observed?.messageId)
        assertArrayEquals(malformedBody, observed?.body)
        assertEquals(0, semanticDispatches)
    }

    private object EmptyTrustState : TrustState {
        override val activePeers = MutableStateFlow(emptyList<Peer>())
        override fun displayName(clientId: ClientId): String? = null
        override fun buildTrustTable() = TrustTable(emptyList())
        override fun applyProfile(update: ProfileUpdate) = false
        override fun applyIncomingTable(sender: ClientId, table: TrustTable) =
            IncomingTrustResult(emptyList(), emptyList())
        override fun applyCard(clientId: ClientId, cardBlob: SignedBlob) = false
    }

    private object NoopTransport : Transport {
        override val type = TransportType.WEBSOCKET
        override suspend fun publishKeyEpoch(keyEpoch: SignedBlob) = Unit
        override suspend fun publishRoutes(routes: List<SignedBlob>) = Unit
        override suspend fun fetchKeyEpoch(clientId: ClientId, epoch: Int?): SignedBlob? = null
        override suspend fun send(envelope: Envelope, urgency: Urgency) = SendResult(accepted = true)
        override suspend fun runLiveDelivery(onEnvelope: (Envelope) -> LiveDeliveryDisposition) = Unit
        override suspend fun uploadPrivateAsset(sourceClientId: ClientId, assetId: String, ciphertext: ByteArray) = false
        override suspend fun fetchPrivateAsset(sourceClientId: ClientId, assetId: String): ByteArray? = null
    }
}
