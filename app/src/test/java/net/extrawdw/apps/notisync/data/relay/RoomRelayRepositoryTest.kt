package net.extrawdw.apps.notisync.data.relay

import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import net.extrawdw.apps.notisync.data.activity.ActivityAction
import net.extrawdw.apps.notisync.data.activity.ActivityDirection
import net.extrawdw.apps.notisync.data.activity.ActivityEventDraft
import net.extrawdw.apps.notisync.data.activity.ActivityFeature
import net.extrawdw.apps.notisync.data.activity.ActivityOutcome
import net.extrawdw.apps.notisync.data.activity.ActivityRenderArgs
import net.extrawdw.apps.notisync.data.activity.ActivityRenderArgsCodec
import net.extrawdw.apps.notisync.data.storage.operational.ActivityEventEntity
import net.extrawdw.apps.notisync.data.storage.operational.MaintenanceStateEntity
import net.extrawdw.apps.notisync.data.storage.operational.MessageDedupEntity
import net.extrawdw.apps.notisync.data.storage.operational.MessageDedupEvidenceKind
import net.extrawdw.apps.notisync.data.storage.operational.RelayDao
import net.extrawdw.apps.notisync.data.storage.operational.RelayFinalizeResult
import net.extrawdw.apps.notisync.data.storage.operational.RelayHandledResolutionResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RoomRelayRepositoryTest {
    @Test
    fun resolveMapsEveryOutcomeAndPassesExactContinuityAndFingerprint() = runTest {
        val dao = FakeRelayDao()
        val repository = RoomRelayRepository(dao)
        val token = AuthenticatedRelayToken.of(fingerprint(3))
        val continuity = RelayOperationalContinuity(7, "incarnation-7")
        val mappings = mapOf(
            RelayHandledResolutionResult.EXACT_AUTHENTICATED to RelayHandledResolution.ExactAuthenticated,
            RelayHandledResolutionResult.LEGACY_RETAINED_NO_ACK to RelayHandledResolution.LegacyRetainedNoAck,
            RelayHandledResolutionResult.MISSING to RelayHandledResolution.Missing,
            RelayHandledResolutionResult.CONFLICT to RelayHandledResolution.Conflict,
            RelayHandledResolutionResult.STORAGE_CONTINUITY_MISMATCH to
                RelayHandledResolution.StorageContinuityMismatch,
        )

        mappings.forEach { (storage, domain) ->
            dao.resolveResult = storage
            assertEquals(domain, repository.resolveHandled(continuity, "message-1", token))
        }

        assertEquals("message-1", dao.resolvedMessageId)
        assertArrayEquals(fingerprint(3), dao.resolvedFingerprint)
        assertEquals(7L, dao.resolvedGeneration)
        assertEquals("incarnation-7", dao.resolvedIncarnation)
    }

    @Test
    fun finalizeMapsEveryOutcomeAndCopiesPrivacySafeActivityInOneDaoCall() = runTest {
        val dao = FakeRelayDao()
        val repository = RoomRelayRepository(dao)
        val activity = ActivityEventDraft(
            eventId = "event-1",
            occurredAt = 10,
            recordedAt = 11,
            feature = ActivityFeature.NOTIFICATION,
            semanticAction = ActivityAction.RECEIVED,
            direction = ActivityDirection.INBOUND,
            outcome = ActivityOutcome.SUCCESS,
            renderArgs = ActivityRenderArgs.V1(count = 2),
        )
        val request = RelayFinalizeRequest(
            messageId = "message-1",
            authenticatedToken = AuthenticatedRelayToken.of(fingerprint(4)),
            handledAt = 12,
            activity = activity,
        )
        val continuity = RelayOperationalContinuity(2, "incarnation-2")
        val mappings = mapOf(
            RelayFinalizeResult.APPLIED to RelayFinalizeOutcome.APPLIED,
            RelayFinalizeResult.ALREADY_FINALIZED to RelayFinalizeOutcome.ALREADY_FINALIZED,
            RelayFinalizeResult.LEGACY_RETAINED_NO_ACK to RelayFinalizeOutcome.LEGACY_RETAINED_NO_ACK,
            RelayFinalizeResult.CONFLICT to RelayFinalizeOutcome.CONFLICT,
            RelayFinalizeResult.STORAGE_CONTINUITY_MISMATCH to RelayFinalizeOutcome.STORAGE_CONTINUITY_MISMATCH,
        )

        mappings.forEach { (storage, domain) ->
            dao.finalizeResult = storage
            assertEquals(domain, repository.finalize(continuity, request))
        }

        val handled = requireNotNull(dao.finalizedHandled)
        assertEquals(MessageDedupEvidenceKind.AUTHENTICATED_FINGERPRINT, handled.evidenceKind)
        assertArrayEquals(fingerprint(4), handled.authenticatedFingerprint)
        assertEquals(12L, handled.handledAt)
        assertEquals(2L, dao.finalizedGeneration)
        assertEquals("incarnation-2", dao.finalizedIncarnation)
        val mappedActivity = requireNotNull(dao.finalizedActivity)
        assertEquals("event-1", mappedActivity.eventId)
        assertArrayEquals(ActivityRenderArgsCodec.encode(ActivityRenderArgs.V1(count = 2)), mappedActivity.renderArgs)
    }

    @Test
    fun pruneOwnsFrozenCutoffAndStorageFailuresOrCancellationPropagate() = runTest {
        val dao = FakeRelayDao().apply { pruneResult = 3 }
        val repository = RoomRelayRepository(dao)

        assertEquals(0, repository.pruneHandled(RelayLimits.HANDLED_RETENTION_MILLIS))
        assertNull(dao.pruneCutoff)
        assertEquals(3, repository.pruneHandled(RelayLimits.HANDLED_RETENTION_MILLIS + 20))
        assertEquals(20L, dao.pruneCutoff)

        val cancellation = CancellationException("cancelled")
        dao.resolveFailure = cancellation
        assertSame(
            cancellation,
            expectFailure<CancellationException> {
                repository.resolveHandled(
                    RelayOperationalContinuity(1, "incarnation-1"),
                    "message-1",
                    AuthenticatedRelayToken.of(fingerprint(1)),
                )
            },
        )
        val storage = IllegalStateException("storage")
        dao.resolveFailure = storage
        assertSame(
            storage,
            expectFailure<IllegalStateException> {
                repository.resolveHandled(
                    RelayOperationalContinuity(1, "incarnation-1"),
                    "message-1",
                    AuthenticatedRelayToken.of(fingerprint(1)),
                )
            },
        )
    }

    private class FakeRelayDao : RelayDao() {
        var resolveResult = RelayHandledResolutionResult.MISSING
        var finalizeResult = RelayFinalizeResult.APPLIED
        var resolveFailure: Throwable? = null
        var resolvedMessageId: String? = null
        var resolvedFingerprint: ByteArray? = null
        var resolvedGeneration: Long? = null
        var resolvedIncarnation: String? = null
        var finalizedHandled: MessageDedupEntity? = null
        var finalizedGeneration: Long? = null
        var finalizedIncarnation: String? = null
        var finalizedActivity: ActivityEventEntity? = null
        var pruneResult = 0
        var pruneCutoff: Long? = null

        override suspend fun resolveHandled(
            messageId: String,
            authenticatedFingerprint: ByteArray,
            expectedOperationalGeneration: Long,
            expectedStorageIncarnationId: String,
        ): RelayHandledResolutionResult {
            resolveFailure?.let { throw it }
            resolvedMessageId = messageId
            resolvedFingerprint = authenticatedFingerprint.copyOf()
            resolvedGeneration = expectedOperationalGeneration
            resolvedIncarnation = expectedStorageIncarnationId
            return resolveResult
        }

        override suspend fun finalizeHandled(
            handled: MessageDedupEntity,
            expectedOperationalGeneration: Long,
            expectedStorageIncarnationId: String,
            activity: ActivityEventEntity?,
        ): RelayFinalizeResult {
            finalizedHandled = handled.copy(
                authenticatedFingerprint = handled.authenticatedFingerprint?.copyOf(),
            )
            finalizedGeneration = expectedOperationalGeneration
            finalizedIncarnation = expectedStorageIncarnationId
            finalizedActivity = activity?.copy(
                renderArgs = activity.renderArgs.copyOf(),
                coalescingKeyToken = activity.coalescingKeyToken?.copyOf(),
            )
            return finalizeResult
        }

        override suspend fun findHandled(messageId: String): MessageDedupEntity? = null
        override suspend fun maintenanceForFinalize(): MaintenanceStateEntity? = null
        override suspend fun findActivityForFinalize(eventId: String): ActivityEventEntity? = null
        override suspend fun insertHandledInternal(entity: MessageDedupEntity) = Unit
        override suspend fun insertActivityInternal(entity: ActivityEventEntity): Long = 1

        override suspend fun pruneHandledBefore(cutoff: Long): Int {
            pruneCutoff = cutoff
            return pruneResult
        }
    }

    private suspend inline fun <reified T : Throwable> expectFailure(
        crossinline block: suspend () -> Unit,
    ): T = try {
        block()
        throw AssertionError("Expected ${T::class.java.name}")
    } catch (failure: Throwable) {
        if (failure !is T) throw failure
        failure
    }

    private fun fingerprint(seed: Int) = ByteArray(32) { (it + seed).toByte() }
}
