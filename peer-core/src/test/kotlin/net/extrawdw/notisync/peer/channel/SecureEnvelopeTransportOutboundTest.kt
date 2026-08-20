package net.extrawdw.notisync.peer.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.protocol.Capability
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
import net.extrawdw.notisync.protocol.crypto.RecipientKey
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import net.extrawdw.notisync.protocol.crypto.SoftwareOperationalSigner
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SecureEnvelopeTransportOutboundTest {
    @Test
    fun stableCallerIdsAndBodiesUseOneFrozenAudienceAndSignerForTheBatch() = runBlocking {
        val fixture = fixture()
        val firstBody = byteArrayOf(1, 2)
        val first = EncodedOutboundEnvelope("stable-1", firstBody)
        firstBody.fill(9)
        val second = EncodedOutboundEnvelope("stable-2", byteArrayOf(3, 4))

        val result = fixture.boundary.sendBatch(
            MessageType.NOTIFICATION,
            listOf(first, second),
            Recipients.OwnMesh,
            Urgency.NORMAL,
        )

        assertTrue(result is OutboundEnvelopeResult.Accepted)
        assertEquals(listOf("stable-1", "stable-2"), result.acceptedPrefix.map { it.messageId })
        assertEquals(listOf("stable-1", "stable-2"), fixture.transport.attempted.map { it.messageId })
        assertEquals(1, fixture.directory.audienceLookups)
        assertEquals(1, fixture.signerLookups.value)
        assertArrayEquals(
            byteArrayOf(1, 2),
            EnvelopeCrypto.open(fixture.transport.attempted[0], fixture.recipientId, fixture.recipientPrivate),
        )
        assertArrayEquals(
            byteArrayOf(3, 4),
            EnvelopeCrypto.open(fixture.transport.attempted[1], fixture.recipientId, fixture.recipientPrivate),
        )

        // A process-death retry may reseal, but the durable protocol identity stays caller-controlled.
        fixture.boundary.send(
            MessageType.NOTIFICATION,
            EncodedOutboundEnvelope("stable-1", byteArrayOf(1, 2)),
            Recipients.OwnMesh,
            Urgency.NORMAL,
        )
        assertEquals("stable-1", fixture.transport.attempted.last().messageId)
    }

    @Test
    fun strictHighDataPolicyRejectsBeforeAudienceSignerOrTransport() = runBlocking {
        val fixture = fixture()
        val rejected = fixture.boundary.send(
            MessageType.DATA_SYNC,
            EncodedOutboundEnvelope("policy", byteArrayOf(1)),
            Recipients.OwnMesh,
            Urgency.HIGH,
        )

        assertEquals(
            OutboundPolicyRejection.HIGH_DATA_SYNC_POLICY,
            (rejected as OutboundEnvelopeResult.PolicyRejected).reason,
        )
        assertEquals(0, fixture.directory.audienceLookups)
        assertEquals(0, fixture.signerLookups.value)
        assertEquals(0, fixture.transport.attempted.size)

        val accepted = fixture.boundary.send(
            MessageType.DATA_SYNC,
            EncodedOutboundEnvelope("valid-high", byteArrayOf(1)),
            Recipients.OwnMeshFiltered(
                requiredCapabilities = setOf(
                    Capability.DISPLAY,
                    Capability.BACKGROUND_WAKE,
                    Capability.PUSH_FILTERING,
                ),
                requireCapabilityRoutingV1 = true,
            ),
            Urgency.HIGH,
        )
        assertTrue(accepted is OutboundEnvelopeResult.Accepted)
    }

    @Test
    fun invalidStableIdsAreTypedPolicyFailures() = runBlocking {
        val fixture = fixture()
        val blank = fixture.boundary.send(
            MessageType.NOTIFICATION,
            EncodedOutboundEnvelope(" ", byteArrayOf(1)),
            Recipients.OwnMesh,
            Urgency.NORMAL,
        )
        val duplicate = fixture.boundary.sendBatch(
            MessageType.NOTIFICATION,
            listOf(
                EncodedOutboundEnvelope("same", byteArrayOf(1)),
                EncodedOutboundEnvelope("same", byteArrayOf(2)),
            ),
            Recipients.OwnMesh,
            Urgency.NORMAL,
        )

        assertEquals(
            OutboundPolicyRejection.BLANK_MESSAGE_ID,
            (blank as OutboundEnvelopeResult.PolicyRejected).reason,
        )
        assertEquals(
            OutboundPolicyRejection.DUPLICATE_MESSAGE_ID,
            (duplicate as OutboundEnvelopeResult.PolicyRejected).reason,
        )
        assertEquals(0, fixture.transport.attempted.size)
    }

    @Test
    fun missingAndPartiallySealableRecipientsNeverCausePartialTransport() = runBlocking {
        val fixture = fixture()
        val missingId = ClientId("missing-key")
        fixture.directory.unsealable = setOf(missingId)
        val missing = fixture.boundary.send(
            MessageType.NOTIFICATION,
            EncodedOutboundEnvelope("missing", byteArrayOf(1)),
            Recipients.OwnMesh,
            Urgency.NORMAL,
        )
        assertEquals(
            OutboundUnsealableReason.MISSING_RECIPIENT_KEYS,
            (missing as OutboundEnvelopeResult.Unsealable).reason,
        )
        assertEquals(0, fixture.transport.attempted.size)

        fixture.directory.unsealable = emptySet()
        fixture.directory.resolved = fixture.directory.resolved +
            RecipientKey(ClientId("corrupt-key"), byteArrayOf(1, 2, 3), 2)
        val partial = fixture.boundary.send(
            MessageType.NOTIFICATION,
            EncodedOutboundEnvelope("partial", byteArrayOf(2)),
            Recipients.OwnMesh,
            Urgency.NORMAL,
        )
        assertEquals(
            OutboundUnsealableReason.PARTIAL_RECIPIENT_SEAL,
            (partial as OutboundEnvelopeResult.Unsealable).reason,
        )
        assertEquals(listOf(fixture.recipientId), partial.sealedRecipientIds)
        assertEquals(0, fixture.transport.attempted.size)
    }

    @Test
    fun transportRejectionReturnsAcceptedPrefixAndStopsSuffix() = runBlocking {
        val fixture = fixture(accepts = { attempt -> attempt != 2 })
        val result = fixture.boundary.sendBatch(
            MessageType.NOTIFICATION,
            listOf(
                EncodedOutboundEnvelope("one", byteArrayOf(1)),
                EncodedOutboundEnvelope("two", byteArrayOf(2)),
                EncodedOutboundEnvelope("three", byteArrayOf(3)),
            ),
            Recipients.OwnMesh,
            Urgency.NORMAL,
        )

        assertTrue(result is OutboundEnvelopeResult.TransportRejected)
        result as OutboundEnvelopeResult.TransportRejected
        assertEquals(TransportRejectionReason.REJECTED, result.reason)
        assertEquals("two", result.failedMessageId)
        assertEquals(listOf("one"), result.acceptedPrefix.map { it.messageId })
        assertEquals(listOf("one", "two"), fixture.transport.attempted.map { it.messageId })
    }

    @Test
    fun transportFailureIsTypedButCancellationPropagates() = runBlocking {
        val failed = fixture(transportFailure = IllegalStateException("private provider detail"))
        val failureResult = failed.boundary.send(
            MessageType.NOTIFICATION,
            EncodedOutboundEnvelope("failure", byteArrayOf(1)),
            Recipients.OwnMesh,
            Urgency.NORMAL,
        )
        assertEquals(
            TransportRejectionReason.FAILURE,
            (failureResult as OutboundEnvelopeResult.TransportRejected).reason,
        )
        assertEquals(null, failureResult.transportReport)

        val cancelled = fixture(transportFailure = CancellationException("cancelled"))
        try {
            cancelled.boundary.send(
                MessageType.NOTIFICATION,
                EncodedOutboundEnvelope("cancelled", byteArrayOf(1)),
                Recipients.OwnMesh,
                Urgency.NORMAL,
            )
            fail("transport cancellation must propagate")
        } catch (_: CancellationException) {
            // expected
        }
    }

    private data class Fixture(
        val boundary: SecureEnvelopeTransport,
        val directory: RecordingDirectory,
        val transport: RecordingTransport,
        val recipientId: ClientId,
        val recipientPrivate: ByteArray,
        val signerLookups: IntAccessor,
    )

    private fun fixture(
        accepts: (Int) -> Boolean = { true },
        transportFailure: Exception? = null,
    ): Fixture {
        val identity = SoftwareIdentitySigner.generate()
        val operational = SoftwareOperationalSigner.generate(identity.clientId, 4)
        val hpke = Hpke.generateKeyPair()
        val recipientId = ClientId("recipient")
        val directory = RecordingDirectory(
            mutableListOf(RecipientKey(recipientId, hpke.publicKeyset, 2)),
        )
        val transport = RecordingTransport(accepts, transportFailure)
        var signerLookups = 0
        val boundary = SecureEnvelopeTransport(
            identitySigner = identity,
            operationalSigner = {
                signerLookups++
                operational
            },
            myHpkePrivate = { null },
            transport = transport,
            directory = directory,
            now = { 1_000 },
        )
        return Fixture(
            boundary,
            directory,
            transport,
            recipientId,
            hpke.privateKeyset,
            IntAccessor { signerLookups },
        )
    }

    private fun interface IntAccessor {
        fun value(): Int
    }

    private val IntAccessor.value: Int
        get() = value()

    private class RecordingDirectory(
        var resolved: List<RecipientKey>,
    ) : PeerDirectory {
        var unsealable: Set<ClientId> = emptySet()
        var audienceLookups = 0

        override fun resolveSender(id: ClientId, signerEpoch: Int): SenderKey? = null

        override fun resolveAudience(scope: Recipients): AudienceSnapshot {
            audienceLookups++
            return AudienceSnapshot(resolved, unsealable)
        }
    }

    private class RecordingTransport(
        private val accepts: (Int) -> Boolean,
        private val failure: Exception?,
    ) : Transport {
        override val type = TransportType.WEBSOCKET
        val attempted = mutableListOf<Envelope>()

        override suspend fun send(envelope: Envelope, urgency: Urgency): SendResult {
            attempted += envelope
            failure?.let { throw it }
            return SendResult(accepted = accepts(attempted.size), delivered = envelope.recipientIds())
        }

        override suspend fun publishKeyEpoch(keyEpoch: SignedBlob) = Unit
        override suspend fun publishRoutes(routes: List<SignedBlob>) = Unit
        override suspend fun fetchKeyEpoch(clientId: ClientId, epoch: Int?): SignedBlob? = null
        override suspend fun runLiveDelivery(onEnvelope: (Envelope) -> LiveDeliveryDisposition) = Unit
        override suspend fun uploadPrivateAsset(sourceClientId: ClientId, assetId: String, ciphertext: ByteArray) = false
        override suspend fun fetchPrivateAsset(sourceClientId: ClientId, assetId: String): ByteArray? = null
    }
}
