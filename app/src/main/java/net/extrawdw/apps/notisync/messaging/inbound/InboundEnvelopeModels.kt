package net.extrawdw.apps.notisync.messaging.inbound

import kotlinx.serialization.SerializationException
import net.extrawdw.apps.notisync.data.relay.RelayDeliveryMode
import net.extrawdw.apps.notisync.data.relay.RelayLimits
import net.extrawdw.apps.notisync.data.relay.RelayOperationalContinuity
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec

/**
 * One broker delivery offered to the durable inbound boundary.
 *
 * [acceptedAt] is broker-authored and can legitimately be ahead of or behind the receiver clock. The coordinator
 * assigns the local `receivedAt` with its clock when staging; it deliberately does not compare the two clocks.
 */
internal class InboundEnvelopeArrival(
    val messageId: String,
    encodedEnvelope: ByteArray,
    val acceptedAt: Long,
    val deliveryMode: RelayDeliveryMode,
    val continuity: RelayOperationalContinuity,
) {
    private val encodedEnvelopeSnapshot = encodedEnvelope.copyOf()

    init {
        require(messageId.isNotBlank()) { "relay locator message id must not be blank" }
        require(messageId.length <= RelayLimits.MAX_MESSAGE_ID_CHARS) { "relay locator message id is too long" }
        require(messageId.none(Char::isISOControl)) { "relay locator message id contains control characters" }
        require(encodedEnvelopeSnapshot.size in 1..RelayLimits.MAX_ENVELOPE_BYTES) {
            "relay envelope must be within the authenticated transport frame bound"
        }
        require(acceptedAt > 0) { "relay accepted time must be positive" }
    }

    fun copyEncodedEnvelope(): ByteArray = encodedEnvelopeSnapshot.copyOf()

    override fun toString(): String =
        "InboundEnvelopeArrival(messageId=$messageId, envelope=${encodedEnvelopeSnapshot.size} bytes, " +
            "acceptedAt=$acceptedAt, deliveryMode=$deliveryMode, generation=${continuity.generation})"
}

/** One recipient projection from the signed envelope. Mutable crypto bytes never escape by reference. */
internal class DecodedInboundRecipient(
    val recipientId: ClientId,
    sealedDek: ByteArray,
    val recipientEpoch: Int,
) {
    private val sealedDekSnapshot = sealedDek.copyOf()

    fun copySealedDek(): ByteArray = sealedDekSnapshot.copyOf()

    override fun toString(): String =
        "DecodedInboundRecipient(recipientId=$recipientId, sealedDek=${sealedDekSnapshot.size} bytes, " +
            "recipientEpoch=$recipientEpoch)"
}

/**
 * App-local, defensively copied representation of every current [Envelope] field.
 *
 * This is still unauthenticated until [InboundEnvelopePort] succeeds. Keeping it app-local lets staging,
 * fingerprinting, and conflict checks avoid embedding peer-core transport result types.
 */
internal class DecodedInboundEnvelope(
    val protocolVersion: Int,
    val suite: String,
    val messageType: MessageType,
    val signerId: ClientId,
    val signerEpoch: Int,
    val messageId: String,
    val signedSequence: Long,
    val signedCreatedAt: Long,
    bodyCiphertext: ByteArray,
    recipients: List<DecodedInboundRecipient>,
    signature: ByteArray,
) {
    private val bodyCiphertextSnapshot = bodyCiphertext.copyOf()
    private val recipientSnapshot = recipients.toList()
    private val signatureSnapshot = signature.copyOf()

    val recipients: List<DecodedInboundRecipient>
        get() = recipientSnapshot.toList()

    fun copyBodyCiphertext(): ByteArray = bodyCiphertextSnapshot.copyOf()

    fun copySignature(): ByteArray = signatureSnapshot.copyOf()

    override fun toString(): String =
        "DecodedInboundEnvelope(v=$protocolVersion, suite=$suite, type=$messageType, signerId=$signerId, " +
            "signerEpoch=$signerEpoch, messageId=$messageId, signedSequence=$signedSequence, " +
            "signedCreatedAt=$signedCreatedAt, ciphertext=${bodyCiphertextSnapshot.size} bytes, " +
            "recipients=${recipientSnapshot.size}, signature=${signatureSnapshot.size} bytes)"
}

