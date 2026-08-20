package net.extrawdw.apps.notisync.data.corecommand

import android.content.Context
import net.extrawdw.apps.notisync.data.activity.ActivityRenderArgsCodec
import net.extrawdw.apps.notisync.data.relay.RelayFinalizeRequest
import net.extrawdw.apps.notisync.data.storage.operational.ActivityAction as StorageActivityAction
import net.extrawdw.apps.notisync.data.storage.operational.ActivityDirection as StorageActivityDirection
import net.extrawdw.apps.notisync.data.storage.operational.ActivityEventEntity
import net.extrawdw.apps.notisync.data.storage.operational.ActivityFeature as StorageActivityFeature
import net.extrawdw.apps.notisync.data.storage.operational.ActivityOutcome as StorageActivityOutcome
import net.extrawdw.apps.notisync.data.storage.operational.MessageDedupEntity
import net.extrawdw.apps.notisync.data.storage.operational.MessageDedupEvidenceKind
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabaseFactory
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDeliveryMode as StorageDeliveryMode
import net.extrawdw.apps.notisync.data.storage.operational.RelayFinalizeResult as StorageFinalizeResult

/** Sole Room adapter for direct Core receipt finalization; no persistence type crosses this file. */
internal class RoomCoreCommandReceiptFinalizer private constructor(
    private val database: OperationalDatabase,
) : CoreCommandReceiptFinalizer {
    override suspend fun finalize(
        continuity: OperationalStorageContinuity,
        request: RelayFinalizeRequest,
    ): CoreCommandReceiptFinalizeOutcome {
        val handled = MessageDedupEntity(
            messageId = request.messageId,
            authenticatedFingerprint = request.authenticatedToken.copyBytes(),
            evidenceKind = MessageDedupEvidenceKind.AUTHENTICATED_FINGERPRINT,
            handledAt = request.handledAt,
        )
        return when (
            database.relayDao().finalizeHandled(
                handled = handled,
                expectedOperationalGeneration = continuity.generation,
                expectedStorageIncarnationId = continuity.storageIncarnationId,
                activity = request.activity?.toEntity(),
            )
        ) {
            StorageFinalizeResult.APPLIED -> CoreCommandReceiptFinalizeOutcome.APPLIED
            StorageFinalizeResult.ALREADY_FINALIZED -> CoreCommandReceiptFinalizeOutcome.ALREADY_FINALIZED
            StorageFinalizeResult.LEGACY_RETAINED_NO_ACK ->
                CoreCommandReceiptFinalizeOutcome.LEGACY_RETAINED_NO_ACK
            StorageFinalizeResult.CONFLICT -> CoreCommandReceiptFinalizeOutcome.CONFLICT
            StorageFinalizeResult.STORAGE_CONTINUITY_MISMATCH ->
                CoreCommandReceiptFinalizeOutcome.STORAGE_CONTINUITY_MISMATCH
        }
    }

    companion object {
        fun create(context: Context): RoomCoreCommandReceiptFinalizer =
            RoomCoreCommandReceiptFinalizer(OperationalDatabaseFactory.get(context.applicationContext))

        fun forDatabase(database: OperationalDatabase): RoomCoreCommandReceiptFinalizer =
            RoomCoreCommandReceiptFinalizer(database)
    }
}

private fun net.extrawdw.apps.notisync.data.activity.ActivityEventDraft.toEntity(): ActivityEventEntity =
    ActivityEventEntity(
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
