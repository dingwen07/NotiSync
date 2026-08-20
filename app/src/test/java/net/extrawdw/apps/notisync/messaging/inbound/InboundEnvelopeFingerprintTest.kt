package net.extrawdw.apps.notisync.messaging.inbound

import net.extrawdw.apps.notisync.data.relay.RelayDeliveryMode
import net.extrawdw.apps.notisync.data.relay.RelayLimits
import net.extrawdw.apps.notisync.data.relay.RelayOperationalContinuity
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.MessageType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundEnvelopeFingerprintTest {
    @Test
    fun envelopeArrivalOpenResultAndRecipientBytesAreDefensivelyCopiedAndRedacted() {
        val encoded = byteArrayOf(1, 2, 3)
        val ciphertext = byteArrayOf(4, 5, 6)
        val sealedDek = byteArrayOf(7, 8, 9)
        val signature = byteArrayOf(10, 11, 12)
        val body = byteArrayOf(13, 14, 15)
        val arrival = InboundEnvelopeArrival(
            messageId = "message-1",
            encodedEnvelope = encoded,
            acceptedAt = 10,
            deliveryMode = RelayDeliveryMode.FCM_RELAY_FETCH,
            continuity = RelayOperationalContinuity(1, "incarnation-1"),
        )
        val recipient = DecodedInboundRecipient(ClientId("recipient"), sealedDek, 2)
        val envelope = envelope(
            bodyCiphertext = ciphertext,
            recipients = listOf(recipient),
            signature = signature,
        )
        val ready = ready(encodedBody = body)

        encoded.fill(0)
        ciphertext.fill(0)
        sealedDek.fill(0)
        signature.fill(0)
        body.fill(0)
        assertArrayEquals(byteArrayOf(1, 2, 3), arrival.copyEncodedEnvelope())
        assertArrayEquals(byteArrayOf(4, 5, 6), envelope.copyBodyCiphertext())
        assertArrayEquals(byteArrayOf(7, 8, 9), recipient.copySealedDek())
        assertArrayEquals(byteArrayOf(10, 11, 12), envelope.copySignature())
        assertArrayEquals(byteArrayOf(13, 14, 15), ready.copyEncodedBody())

        arrival.copyEncodedEnvelope().fill(1)
        envelope.copyBodyCiphertext().fill(1)
        recipient.copySealedDek().fill(1)
        envelope.copySignature().fill(1)
        ready.copyEncodedBody().fill(1)
        assertArrayEquals(byteArrayOf(1, 2, 3), arrival.copyEncodedEnvelope())
        assertArrayEquals(byteArrayOf(4, 5, 6), envelope.copyBodyCiphertext())
        assertArrayEquals(byteArrayOf(7, 8, 9), recipient.copySealedDek())
        assertArrayEquals(byteArrayOf(10, 11, 12), envelope.copySignature())
        assertArrayEquals(byteArrayOf(13, 14, 15), ready.copyEncodedBody())

        val rendered = listOf(arrival, recipient, envelope, ready).joinToString()
        assertFalse(rendered.contains("1, 2, 3"))
        assertFalse(rendered.contains("4, 5, 6"))
        assertFalse(rendered.contains("7, 8, 9"))
        assertFalse(rendered.contains("10, 11, 12"))
        assertFalse(rendered.contains("13, 14, 15"))
    }

    @Test
    fun arrivalRejectsInvalidLocatorTimesAndTransportFrameBounds() {
        listOf("", " ", "line\nbreak", "x".repeat(RelayLimits.MAX_MESSAGE_ID_CHARS + 1)).forEach { id ->
            assertThrows(IllegalArgumentException::class.java) {
                arrival(messageId = id)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { arrival(encoded = byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) {
            arrival(encoded = ByteArray(RelayLimits.MAX_ENVELOPE_BYTES + 1))
        }
        assertThrows(IllegalArgumentException::class.java) { arrival(acceptedAt = 0) }

        assertEquals(RelayLimits.MAX_ENVELOPE_BYTES, arrival(encoded = ByteArray(RelayLimits.MAX_ENVELOPE_BYTES)).copyEncodedEnvelope().size)
    }

    @Test
    fun canonicalV1FingerprintHasPinnedGoldenValue() {
        assertEquals(
            "dfba4b51dc27f90e935d0c5460b9185ff6a19ad3b5084db95875495f899855e2",
            CanonicalInboundEnvelopeFingerprint.derive(envelope()).copyBytes().toHex(),
        )
        assertEquals(1, CanonicalInboundEnvelopeFingerprint.FORMAT_VERSION)
    }

    @Test
    fun everyCurrentEnvelopeFieldAndRecipientOrderAffectFingerprint() {
        val base = token(envelope())
        val variants = listOf(
            envelope(protocolVersion = 2),
            envelope(suite = "NS3"),
            envelope(messageType = MessageType.ACTION),
            envelope(signerId = ClientId("other-signer")),
            envelope(signerEpoch = 8),
            envelope(messageId = "other-message"),
            envelope(signedSequence = 100),
            envelope(signedCreatedAt = 200),
            envelope(bodyCiphertext = byteArrayOf(1, 2, 4)),
            envelope(recipients = listOf(recipient(id = "other-recipient"), recipient(id = "recipient-2", epoch = 3))),
            envelope(recipients = listOf(recipient(sealedDek = byteArrayOf(4, 5, 7)), recipient(id = "recipient-2", epoch = 3))),
            envelope(recipients = listOf(recipient(epoch = 9), recipient(id = "recipient-2", epoch = 3))),
            envelope(recipients = listOf(recipient(id = "recipient-2", epoch = 3), recipient())),
            envelope(recipients = listOf(recipient())),
            envelope(signature = byteArrayOf(7, 8, 10)),
        )

        assertEquals(variants.size, variants.map(::token).toSet().size)
        variants.forEach { assertNotEquals(base, token(it)) }
    }

    @Test
    fun sameSemanticEnvelopeHasStableIdentityAcrossFreshDefensiveInstances() {
        val first = envelope()
        val second = envelope()

        assertEquals(token(first), token(second))
        first.copyBodyCiphertext().fill(0)
        first.copySignature().fill(0)
        first.recipients.forEach { it.copySealedDek().fill(0) }
        assertEquals(token(first), token(second))
    }

    private fun arrival(
        messageId: String = "message-1",
        encoded: ByteArray = byteArrayOf(1),
        acceptedAt: Long = 1,
    ) = InboundEnvelopeArrival(
        messageId,
        encoded,
        acceptedAt,
        RelayDeliveryMode.RELAY_DRAIN,
        RelayOperationalContinuity(1, "incarnation-1"),
    )

    private fun envelope(
        protocolVersion: Int = 1,
        suite: String = "NS2",
        messageType: MessageType = MessageType.NOTIFICATION,
        signerId: ClientId = ClientId("sender"),
        signerEpoch: Int = 7,
        messageId: String = "message-1",
        signedSequence: Long = 99,
        signedCreatedAt: Long = 123_456,
        bodyCiphertext: ByteArray = byteArrayOf(1, 2, 3),
        recipients: List<DecodedInboundRecipient> = listOf(
            recipient(),
            recipient(id = "recipient-2", sealedDek = byteArrayOf(8, 9), epoch = 3),
        ),
        signature: ByteArray = byteArrayOf(7, 8, 9),
    ) = DecodedInboundEnvelope(
        protocolVersion,
        suite,
        messageType,
        signerId,
        signerEpoch,
        messageId,
        signedSequence,
        signedCreatedAt,
        bodyCiphertext,
        recipients,
        signature,
    )

    private fun recipient(
        id: String = "recipient",
        sealedDek: ByteArray = byteArrayOf(4, 5, 6),
        epoch: Int = 2,
    ) = DecodedInboundRecipient(ClientId(id), sealedDek, epoch)

    private fun ready(encodedBody: ByteArray) = InboundSecureOpenResult.Ready(
        senderId = ClientId("sender"),
        senderOwnDevice = true,
        messageType = MessageType.NOTIFICATION,
        signerEpoch = 7,
        messageId = "message-1",
        signedSequence = 99,
        signedCreatedAt = 123_456,
        recipientEpoch = 2,
        encodedBody = encodedBody,
    )

    private fun token(envelope: DecodedInboundEnvelope) =
        CanonicalInboundEnvelopeFingerprint.derive(envelope)

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