/** A pure envelope parser. Expected wire-shape failures are data, while unexpected failures propagate. */
internal fun interface InboundEnvelopeCodec {
    fun decode(encodedEnvelope: ByteArray): DecodedInboundEnvelope?
}

internal object ProtocolInboundEnvelopeCodec : InboundEnvelopeCodec {
    override fun decode(encodedEnvelope: ByteArray): DecodedInboundEnvelope? {
        if (encodedEnvelope.size !in 1..RelayLimits.MAX_ENVELOPE_BYTES) return null
        val envelope = try {
            ProtocolCodec.decodeFromCbor<Envelope>(encodedEnvelope.copyOf())
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return DecodedInboundEnvelope(
            protocolVersion = envelope.v,
            suite = envelope.suite,
            messageType = envelope.typ,
            signerId = envelope.signerId,
            signerEpoch = envelope.signerEpoch,
            messageId = envelope.messageId,
            signedSequence = envelope.seq,
            signedCreatedAt = envelope.createdAt,
            bodyCiphertext = envelope.bodyCiphertext,
            recipients = envelope.recipients.map { recipient ->
                DecodedInboundRecipient(
                    recipientId = recipient.recipientId,
                    sealedDek = recipient.sealedDek,
                    recipientEpoch = recipient.recipientEpoch,
                )
            },
            signature = envelope.sig,
        )
    }
}

/** Result of peer authentication/open after all peer-core types have been mapped away. */
internal sealed interface InboundSecureOpenResult {
    class Ready(
        val senderId: ClientId,
        val senderOwnDevice: Boolean,
        val messageType: MessageType,
        val signerEpoch: Int,
        val messageId: String,
        val signedSequence: Long,
        val signedCreatedAt: Long,
        val recipientEpoch: Int,
        encodedBody: ByteArray,
    ) : InboundSecureOpenResult {
        private val encodedBodySnapshot = encodedBody.copyOf()

        fun copyEncodedBody(): ByteArray = encodedBodySnapshot.copyOf()

        override fun toString(): String =
            "Ready(senderId=$senderId, senderOwnDevice=$senderOwnDevice, messageType=$messageType, " +
                "signerEpoch=$signerEpoch, messageId=$messageId, signedSequence=$signedSequence, " +
                "signedCreatedAt=$signedCreatedAt, recipientEpoch=$recipientEpoch, " +
                "encodedBody=${encodedBodySnapshot.size} bytes)"
    }

    data class UnresolvedSender(
        val claimedSenderId: ClientId,
        val claimedSignerEpoch: Int,
    ) : InboundSecureOpenResult

    data class BadSignature(
        val claimedSenderId: ClientId,
        val claimedSignerEpoch: Int,
    ) : InboundSecureOpenResult

    data class RecipientUnavailable(
        val reason: InboundRecipientUnavailableReason,
        val recipientEpoch: Int? = null,
    ) : InboundSecureOpenResult

    data class DecryptFailed(val recipientEpoch: Int) : InboundSecureOpenResult
}

internal enum class InboundRecipientUnavailableReason(val token: String) {
    NOT_ADDRESSED_TO_THIS_DEVICE("not_addressed_to_this_device"),
    AMBIGUOUS_RECIPIENT("ambiguous_recipient"),
    MISSING_PRIVATE_KEY("missing_private_key"),
}

/** The coordinator invokes this once per processing attempt. Implementations must not own storage or ACKs. */
internal fun interface InboundEnvelopePort {
    suspend fun authenticateAndOpen(envelope: DecodedInboundEnvelope): InboundSecureOpenResult
}
