package net.extrawdw.notisync.peer.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.LiveDeliveryDisposition
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.SendResult
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.Transport
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.crypto.EnvelopeCrypto
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.HpkeKeyPair
import net.extrawdw.notisync.protocol.crypto.RecipientKey
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import net.extrawdw.notisync.protocol.crypto.SoftwareOperationalSigner
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SecureEnvelopeTransportInboundTest {
    @Test
    fun readyUsesOneSenderAndKeyLookupAndDefensivelySnapshotsMutableBytes() = runBlocking {
        val fixture = fixture()
        val body = byteArrayOf(7, 8, 9)
        val envelope = fixture.envelope(body)
        var senderLookups = 0
        var keyLookups = 0
        val boundary = fixture.boundary(
            directory = directory { _, _ ->
                senderLookups++
                // Mutating the transport-owned object during lookup must not change the verified snapshot.
                envelope.bodyCiphertext.fill(0)
                envelope.sig.fill(0)
                envelope.recipients.single().sealedDek.fill(0)
                SenderKey(fixture.sender.operationalPublicKeySpki, ownDevice = true)
            },
            privateKey = {
                keyLookups++
                fixture.recipientHpke.privateKeyset
            },
        )

        val result = boundary.authenticateAndDecrypt(envelope)

        assertTrue(result is InboundEnvelopeResult.Ready)
        val message = (result as InboundEnvelopeResult.Ready).message
        assertEquals(fixture.sender.clientId, message.senderId)
        assertEquals(3, message.signerEpoch)
        assertEquals(11, message.recipientEpoch)
        assertEquals("stable-inbound", message.messageId)
        assertArrayEquals(body, message.encodedBody)
        message.encodedBody.fill(42)
        assertArrayEquals(body, message.encodedBody)
        assertEquals(1, senderLookups)
        assertEquals(1, keyLookups)
    }

    @Test
    fun unresolvedSenderIsTypedAndStopsBeforeKeyLookup() = runBlocking {
        val fixture = fixture()
        var senderLookups = 0
        var keyLookups = 0
        val result = fixture.boundary(
            directory = directory { _, _ ->
                senderLookups++
                null
            },
            privateKey = {
                keyLookups++
                fixture.recipientHpke.privateKeyset
            },
        ).authenticateAndDecrypt(fixture.envelope(byteArrayOf(1)))

        assertTrue(result is InboundEnvelopeResult.UnresolvedSender)
        assertEquals(1, senderLookups)
        assertEquals(0, keyLookups)
    }

    @Test
    fun signatureAndCiphertextTamperAreBadSignatureBeforeKeyLookup() = runBlocking {
        val fixture = fixture()
        val original = fixture.envelope(byteArrayOf(1, 2, 3))
        val variants = listOf(
            original.copy(sig = original.sig.copyOf().also { it[it.lastIndex] = (it.last() xor 1) }),
            original.copy(
                bodyCiphertext = original.bodyCiphertext.copyOf().also {
                    it[it.lastIndex] = (it.last() xor 1)
                },
            ),
        )
        var keyLookups = 0
        val boundary = fixture.boundary(
            directory = directory { _, _ -> SenderKey(fixture.sender.operationalPublicKeySpki, false) },
            privateKey = {
                keyLookups++
                fixture.recipientHpke.privateKeyset
            },
        )

        variants.forEach { envelope ->
            assertTrue(boundary.authenticateAndDecrypt(envelope) is InboundEnvelopeResult.BadSignature)
        }
        assertEquals(0, keyLookups)
    }

    @Test
    fun recipientAndMissingKeyOutcomesAreDistinct() = runBlocking {
        val fixture = fixture()
        val other = Hpke.generateKeyPair()
        val notForMe = EnvelopeCrypto.seal(
            fixture.sender,
            MessageType.NOTIFICATION,
            byteArrayOf(1),
            listOf(RecipientKey(ClientId("other-recipient"), other.publicKeyset, 4)),
            "not-for-me",
            1,
            2,
        )
        val boundary = fixture.boundary(
            directory = directory { _, _ -> SenderKey(fixture.sender.operationalPublicKeySpki, true) },
            privateKey = { null },
        )

        val notAddressed = boundary.authenticateAndDecrypt(notForMe)
        val missingKey = boundary.authenticateAndDecrypt(fixture.envelope(byteArrayOf(2)))

        assertEquals(
            RecipientUnavailableReason.NOT_ADDRESSED_TO_THIS_DEVICE,
            (notAddressed as InboundEnvelopeResult.RecipientUnavailable).reason,
        )
        assertEquals(
            RecipientUnavailableReason.MISSING_PRIVATE_KEY,
            (missingKey as InboundEnvelopeResult.RecipientUnavailable).reason,
        )
        assertEquals(11, missingKey.recipientEpoch)
    }

    @Test
    fun validSignatureWithWrongPrivateKeyIsDecryptFailure() = runBlocking {
        val fixture = fixture()
        val wrong = Hpke.generateKeyPair()
        val result = fixture.boundary(
            directory = directory { _, _ -> SenderKey(fixture.sender.operationalPublicKeySpki, true) },
            privateKey = { wrong.privateKeyset },
        ).authenticateAndDecrypt(fixture.envelope(byteArrayOf(1)))

        assertEquals(InboundEnvelopeResult.DecryptFailed(11), result)
    }

    @Test
    fun cancellationAfterSenderLookupPropagates() = runBlocking {
        supervisorScope {
            val fixture = fixture()
            lateinit var deliveryJob: Job
            val boundary = fixture.boundary(
                directory = directory { _, _ ->
                    deliveryJob.cancel(CancellationException("test cancellation"))
                    SenderKey(fixture.sender.operationalPublicKeySpki, true)
                },
                privateKey = { fixture.recipientHpke.privateKeyset },
            )
            val delivery = async(start = CoroutineStart.LAZY) {
                boundary.authenticateAndDecrypt(fixture.envelope(byteArrayOf(1)))
            }
            deliveryJob = delivery
            delivery.start()

            try {
                delivery.await()
                fail("cancelled authentication must not produce a typed success/failure result")
            } catch (_: CancellationException) {
                // expected
            }
        }
    }

    private data class Fixture(
        val recipient: SoftwareIdentitySigner,
        val recipientOperational: SoftwareOperationalSigner,
        val recipientHpke: HpkeKeyPair,
        val sender: SoftwareOperationalSigner,
    ) {
        fun envelope(body: ByteArray): Envelope = EnvelopeCrypto.seal(
            sender,
            MessageType.NOTIFICATION,
            body,
            listOf(RecipientKey(recipient.clientId, recipientHpke.publicKeyset, 11)),
            "stable-inbound",
            5,
            10,
        )

        fun boundary(
            directory: PeerDirectory,
            privateKey: (Int) -> ByteArray?,
        ) = SecureEnvelopeTransport(
            identitySigner = recipient,
            operationalSigner = { recipientOperational },
            myHpkePrivate = privateKey,
            transport = NoopTransport,
            directory = directory,
            now = { 100 },
        )
    }

    private fun fixture(): Fixture {
        val recipient = SoftwareIdentitySigner.generate()
        return Fixture(
            recipient = recipient,
            recipientOperational = SoftwareOperationalSigner.generate(recipient.clientId, 1),
            recipientHpke = Hpke.generateKeyPair(),
            sender = SoftwareOperationalSigner.generate(SoftwareIdentitySigner.generate().clientId, 3),
        )
    }

    private fun directory(resolve: (ClientId, Int) -> SenderKey?): PeerDirectory = object : PeerDirectory {
        override fun resolveSender(id: ClientId, signerEpoch: Int): SenderKey? = resolve(id, signerEpoch)
        override fun resolveAudience(scope: Recipients) = AudienceSnapshot(emptyList())
    }

    private object NoopTransport : Transport {
        override val type = TransportType.WEBSOCKET
        override suspend fun publishKeyEpoch(keyEpoch: SignedBlob) = Unit
        override suspend fun publishRoutes(routes: List<SignedBlob>) = Unit
        override suspend fun fetchKeyEpoch(clientId: ClientId, epoch: Int?): SignedBlob? = null
        override suspend fun send(envelope: Envelope, urgency: Urgency) = SendResult(false)
        override suspend fun runLiveDelivery(onEnvelope: (Envelope) -> LiveDeliveryDisposition) = Unit
        override suspend fun uploadPrivateAsset(sourceClientId: ClientId, assetId: String, ciphertext: ByteArray) = false
        override suspend fun fetchPrivateAsset(sourceClientId: ClientId, assetId: String): ByteArray? = null
    }

    private infix fun Byte.xor(other: Int): Byte = (toInt() xor other).toByte()
}
