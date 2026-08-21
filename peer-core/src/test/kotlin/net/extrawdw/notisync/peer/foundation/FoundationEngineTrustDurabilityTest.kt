package net.extrawdw.notisync.peer.foundation

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import net.extrawdw.notisync.peer.channel.ChannelLogger
import net.extrawdw.notisync.peer.channel.DeliveryOutcome
import net.extrawdw.notisync.peer.channel.PeerDirectory
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.SecureChannel
import net.extrawdw.notisync.peer.channel.SenderKey
import net.extrawdw.notisync.peer.ports.FoundationEventSink
import net.extrawdw.notisync.peer.ports.IncomingTrustPolicy
import net.extrawdw.notisync.peer.ports.TrustPersistence
import net.extrawdw.notisync.peer.transport.DeliveryMode
import net.extrawdw.notisync.peer.trust.TrustPrompt
import net.extrawdw.notisync.peer.trust.TrustStore
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.LiveDeliveryDisposition
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SendResult
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.Transport
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.TrustStatus
import net.extrawdw.notisync.protocol.TrustTable
import net.extrawdw.notisync.protocol.TrustTableEntry
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.crypto.EnvelopeCrypto
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.RecipientKey
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import net.extrawdw.notisync.protocol.crypto.SoftwareOperationalSigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoundationEngineTrustDurabilityTest {
    @Test
    fun `failed atomic automatic decision is retried on identical delivery`() {
        val receiver = SoftwareIdentitySigner.generate()
        val receiverHpke = Hpke.generateKeyPair()
        val sender = SoftwareIdentitySigner.generate()
        val subject = SoftwareIdentitySigner.generate()
        val persistence = SwitchablePersistence()
        val trust = TrustStore(persistence, receiver)
        val channel = SecureChannel(
            signer = receiver,
            operationalSigner = { SoftwareOperationalSigner.generate(receiver.clientId, 1) },
            myHpkePrivate = { receiverHpke.privateKeyset },
            transport = NoopTransport,
            directory = object : PeerDirectory {
                override fun resolveSender(id: ClientId, signerEpoch: Int): SenderKey? =
                    if (id == sender.clientId && signerEpoch == 0) {
                        SenderKey(sender.publicKeySpki, ownDevice = true)
                    } else {
                        null
                    }

                override fun recipients(scope: Recipients): List<RecipientKey> = emptyList()
            },
            log = ChannelLogger { },
        )
        val prompts = mutableListOf<Pair<ClientId, TrustPrompt>>()
        val automaticEvents = mutableListOf<Boolean>()
        FoundationEngine(
            channel = channel,
            trust = trust,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onTrustPrompt = { id, prompt, _ -> prompts += id to prompt },
            onAsset = { _, _ -> },
            incomingTrustPolicy = IncomingTrustPolicy.TRUSTED_OWN_DEVICES,
            eventSink = object : FoundationEventSink {
                override fun trustChanged(
                    subject: ClientId,
                    prompt: TrustPrompt,
                    introducedBy: String,
                    deliveryMode: DeliveryMode?,
                    automaticallyApplied: Boolean,
                ) {
                    automaticEvents += automaticallyApplied
                }
            },
            now = { 30L },
        ).register()
        val table = TrustTable(
            listOf(
                TrustTableEntry(subject.clientId, TrustStatus.TRUSTED, 20L, keyAvailable = false)
            )
        )
        val envelope = EnvelopeCrypto.seal(
            sender,
            MessageType.DATA_SYNC,
            ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.TRUST, trust = table)),
            listOf(RecipientKey(receiver.clientId, receiverHpke.publicKeyset)),
            "retry-automatic-trust",
            1L,
            1L,
        )
        persistence.failWrites = true

        assertEquals(DeliveryOutcome.DROPPED, channel.deliver(envelope))
        assertNull(trust.statusOf(subject.clientId))
        assertTrue(prompts.isEmpty())
        assertTrue(automaticEvents.isEmpty())

        persistence.failWrites = false
        assertEquals(DeliveryOutcome.HANDLED, channel.deliver(envelope))
        assertEquals(TrustStatus.TRUSTED, trust.statusOf(subject.clientId))
        assertTrue(prompts.isEmpty())
        assertEquals(listOf(true), automaticEvents)
    }

    private class SwitchablePersistence : TrustPersistence {
        private val values = linkedMapOf<String, String>()
        var failWrites = false

        override fun read(key: String): String? = values[key]

        override fun write(values: Map<String, String?>) {
            if (failWrites) throw IOException("disk unavailable")
            values.forEach { (key, value) ->
                if (value == null) this.values.remove(key) else this.values[key] = value
            }
        }
    }

    private data object NoopTransport : Transport {
        override val type = TransportType.WEBSOCKET
        override suspend fun publishKeyEpoch(keyEpoch: SignedBlob) = Unit
        override suspend fun publishRoutes(routes: List<SignedBlob>) = Unit
        override suspend fun fetchKeyEpoch(clientId: ClientId, epoch: Int?): SignedBlob? = null
        override suspend fun send(envelope: Envelope, urgency: Urgency) = SendResult(accepted = true)
        override suspend fun runLiveDelivery(onEnvelope: (Envelope) -> LiveDeliveryDisposition) = Unit
        override suspend fun uploadPrivateAsset(
            sourceClientId: ClientId,
            assetId: String,
            ciphertext: ByteArray,
        ) = false

        override suspend fun fetchPrivateAsset(sourceClientId: ClientId, assetId: String): ByteArray? = null
    }
}
