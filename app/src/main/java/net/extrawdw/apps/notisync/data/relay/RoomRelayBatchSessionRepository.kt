package net.extrawdw.apps.notisync.data.relay

import net.extrawdw.apps.notisync.data.storage.operational.RelayBatchPresentationKind
import net.extrawdw.apps.notisync.data.storage.operational.RelayBatchRecordResult
import net.extrawdw.apps.notisync.data.storage.operational.RelayBatchStageDao
import net.extrawdw.apps.notisync.data.storage.operational.RelayBatchStageEntity

/** Sole Room adapter for the metadata-only finite-drain scratch contract. */
internal class RoomRelayBatchSessionRepository(
    private val dao: RelayBatchStageDao,
) : RelayBatchSessionRepository {
    override suspend fun clearAtDrainBoundary(): Int = dao.clearAtDrainBoundary().also {
        check(it >= 0) { "relay batch clear returned an impossible row count" }
    }

    override suspend fun record(
        messageId: String,
        authenticatedToken: AuthenticatedRelayToken,
        presentation: RelayBatchPresentation,
    ): RelayBatchRecordOutcome {
        requireRelayMessageId(messageId, "relay batch message id")
        return when (
            dao.recordItem(
                messageId = messageId,
                authenticatedFingerprint = authenticatedToken.copyBytes(),
                presentationKind = presentation.toStorage(),
            )
        ) {
            RelayBatchRecordResult.INSERTED -> RelayBatchRecordOutcome.INSERTED
            RelayBatchRecordResult.EXACT -> RelayBatchRecordOutcome.EXACT
            RelayBatchRecordResult.CONFLICT -> RelayBatchRecordOutcome.CONFLICT
        }
    }

    override suspend fun presentationPage(afterMessageId: String?, limit: Int): List<RelayBatchItem> {
        requirePage(afterMessageId, limit)
        return dao.presentationPage(afterMessageId, limit).map(RelayBatchStageEntity::toDomain)
    }

    override suspend fun nonPresentationPage(afterMessageId: String?, limit: Int): List<RelayBatchItem> {
        requirePage(afterMessageId, limit)
        return dao.nonPresentationPage(afterMessageId, limit).map(RelayBatchStageEntity::toDomain)
    }

    override suspend fun find(messageId: String): RelayBatchItem? {
        requireRelayMessageId(messageId, "relay batch message id")
        return dao.find(messageId)?.toDomain()
    }

    override suspend fun deleteExact(expected: RelayBatchItem): Boolean = dao.deleteExact(expected.toEntity())

    private fun requirePage(afterMessageId: String?, limit: Int) {
        afterMessageId?.let { requireRelayMessageId(it, "relay batch page cursor") }
        require(limit in 1..RelayLimits.MAX_BATCH_PAGE_ROWS) { "relay batch page limit is outside its bound" }
    }
}

private fun RelayBatchStageEntity.toDomain(): RelayBatchItem = RelayBatchItem(
    messageId = messageId,
    authenticatedToken = AuthenticatedRelayToken.of(authenticatedFingerprint),
    conflict = conflict,
    presentation = presentationKind.toDomain(),
)

private fun RelayBatchItem.toEntity(): RelayBatchStageEntity = RelayBatchStageEntity(
    messageId = messageId,
    authenticatedFingerprint = authenticatedToken.copyBytes(),
    conflict = conflict,
    presentationKind = presentation.toStorage(),
)

private fun RelayBatchPresentation.toStorage(): RelayBatchPresentationKind = when (this) {
    RelayBatchPresentation.NONE -> RelayBatchPresentationKind.NONE
    RelayBatchPresentation.NOTIFICATION -> RelayBatchPresentationKind.NOTIFICATION
    RelayBatchPresentation.DISMISSAL -> RelayBatchPresentationKind.DISMISSAL
}

private fun RelayBatchPresentationKind.toDomain(): RelayBatchPresentation = when (this) {
    RelayBatchPresentationKind.NONE -> RelayBatchPresentation.NONE
    RelayBatchPresentationKind.NOTIFICATION -> RelayBatchPresentation.NOTIFICATION
    RelayBatchPresentationKind.DISMISSAL -> RelayBatchPresentation.DISMISSAL
}
