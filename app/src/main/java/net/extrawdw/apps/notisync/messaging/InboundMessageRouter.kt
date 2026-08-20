package net.extrawdw.apps.notisync.messaging

import net.extrawdw.notisync.protocol.ActionEvent
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.DismissEvent
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec

/** The single decoded payload instance handed from relay planning to its owning feature. */
internal sealed interface DecodedInboundPayload {
    data class Notification(val value: CapturedNotification) : DecodedInboundPayload
    data class Dismissal(val value: DismissEvent) : DecodedInboundPayload
    data class Action(val value: ActionEvent) : DecodedInboundPayload
    data class Data(val value: DataSync) : DecodedInboundPayload
}

/**
 * An authenticated delivery after its body has been decoded, structurally validated, and classified once.
 * Relay coalescing and the owning handler must use this object instead of decoding [delivery.encodedBody] again.
 */
internal data class PlannedInboundCommand(
    val delivery: AuthenticatedInboundDelivery,
    val descriptor: ProtocolMessageDescriptor,
    val payload: DecodedInboundPayload,
)

internal sealed interface InboundPlanningResult {
    data class Planned(val command: PlannedInboundCommand) : InboundPlanningResult

    data class TerminalRejected(val reasonCode: String) : InboundPlanningResult {
        init {
            requireStableReasonCode(reasonCode)
        }
    }

    data class SecurityBlocked(val reasonCode: String) : InboundPlanningResult {
        init {
            requireStableReasonCode(reasonCode)
        }
    }
}

/** One successful result of the single protocol decode and structural-classification pass. */
internal data class DecodedInboundMessage(
    val leaf: ProtocolLeaf,
    val payload: DecodedInboundPayload,
)

/** Injectable only so tests can prove the router invokes one decode boundary exactly once. */
internal fun interface InboundPayloadDecoder {
    fun decode(messageType: MessageType, encodedBody: ByteArray): DecodedInboundMessage?
}

/**
 * Immutable pure application planner. Owner coverage and commit behavior live in the closed Core/Operational
 * dispatch adapters; keeping a second mutable handler registry here would create two competing ownership catalogs.
 */
internal class InboundMessageRouter(
    private val decoder: InboundPayloadDecoder = PROTOCOL_PAYLOAD_DECODER,
) {
    fun plan(delivery: AuthenticatedInboundDelivery): InboundPlanningResult {
        validateAuthenticatedMetadata(delivery)?.let { return it }

        val decoded = decoder.decode(delivery.messageType, delivery.encodedBody)
            ?: return InboundPlanningResult.TerminalRejected(
                "malformed_${delivery.messageType.reasonToken()}",
            )
        val descriptor = decoded.leaf.routingDescriptor()

        if (descriptor.messageType != delivery.messageType) {
            return InboundPlanningResult.SecurityBlocked("catalog_message_type_mismatch")
        }
        if (descriptor.senderPolicy == SenderPolicy.TRUSTED_OWN_DEVICE && !delivery.senderOwnDevice) {
            return InboundPlanningResult.SecurityBlocked("own_device_required")
        }
        when (descriptor.signerPolicy) {
            SignerPolicy.ANY_VERIFIED -> Unit
            SignerPolicy.OPERATIONAL -> if (delivery.signerEpoch <= 0) {
                return InboundPlanningResult.SecurityBlocked("operational_signer_required")
            }
            SignerPolicy.IDENTITY -> if (delivery.signerEpoch != 0) {
                return InboundPlanningResult.SecurityBlocked("identity_signer_required")
            }
        }

        return InboundPlanningResult.Planned(
            PlannedInboundCommand(delivery, descriptor, decoded.payload),
        )
    }

    private fun validateAuthenticatedMetadata(
        delivery: AuthenticatedInboundDelivery,
    ): InboundPlanningResult? {
        if (delivery.messageId.isBlank()) {
            return InboundPlanningResult.TerminalRejected("missing_authenticated_message_id")
        }
        if (delivery.messageId.length > MAX_AUTHENTICATED_MESSAGE_ID_CHARS) {
            return InboundPlanningResult.TerminalRejected("invalid_authenticated_message_id")
        }
        if (
            delivery.senderId.value.isBlank() ||
            delivery.senderId.value.length > MAX_AUTHENTICATED_CLIENT_ID_CHARS
        ) {
            return InboundPlanningResult.SecurityBlocked("invalid_authenticated_sender_id")
        }
        if (delivery.signerEpoch < 0) {
            return InboundPlanningResult.SecurityBlocked("invalid_authenticated_signer_epoch")
        }
        if (delivery.recipientEpoch < 0) {
            return InboundPlanningResult.SecurityBlocked("invalid_authenticated_recipient_epoch")
        }
        if (delivery.signedSequence < 0) {
            return InboundPlanningResult.TerminalRejected("invalid_signed_sequence")
        }
        if (delivery.signedCreatedAt <= 0) {
            return InboundPlanningResult.TerminalRejected("invalid_signed_created_at")
        }
        return null
    }

}

