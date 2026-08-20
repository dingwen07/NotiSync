package net.extrawdw.apps.notisync.data.relay

import net.extrawdw.apps.notisync.data.activity.ActivityEventDraft
import net.extrawdw.apps.notisync.data.activity.ActivityRenderArgsCodec
import net.extrawdw.apps.notisync.data.storage.operational.ActivityAction as StorageActivityAction
import net.extrawdw.apps.notisync.data.storage.operational.ActivityDirection as StorageActivityDirection
import net.extrawdw.apps.notisync.data.storage.operational.ActivityEventEntity
import net.extrawdw.apps.notisync.data.storage.operational.ActivityFeature as StorageActivityFeature
import net.extrawdw.apps.notisync.data.storage.operational.ActivityOutcome as StorageActivityOutcome
import net.extrawdw.apps.notisync.data.storage.operational.MessageDedupEntity
import net.extrawdw.apps.notisync.data.storage.operational.MessageDedupEvidenceKind
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDeliveryMode as StorageDeliveryMode
import net.extrawdw.apps.notisync.data.storage.operational.RelayDao
import net.extrawdw.apps.notisync.data.storage.operational.RelayFinalizeResult as StorageFinalizeResult
import net.extrawdw.apps.notisync.data.storage.operational.RelayHandledResolutionResult as StorageHandledResolution

/**
 * Sole Room adapter for [RelayRepository]. There is no local inbox, retry, claim, or ACK persistence surface.
 * Cancellation and database failures propagate unchanged and can never become false ACK authorization.
 */
internal class RoomRelayRepository(
    private val dao: RelayDao,
) : RelayRepository {
    override suspend fun resolveHandled(
        continuity: RelayOperationalContinuity,
        messageId: String,
        authenticatedToken: AuthenticatedRelayToken,
    ): RelayHandledResolution {
        requireRelayMessageId(messageId, "handled message id")
        return when (
            dao.resolveHandled(
                messageId = messageId,
                authenticatedFingerprint = authenticatedToken.copyBytes(),
                expectedOperationalGeneration = continuity.generation,
                expectedStorageIncarnationId = continuity.storageIncarnationId,
            )
        ) {
            StorageHandledResolution.EXACT_AUTHENTICATED -> RelayHandledResolution.ExactAuthenticated
            StorageHandledResolution.LEGACY_RETAINED_NO_ACK -> RelayHandledResolution.LegacyRetainedNoAck
            StorageHandledResolution.MISSING -> RelayHandledResolution.Missing
            StorageHandledResolution.CONFLICT -> RelayHandledResolution.Conflict
            StorageHandledResolution.STORAGE_CONTINUITY_MISMATCH ->
                RelayHandledResolution.StorageContinuityMismatch
        }
    }

    override suspend fun finalize(
        continuity: RelayOperationalContinuity,
        request: RelayFinalizeRequest,
    ): RelayFinalizeOutcome = when (
        dao.finalizeHandled(
            handled = MessageDedupEntity(
                messageId = request.messageId,
                authenticatedFingerprint = request.authenticatedToken.copyBytes(),
                evidenceKind = MessageDedupEvidenceKind.AUTHENTICATED_FINGERPRINT,
                handledAt = request.handledAt,
            ),
            expectedOperationalGeneration = continuity.generation,
            expectedStorageIncarnationId = continuity.storageIncarnationId,
            activity = request.activity?.toEntity(),
        )
    ) {
        StorageFinalizeResult.APPLIED -> RelayFinalizeOutcome.APPLIED
        StorageFinalizeResult.ALREADY_FINALIZED -> RelayFinalizeOutcome.ALREADY_FINALIZED
        StorageFinalizeResult.LEGACY_RETAINED_NO_ACK -> RelayFinalizeOutcome.LEGACY_RETAINED_NO_ACK
        StorageFinalizeResult.CONFLICT -> RelayFinalizeOutcome.CONFLICT
        StorageFinalizeResult.STORAGE_CONTINUITY_MISMATCH -> RelayFinalizeOutcome.STORAGE_CONTINUITY_MISMATCH
    }

    override suspend fun pruneHandled(now: Long): Int {
        requireRelayTimestamp(now, "handled-message prune time")
        if (now <= RelayLimits.HANDLED_RETENTION_MILLIS) return 0
        val deleted = dao.pruneHandledBefore(now - RelayLimits.HANDLED_RETENTION_MILLIS)
        check(deleted >= 0) { "handled-message prune returned an impossible row count" }
        return deleted
    }
}

private fun ActivityEventDraft.toEntity(): ActivityEventEntity = ActivityEventEntity(
    eventId = eventId,
    occurredAt = occurredAt,
    recordedAt = recordedAt,
    feature = StorageActivityFeature.decode(feature.token),
    semanticAction = StorageActivityAction.decode(semanticAction.token),
    direction = StorageActivityDirection.decode(direction.token),
    outcome = StorageActivityOutcome.decode(outcome.token),
    peerClientId = peerClientId,
    correlationId = correlationId,
    deliveryMode = deliveryMode?.let { StorageDeliveryMode.decode(it.token) },
    renderArgsVersion = renderArgs.version,
    renderArgs = ActivityRenderArgsCodec.encode(renderArgs),
    coalescingKeyToken = coalescingKeyToken?.copyBytes(),
    coalescedCount = coalescedCount,
)
