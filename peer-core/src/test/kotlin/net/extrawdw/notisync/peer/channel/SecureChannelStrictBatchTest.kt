package net.extrawdw.notisync.peer.channel

import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.LiveDeliveryDisposition
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.SendResult
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.Transport
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.RecipientKey
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import net.extrawdw.notisync.protocol.crypto.SoftwareOperationalSigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SecureChannelStrictBatchTest {
    @Test
    fun strictBatch_reusesAudienceAndSigner_preservesIds_andCheckpointsAcceptedItems() = runBlocking {
        val recipient = RecipientKey(ClientId("recipient"), Hpke.generateKeyPair().publicKeyset)
        val directory = RecordingDirectory(listOf(recipient))
        val transport = RecordingTransport()
        var signerResolutions = 0
        val channel = channel(directory, transport) { signerResolutions++ }
        val items = listOf(
            OutboundItem("stable-1", byteArrayOf(1)),
            OutboundItem("stable-2", byteArrayOf(2)),
        )
        val checkpoints = mutableListOf<Pair<String, Int>>()

        val recipientCount = channel.sendAllStrict(
            MessageType.NOTIFICATION,
            items,
            Recipients.OwnMesh,
            Urgency.HIGH,
        ) { item -> checkpoints += item.messageId to transport.accepted.size }

        assertEquals(1, recipientCount)
        assertEquals(1, directory.recipientResolutions)
        assertEquals(1, directory.unsealableResolutions)
        assertEquals(1, signerResolutions)
        assertEquals(listOf("stable-1", "stable-2"), transport.attempted.map { it.messageId })
        assertEquals(listOf("stable-1" to 1, "stable-2" to 2), checkpoints)
    }

    @Test
    fun strictBatch_transportRejectionStopsWithoutCheckpointingRejectedItemOrSuffix() = runBlocking {
        val recipient = RecipientKey(ClientId("recipient"), Hpke.generateKeyPair().publicKeyset)
        val transport = RecordingTransport { attempt -> attempt != 2 }
        val checkpoints = mutableListOf<String>()
        val channel = channel(RecordingDirectory(listOf(recipient)), transport)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                channel.sendAllStrict(
                    MessageType.NOTIFICATION,
                    listOf(
                        OutboundItem("one", byteArrayOf(1)),
                        OutboundItem("two", byteArrayOf(2)),
                        OutboundItem("three", byteArrayOf(3)),
                    ),
                    Recipients.OwnMesh,
                    Urgency.NORMAL,
                ) { checkpoints += it.messageId }
            }
        }

        assertEquals(listOf("one", "two"), transport.attempted.map { it.messageId })
        assertEquals(listOf("one"), checkpoints)
    }

    @Test
    fun strictBatch_partialSealFailureStopsBeforeTransport() = runBlocking {
        val good = RecipientKey(ClientId("good"), Hpke.generateKeyPair().publicKeyset)
        val bad = RecipientKey(ClientId("bad"), byteArrayOf(1, 2, 3))
        val transport = RecordingTransport()
        val channel = channel(RecordingDirectory(listOf(good, bad)), transport)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                channel.sendAllStrict(
                    MessageType.NOTIFICATION,
                    listOf(OutboundItem("partial", byteArrayOf(1))),
                    Recipients.OwnMesh,
                    Urgency.NORMAL,
                ) { error("must not checkpoint") }
            }
        }

        assertEquals(emptyList<Envelope>(), transport.attempted)
    }

    @Test
    fun strictBatch_emptyAudienceFailsWithoutCheckpointing() = runBlocking {
        val transport = RecordingTransport()
        val channel = channel(RecordingDirectory(emptyList()), transport)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                channel.sendAllStrict(
                    MessageType.NOTIFICATION,
                    listOf(OutboundItem("no-audience", byteArrayOf(1))),
                    Recipients.OwnMesh,
                    Urgency.NORMAL,
                ) { error("must not checkpoint") }
            }
        }

        assertEquals(emptyList<Envelope>(), transport.attempted)
    }

    @Test
    fun strictBatch_checkpointFailureStopsSuffixAfterAcceptedItem() = runBlocking {
        val recipient = RecipientKey(ClientId("recipient"), Hpke.generateKeyPair().publicKeyset)
        val transport = RecordingTransport()
        val channel = channel(RecordingDirectory(listOf(recipient)), transport)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                channel.sendAllStrict(
                    MessageType.NOTIFICATION,
                    listOf(
                        OutboundItem("accepted", byteArrayOf(1)),
                        OutboundItem("suffix", byteArrayOf(2)),
                    ),
                    Recipients.OwnMesh,
                    Urgency.NORMAL,
                ) { throw IllegalStateException("checkpoint failed") }
            }
        }

        assertEquals(listOf("accepted"), transport.accepted.map { it.messageId })
    }

    private fun channel(
        directory: PeerDirectory,
        transport: Transport,
        onSignerResolution: () -> Unit = {},
    ): SecureChannel {
        val identity = SoftwareIdentitySigner.generate()
        val operational = SoftwareOperationalSigner.generate(identity.clientId, 1)
        return SecureChannel(
            signer = identity,
            operationalSigner = {
                onSignerResolution()
                operational
            },
            myHpkePrivate = { null },
            transport = transport,
            directory = directory,
            log = ChannelLogger { },
            now = { 1_750_000_000_000L },
        )
    }

    private class RecordingDirectory(
        private val resolved: List<RecipientKey>,
    ) : PeerDirectory {
        var recipientResolutions = 0
        var unsealableResolutions = 0

        override fun resolveSender(id: ClientId, signerEpoch: Int): SenderKey? = null

        override fun recipients(scope: Recipients): List<RecipientKey> {
            recipientResolutions++
            return resolved
        }

        override fun unsealableRecipients(scope: Recipients): Set<ClientId> {
            unsealableResolutions++
            return emptySet()
        }
    }

    private class RecordingTransport(
        private val accepts: (attempt: Int) -> Boolean = { true },
    ) : Transport {
        override val type = TransportType.WEBSOCKET
        val attempted = mutableListOf<Envelope>()
        val accepted = mutableListOf<Envelope>()

        override suspend fun send(envelope: Envelope, urgency: Urgency): SendResult {
            attempted += envelope
            val isAccepted = accepts(attempted.size)
            if (isAccepted) accepted += envelope
            return SendResult(accepted = isAccepted)
        }

        override suspend fun publishKeyEpoch(keyEpoch: SignedBlob) = Unit
        override suspend fun publishRoutes(routes: List<SignedBlob>) = Unit
        override suspend fun fetchKeyEpoch(clientId: ClientId, epoch: Int?): SignedBlob? = null
        override suspend fun runLiveDelivery(onEnvelope: (Envelope) -> LiveDeliveryDisposition) = Unit
        override suspend fun uploadPrivateAsset(sourceClientId: ClientId, assetId: String, ciphertext: ByteArray) = false
        override suspend fun fetchPrivateAsset(sourceClientId: ClientId, assetId: String): ByteArray? = null
    }
}
