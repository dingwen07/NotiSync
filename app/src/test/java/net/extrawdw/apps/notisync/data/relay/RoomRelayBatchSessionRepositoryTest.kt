package net.extrawdw.apps.notisync.data.relay

import kotlinx.coroutines.test.runTest
import net.extrawdw.apps.notisync.data.storage.operational.RelayBatchPresentationKind
import net.extrawdw.apps.notisync.data.storage.operational.RelayBatchStageDao
import net.extrawdw.apps.notisync.data.storage.operational.RelayBatchStageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomRelayBatchSessionRepositoryTest {
    @Test
    fun recordUsesFingerprintIdentityPreservesFirstPresentationAndLatchesConflict() = runTest {
        val dao = FakeBatchDao()
        val repository = RoomRelayBatchSessionRepository(dao)
        val first = token(1)

        assertEquals(
            RelayBatchRecordOutcome.INSERTED,
            repository.record("message-1", first, RelayBatchPresentation.NOTIFICATION),
        )
        assertEquals(
            RelayBatchRecordOutcome.EXACT,
            repository.record("message-1", token(1), RelayBatchPresentation.DISMISSAL),
        )
        assertEquals(RelayBatchPresentation.NOTIFICATION, repository.find("message-1")?.presentation)

        assertEquals(
            RelayBatchRecordOutcome.CONFLICT,
            repository.record("message-1", token(2), RelayBatchPresentation.NOTIFICATION),
        )
        assertTrue(requireNotNull(repository.find("message-1")).conflict)
        assertEquals(
            RelayBatchRecordOutcome.CONFLICT,
            repository.record("message-1", first, RelayBatchPresentation.NOTIFICATION),
        )
    }

    @Test
    fun pagesAreBoundedOrderedSeparatedAndDefensivelyCopied() = runTest {
        val dao = FakeBatchDao()
        val repository = RoomRelayBatchSessionRepository(dao)
        repository.record("message-b", token(2), RelayBatchPresentation.NONE)
        repository.record("message-a", token(1), RelayBatchPresentation.DISMISSAL)
        repository.record("message-c", token(3), RelayBatchPresentation.NOTIFICATION)

        val presentation = repository.presentationPage(null, 2)
        val nonPresentation = repository.nonPresentationPage(null, 2)

        assertEquals(listOf("message-a", "message-c"), presentation.map { it.messageId })
        assertEquals(listOf("message-b"), nonPresentation.map { it.messageId })
        val firstRead = presentation.first().authenticatedToken
        assertNotSame(firstRead, presentation.first().authenticatedToken)
        firstRead.copyBytes().fill(0)
        assertEquals(token(1), repository.find("message-a")?.authenticatedToken)
        var failure: Throwable? = null
        try {
            repository.presentationPage(null, RelayLimits.MAX_BATCH_PAGE_ROWS + 1)
        } catch (thrown: Throwable) {
            failure = thrown
        }
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun deleteRequiresExactCompleteMetadataAndClearIsCounted() = runTest {
        val dao = FakeBatchDao()
        val repository = RoomRelayBatchSessionRepository(dao)
        repository.record("message-1", token(1), RelayBatchPresentation.NOTIFICATION)
        val exact = requireNotNull(repository.find("message-1"))

        assertFalse(
            repository.deleteExact(
                RelayBatchItem(
                    "message-1",
                    token(1),
                    conflict = false,
                    presentation = RelayBatchPresentation.DISMISSAL,
                ),
            ),
        )
        assertTrue(repository.deleteExact(exact))
        assertNull(repository.find("message-1"))

        repository.record("message-2", token(2), RelayBatchPresentation.NONE)
        repository.record("message-3", token(3), RelayBatchPresentation.NONE)
        assertEquals(2, repository.clearAtDrainBoundary())
        assertEquals(0, repository.clearAtDrainBoundary())
    }

    private class FakeBatchDao : RelayBatchStageDao() {
        private val rows = linkedMapOf<String, RelayBatchStageEntity>()

        protected override suspend fun findInternal(messageId: String): RelayBatchStageEntity? =
            rows[messageId]?.copyDefensively()

        protected override suspend fun insertInternal(entity: RelayBatchStageEntity) {
            check(rows.putIfAbsent(entity.messageId, entity.copyDefensively()) == null)
        }

        protected override suspend fun markConflictInternal(messageId: String): Int {
            val current = rows[messageId] ?: return 0
            if (current.conflict) return 0
            rows[messageId] = current.copy(conflict = true).copyDefensively()
            return 1
        }

        override suspend fun clearAtDrainBoundary(): Int = rows.size.also { rows.clear() }

        protected override suspend fun presentationPageInternal(
            afterMessageId: String?,
            limit: Int,
            none: RelayBatchPresentationKind,
        ): List<RelayBatchStageEntity> = page(afterMessageId, limit) { it.presentationKind != none }

        protected override suspend fun nonPresentationPageInternal(
            afterMessageId: String?,
            limit: Int,
            none: RelayBatchPresentationKind,
        ): List<RelayBatchStageEntity> = page(afterMessageId, limit) { it.presentationKind == none }

        protected override suspend fun deleteExactInternal(
            messageId: String,
            authenticatedFingerprint: ByteArray,
            conflict: Boolean,
            presentationKind: RelayBatchPresentationKind,
        ): Int {
            val current = rows[messageId] ?: return 0
            if (
                !current.authenticatedFingerprint.contentEquals(authenticatedFingerprint) ||
                current.conflict != conflict ||
                current.presentationKind != presentationKind
            ) return 0
            rows.remove(messageId)
            return 1
        }

        private fun page(
            afterMessageId: String?,
            limit: Int,
            predicate: (RelayBatchStageEntity) -> Boolean,
        ): List<RelayBatchStageEntity> = rows.values
            .asSequence()
            .filter(predicate)
            .filter { afterMessageId == null || it.messageId > afterMessageId }
            .sortedBy { it.messageId }
            .take(limit)
            .map(RelayBatchStageEntity::copyDefensively)
            .toList()
    }

    private fun token(value: Byte): AuthenticatedRelayToken =
        AuthenticatedRelayToken.of(ByteArray(32) { value })
}

private fun RelayBatchStageEntity.copyDefensively(): RelayBatchStageEntity = copy(
    authenticatedFingerprint = authenticatedFingerprint.copyOf(),
)
