package net.extrawdw.apps.notisync.data.activity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.extrawdw.apps.notisync.data.storage.operational.ActivityAction as StorageActivityAction
import net.extrawdw.apps.notisync.data.storage.operational.ActivityDao
import net.extrawdw.apps.notisync.data.storage.operational.ActivityDirection as StorageActivityDirection
import net.extrawdw.apps.notisync.data.storage.operational.ActivityEventEntity
import net.extrawdw.apps.notisync.data.storage.operational.ActivityFeature as StorageActivityFeature
import net.extrawdw.apps.notisync.data.storage.operational.ActivityOutcome as StorageActivityOutcome
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDeliveryMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityRepositoryTest {
    @Test
    fun insertIsIdempotentAndFlowMapsNewestRows() = runTest {
        val dao = FakeActivityDao()
        val repository: ActivityRepository = RoomActivityRepository(dao)
        val first = draft("event-a", recordedAt = 10L)
        val second = draft("event-b", recordedAt = 20L)

        assertTrue(repository.insert(first))
        assertFalse(repository.insert(first))
        assertTrue(repository.insert(second))

        val rows = repository.observeNewest().first()
        assertEquals(listOf("event-b", "event-a"), rows.map { it.eventId })
        assertEquals(ActivityFeature.NOTIFICATION, rows.first().feature)
        assertEquals(ActivityRenderArgs.V1(count = 3), rows.first().renderArgs)
    }

    @Test
    fun mapperDefensivelyCopiesTokensAndRenderArguments() = runTest {
        val dao = FakeActivityDao()
        val repository = RoomActivityRepository(dao)
        val sourceToken = ByteArray(ActivityLimits.MAX_COALESCING_TOKEN_BYTES) { it.toByte() }
        val event = draft("event-copy", token = ActivityCoalescingKeyToken.of(sourceToken))

        repository.insert(event)
        sourceToken.fill(99)
        val first = repository.observeNewest().first().single()
        val readToken = checkNotNull(first.coalescingKeyToken)
        val returnedTokenBytes = readToken.copyBytes()
        returnedTokenBytes.fill(88)
        assertArrayEquals(
            ByteArray(ActivityLimits.MAX_COALESCING_TOKEN_BYTES) { it.toByte() },
            readToken.copyBytes(),
        )

        // The adapter reads a fresh entity snapshot and does not expose the entity's BLOB object.
        val second = repository.observeNewest().first().single()
        assertEquals(ActivityRenderArgs.V1(count = 3), second.renderArgs)
        assertArrayEquals(
            ByteArray(ActivityLimits.MAX_COALESCING_TOKEN_BYTES) { it.toByte() },
            second.coalescingKeyToken!!.copyBytes(),
        )
    }

    @Test
    fun unknownAndCorruptRenderArgsBecomeSafePresentationStates() = runTest {
        val dao = FakeActivityDao(
            listOf(
                entity("unknown", version = 99, args = byteArrayOf(1, 2, 3)),
                entity("corrupt", version = ActivityRenderArgsCodec.CURRENT_VERSION, args = byteArrayOf(1)),
                entity("invalid-token", version = 99, args = byteArrayOf(), token = byteArrayOf(1), count = 0),
                entity(
                    "oversized-count",
                    version = 99,
                    args = byteArrayOf(),
                    count = ActivityLimits.MAX_COUNT + 1,
                ),
            ),
        )
        val rows = RoomActivityRepository(dao).observeNewest().first()

        assertEquals(ActivityRenderArgs.Unsupported(99), rows.first { it.eventId == "unknown" }.renderArgs)
        assertEquals(
            ActivityRenderArgs.Corrupt(
                ActivityRenderArgsCodec.CURRENT_VERSION,
                ActivityRenderArgs.CorruptReason.MALFORMED,
            ),
            rows.first { it.eventId == "corrupt" }.renderArgs,
        )
        val invalidToken = rows.first { it.eventId == "invalid-token" }
        assertNull(invalidToken.coalescingKeyToken)
        assertEquals(1, invalidToken.coalescedCount)
        assertEquals(
            ActivityLimits.MAX_COUNT,
            rows.first { it.eventId == "oversized-count" }.coalescedCount,
        )
    }

    @Test
    fun deterministicIdsAreStableFramedAndDomainSeparated() {
        val code = ActivitySemanticCode.of("notification.received")
        val message = ActivityStableIdentifier.of("msg-123")
        val peer = ActivityStableIdentifier.of("peer-456")

        val first = ActivityEventId.derive(code, listOf(message, peer))
        assertEquals(first, ActivityEventId.derive(code, listOf(message, peer)))
        assertNotEquals(
            first,
            ActivityEventId.derive(ActivitySemanticCode.of("notification.applied"), listOf(message, peer)),
        )
        assertNotEquals(first, ActivityEventId.derive(code, listOf(peer, message)))
        assertTrue(first.startsWith("activity-v1-"))
        assertEquals("activity-v1-".length + 64, first.length)
    }

    @Test
    fun deterministicIdsRequireStableIdentityAndSemanticCodesAreAsciiTokens() {
        val code = ActivitySemanticCode.of("notification.received")
        assertTrue(runCatching { ActivityEventId.derive(code, emptyList()) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ActivitySemanticCode.of("évent.received") }.isFailure)
        assertTrue(runCatching { ActivitySemanticCode.of("event.É") }.isFailure)
        assertTrue(runCatching { ActivitySemanticCode.of("Event.received") }.isFailure)
    }

    @Test
    fun reducerCopiesObservedListAndRecoversFromFailure() {
        val event = draft("event-ui").toEvent()
        val source = mutableListOf(event)
        val observed = reduceActivityTimeline(
            ActivityTimelineState(),
            ActivityTimelineAction.Observed(source),
        )
        source.clear()
        assertEquals(listOf(event), observed.events)
        assertFalse(observed.isLoading)
        assertNull(observed.error)
        assertEquals(
            ActivityTimelineError.ObservationFailed,
            reduceActivityTimeline(observed, ActivityTimelineAction.ObservationFailed).error,
        )
    }

    private fun draft(
        id: String,
        recordedAt: Long = 20L,
        token: ActivityCoalescingKeyToken? = null,
    ) = ActivityEventDraft(
        eventId = id,
        occurredAt = recordedAt - 1,
        recordedAt = recordedAt,
        feature = ActivityFeature.NOTIFICATION,
        semanticAction = ActivityAction.RECEIVED,
        direction = ActivityDirection.INBOUND,
        outcome = ActivityOutcome.SUCCESS,
        peerClientId = "peer-1",
        correlationId = "request-1",
        deliveryMode = ActivityDeliveryMode.RELAY_DRAIN,
        renderArgs = ActivityRenderArgs.V1(count = 3),
        coalescingKeyToken = token,
    )

    private fun ActivityEventDraft.toEvent() = ActivityEvent(
        eventId = eventId,
        occurredAt = occurredAt,
        recordedAt = recordedAt,
        feature = feature,
        semanticAction = semanticAction,
        direction = direction,
        outcome = outcome,
        peerClientId = peerClientId,
        correlationId = correlationId,
        deliveryMode = deliveryMode,
        renderArgs = renderArgs,
        coalescingKeyToken = coalescingKeyToken,
        coalescedCount = coalescedCount,
    )

    private fun entity(
        id: String,
        version: Int,
        args: ByteArray,
        token: ByteArray? = null,
        count: Int = 1,
    ) = ActivityEventEntity(
        eventId = id,
        occurredAt = 1L,
        recordedAt = 2L,
        feature = StorageActivityFeature.NOTIFICATION,
        semanticAction = StorageActivityAction.RECEIVED,
        direction = StorageActivityDirection.INBOUND,
        outcome = StorageActivityOutcome.SUCCESS,
        peerClientId = null,
        correlationId = null,
        deliveryMode = OperationalDeliveryMode.RELAY_DRAIN,
        renderArgsVersion = version,
        renderArgs = args,
        coalescingKeyToken = token,
        coalescedCount = count,
    )

    private class FakeActivityDao(initial: List<ActivityEventEntity> = emptyList()) : ActivityDao() {
        private val state = MutableStateFlow(initial.map(::copyEntity))
        private val rows: MutableList<ActivityEventEntity>
            get() = state.value.toMutableList()
        var lastPruneAt: Long? = null
            private set
        var pruneResult: Int = 0

        override fun observeNewest(limit: Int): Flow<List<ActivityEventEntity>> =
            state

        override suspend fun findInternal(eventId: String): ActivityEventEntity? =
            state.value.firstOrNull { it.eventId == eventId }?.let(::copyEntity)

        override suspend fun insertInternal(entity: ActivityEventEntity): Long {
            if (rows.any { it.eventId == entity.eventId }) return -1L
            state.value = (rows + copyEntity(entity)).sortedWith(
                compareByDescending<ActivityEventEntity> { it.recordedAt }
                    .thenByDescending { it.occurredAt }
                    .thenByDescending { it.eventId },
            )
            return 1L
        }

        override suspend fun pruneOlderThan(cutoff: Long, limit: Int): Int = 0

        override suspend fun rowCount(): Int = rows.size

        override suspend fun pruneOldest(limit: Int): Int = 0

        override suspend fun pruneBatch(now: Long): Int {
            lastPruneAt = now
            return pruneResult
        }

        private fun copyEntity(entity: ActivityEventEntity) = entity.copy(
            renderArgs = entity.renderArgs.copyOf(),
            coalescingKeyToken = entity.coalescingKeyToken?.copyOf(),
        )
    }
}
