package net.extrawdw.apps.notisync.messaging.inbound

import net.extrawdw.notisync.peer.channel.InboundEnvelopeResult
import net.extrawdw.notisync.peer.channel.RecipientUnavailableReason
import net.extrawdw.notisync.peer.channel.SecureEnvelopeTransport
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.PerRecipientKey

/** The only app inbound-coordinator adapter allowed to expose peer-core secure-envelope result types. */
internal class PeerCoreInboundEnvelopePort(
    private val transport: SecureEnvelopeTransport,
) : InboundEnvelopePort {
    override suspend fun authenticateAndOpen(envelope: DecodedInboundEnvelope): InboundSecureOpenResult =
        when (val result = transport.authenticateAndDecrypt(envelope.toProtocolEnvelope())) {
            is InboundEnvelopeResult.Ready -> {
                val message = result.message
                val body = message.encodedBody
                try {
                    InboundSecureOpenResult.Ready(
                        senderId = message.senderId,
                        senderOwnDevice = message.senderOwnDevice,
                        messageType = message.type,
                        signerEpoch = message.signerEpoch,
                        messageId = message.messageId,
                        signedSequence = message.sequence,
                        signedCreatedAt = message.createdAt,
                        recipientEpoch = message.recipientEpoch,
                        encodedBody = body,
                    )
                } finally {
                    body.fill(0)
                }
            }
            is InboundEnvelopeResult.UnresolvedSender -> InboundSecureOpenResult.UnresolvedSender(
                claimedSenderId = result.claimedSenderId,
                claimedSignerEpoch = result.claimedSignerEpoch,
            )
            is InboundEnvelopeResult.BadSignature -> InboundSecureOpenResult.BadSignature(
                claimedSenderId = result.claimedSenderId,
                claimedSignerEpoch = result.claimedSignerEpoch,
            )
            is InboundEnvelopeResult.RecipientUnavailable -> InboundSecureOpenResult.RecipientUnavailable(
                reason = result.reason.toAppReason(),
                recipientEpoch = result.recipientEpoch,
            )
            is InboundEnvelopeResult.DecryptFailed -> InboundSecureOpenResult.DecryptFailed(
                recipientEpoch = result.recipientEpoch,
            )
        }
}

private fun DecodedInboundEnvelope.toProtocolEnvelope(): Envelope = Envelope(
    v = protocolVersion,
    suite = suite,
    typ = messageType,
    signerId = signerId,
    signerEpoch = signerEpoch,
    messageId = messageId,
    seq = signedSequence,
    createdAt = signedCreatedAt,
    bodyCiphertext = copyBodyCiphertext(),
    recipients = recipients.map { recipient ->
        PerRecipientKey(
            recipientId = recipient.recipientId,
            sealedDek = recipient.copySealedDek(),
            recipientEpoch = recipient.recipientEpoch,
        )
    },
    sig = copySignature(),
)

private fun RecipientUnavailableReason.toAppReason(): InboundRecipientUnavailableReason = when (this) {
    RecipientUnavailableReason.NOT_ADDRESSED_TO_THIS_DEVICE ->
        InboundRecipientUnavailableReason.NOT_ADDRESSED_TO_THIS_DEVICE
    RecipientUnavailableReason.AMBIGUOUS_RECIPIENT -> InboundRecipientUnavailableReason.AMBIGUOUS_RECIPIENT
    RecipientUnavailableReason.MISSING_PRIVATE_KEY -> InboundRecipientUnavailableReason.MISSING_PRIVATE_KEY
}