private val PROTOCOL_PAYLOAD_DECODER = InboundPayloadDecoder { messageType, encodedBody ->
    try {
        when (messageType) {
            MessageType.NOTIFICATION -> DecodedInboundMessage(
                ProtocolLeaf.Notification,
                DecodedInboundPayload.Notification(ProtocolCodec.decodeFromCbor(encodedBody)),
            )
            MessageType.DISMISSAL -> DecodedInboundMessage(
                ProtocolLeaf.Dismissal,
                DecodedInboundPayload.Dismissal(ProtocolCodec.decodeFromCbor(encodedBody)),
            )
            MessageType.ACTION -> {
                val action = ProtocolCodec.decodeFromCbor<ActionEvent>(encodedBody)
                DecodedInboundMessage(
                    ProtocolLeaf.Action(action.kind),
                    DecodedInboundPayload.Action(action),
                )
            }
            MessageType.DATA_SYNC -> decodeDataSync(ProtocolCodec.decodeFromCbor(encodedBody))
        }
    } catch (_: kotlinx.serialization.SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun decodeDataSync(data: DataSync): DecodedInboundMessage? {
    if (data.validateOneMatchingBody() != null) return null
    val leaf = when (data.kind) {
        DataSyncKind.ASSET -> ProtocolLeaf.Asset(requireNotNull(data.asset).kind)
        DataSyncKind.PROFILE -> ProtocolLeaf.Profile
        DataSyncKind.TRUST -> ProtocolLeaf.Trust
        DataSyncKind.CARD -> ProtocolLeaf.Card
        DataSyncKind.FILTER -> ProtocolLeaf.Filter
        DataSyncKind.NOTIFICATION -> ProtocolLeaf.QuietNotification
        DataSyncKind.RUN -> ProtocolLeaf.Run(requireNotNull(data.run).kind)
        DataSyncKind.SCREEN_MIRRORING -> ProtocolLeaf.Screen(requireNotNull(data.screenMirror).action)
        DataSyncKind.OPENPGP_SIGN -> ProtocolLeaf.Seal(requireNotNull(data.openPgpSign).action)
        DataSyncKind.SSH_AGENT -> ProtocolLeaf.Ssh(requireNotNull(data.sshAgent).kind)
    }
    return DecodedInboundMessage(leaf, DecodedInboundPayload.Data(data))
}

private fun MessageType.reasonToken(): String = when (this) {
    MessageType.NOTIFICATION -> "notification"
    MessageType.DISMISSAL -> "dismissal"
    MessageType.DATA_SYNC -> "data_sync"
    MessageType.ACTION -> "action"
}

private fun requireStableReasonCode(reasonCode: String): String = reasonCode.also {
    require(STABLE_REASON_CODE.matches(it)) { "reasonCode must be a stable bounded token" }
}

private val STABLE_REASON_CODE = Regex("[a-z][a-z0-9_.-]{0,127}")

/** The broker's durable message/client-id columns are both bounded to 64 characters. */
internal const val MAX_AUTHENTICATED_MESSAGE_ID_CHARS = 64
internal const val MAX_AUTHENTICATED_CLIENT_ID_CHARS = 64
