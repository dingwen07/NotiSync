package net.extrawdw.apps.notisync.messaging.core

import net.extrawdw.apps.notisync.data.activity.ActivityDeliveryMode
import net.extrawdw.apps.notisync.data.corecommand.AuthenticatedCoreCommandDelivery
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandKind
import net.extrawdw.apps.notisync.data.corecommand.OperationalStorageContinuity
import net.extrawdw.apps.notisync.data.relay.RelayHandledDisposition
import net.extrawdw.apps.notisync.messaging.DecodedInboundPayload
import net.extrawdw.apps.notisync.messaging.InboundDeliveryMode
import net.extrawdw.apps.notisync.messaging.PlannedInboundCommand
import net.extrawdw.apps.notisync.messaging.ProtocolLeaf
import net.extrawdw.apps.notisync.messaging.inbound.CoreInboundDispatchPort
import net.extrawdw.apps.notisync.messaging.inbound.InboundOwnerCommitResult
import net.extrawdw.apps.notisync.messaging.inbound.InboundOwnerReceipt
import net.extrawdw.notisync.peer.foundation.FoundationTrustCommand
import net.extrawdw.notisync.peer.foundation.FoundationTrustCommandDecodeResult

/** Direct adapter from the router's single decoded DATA_SYNC value to the Core command processor. */
internal class CoreInboundDispatch(
    private val processor: CoreCommandProcessor,
) : CoreInboundDispatchPort {
    override suspend fun dispatch(
        command: PlannedInboundCommand,
        receipt: InboundOwnerReceipt,
    ): InboundOwnerCommitResult {
        if (
            receipt.messageId != command.delivery.messageId ||
            receipt.continuity.generation <= 0
        ) return InboundOwnerCommitResult.ConflictNoAck

        val data = (command.payload as? DecodedInboundPayload.Data)?.value
            ?: return InboundOwnerCommitResult.SecurityBlocked(
                net.extrawdw.apps.notisync.data.relay.RelayStableCode.of("core_payload_mismatch"),
            )
        val kind = when (command.descriptor.leaf) {
            ProtocolLeaf.Profile -> CoreCommandKind.DATA_SYNC_PROFILE
            ProtocolLeaf.Trust -> CoreCommandKind.DATA_SYNC_TRUST
            ProtocolLeaf.Card -> CoreCommandKind.DATA_SYNC_CARD
            else -> return InboundOwnerCommitResult.SecurityBlocked(
                net.extrawdw.apps.notisync.data.relay.RelayStableCode.of("core_leaf_mismatch"),
            )
        }
        val decoded = when (val result = FoundationTrustCommand.fromDecoded(data)) {
            is FoundationTrustCommandDecodeResult.Ready -> result.command
            FoundationTrustCommandDecodeResult.Malformed -> return InboundOwnerCommitResult.SecurityBlocked(
                net.extrawdw.apps.notisync.data.relay.RelayStableCode.of("core_command_malformed"),
            )
            FoundationTrustCommandDecodeResult.Unsupported -> return InboundOwnerCommitResult.SecurityBlocked(
                net.extrawdw.apps.notisync.data.relay.RelayStableCode.of("core_command_unsupported"),
            )
        }
        val canonical = command.delivery.encodedBody
        val result = try {
            processor.process(
                AuthenticatedCoreCommandDelivery(
                    messageId = command.delivery.messageId,
                    commandId = command.delivery.messageId,
                    authenticatedRequestId = command.delivery.messageId,
                    commandType = kind,
                    senderId = command.delivery.senderId.value,
                    senderOwnDevice = command.delivery.senderOwnDevice,
                    signerEpoch = command.delivery.signerEpoch,
                    signedCreatedAt = command.delivery.signedCreatedAt,
                    deliveryMode = command.delivery.deliveryMode.toActivityMode(),
                    decodedCommand = decoded,
                    canonicalCommand = canonical,
                    authenticatedToken = receipt.authenticatedToken,
                    continuity = OperationalStorageContinuity(
                        generation = receipt.continuity.generation,
                        storageIncarnationId = receipt.continuity.storageIncarnationId,
                    ),
                ),
            )
        } finally {
            canonical.fill(0)
        }
        return when (result) {
            is CoreCommandProcessingResult.AcknowledgementReady ->
                InboundOwnerCommitResult.AcknowledgementReady(
                    when (result.disposition) {
                        RelayHandledDisposition.APPLIED -> RelayHandledDisposition.APPLIED
                        RelayHandledDisposition.SUPERSEDED -> RelayHandledDisposition.SUPERSEDED
                        RelayHandledDisposition.DUPLICATE,
                        RelayHandledDisposition.TERMINAL_REJECTED,
                        -> return InboundOwnerCommitResult.SecurityBlocked(
                            net.extrawdw.apps.notisync.data.relay.RelayStableCode.of("core_outcome_invalid"),
                        )
                    },
                )
            is CoreCommandProcessingResult.RetryRequired ->
                InboundOwnerCommitResult.RetryRequired(result.errorCode)
            is CoreCommandProcessingResult.SecurityBlocked ->
                InboundOwnerCommitResult.SecurityBlocked(result.errorCode)
        }
    }
}

private fun InboundDeliveryMode.toActivityMode(): ActivityDeliveryMode = when (this) {
    InboundDeliveryMode.UNKNOWN -> ActivityDeliveryMode.UNKNOWN
    InboundDeliveryMode.WEBSOCKET -> ActivityDeliveryMode.WEBSOCKET
    InboundDeliveryMode.FCM_INLINE -> ActivityDeliveryMode.FCM_INLINE
    InboundDeliveryMode.FCM_RELAY_FETCH -> ActivityDeliveryMode.FCM_RELAY_FETCH
    InboundDeliveryMode.RELAY_DRAIN -> ActivityDeliveryMode.RELAY_DRAIN
}
