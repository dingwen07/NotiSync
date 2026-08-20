package net.extrawdw.apps.notisync.data.activity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.extrawdw.apps.notisync.data.storage.operational.ActivityAction as StorageActivityAction
import net.extrawdw.apps.notisync.data.storage.operational.ActivityDao
import net.extrawdw.apps.notisync.data.storage.operational.ActivityDirection as StorageActivityDirection
import net.extrawdw.apps.notisync.data.storage.operational.ActivityEventEntity
import net.extrawdw.apps.notisync.data.storage.operational.ActivityFeature as StorageActivityFeature
import net.extrawdw.apps.notisync.data.storage.operational.ActivityOutcome as StorageActivityOutcome
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDeliveryMode as StorageDeliveryMode

/**
 * Internal Room adapter.  The DAO/entity/database types deliberately stop at this file; public consumers
 * depend only on [ActivityRepository] and immutable domain values.
 */
internal class RoomActivityRepository(
    private val dao: ActivityDao,
) : ActivityRepository {
    override fun observeNewest(limit: Int): Flow<List<ActivityEvent>> {
        require(limit in 1..ActivityLimits.MAX_ROWS) {
            "activity observation limit must be between 1 and ${ActivityLimits.MAX_ROWS}"
        }
        return dao.observeNewest(limit).map { rows -> rows.map(::toDomain) }
    }

    override suspend fun insert(event: ActivityEventDraft): Boolean = dao.insert(event.toEntity())

    override suspend fun prune(now: Long): Int {
        require(now > 0) { "activity prune time must be positive" }
        return dao.pruneBatch(now)
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

private fun toDomain(entity: ActivityEventEntity): ActivityEvent {
    val safeCoalescingToken = entity.coalescingKeyToken?.let { bytes ->
        try {
            ActivityCoalescingKeyToken.of(bytes.copyOf())
        } catch (_: IllegalArgumentException) {
            null
        }
    }
    return ActivityEvent(
        eventId = entity.eventId,
        occurredAt = entity.occurredAt,
        recordedAt = entity.recordedAt,
        feature = ActivityFeature.entries.first { it.token == entity.feature.token },
        semanticAction = ActivityAction.entries.first { it.token == entity.semanticAction.token },
        direction = ActivityDirection.entries.first { it.token == entity.direction.token },
        outcome = ActivityOutcome.entries.first { it.token == entity.outcome.token },
        peerClientId = entity.peerClientId,
        correlationId = entity.correlationId,
        deliveryMode = entity.deliveryMode?.let { mode ->
            ActivityDeliveryMode.entries.first { it.token == mode.token }
        },
        // The codec owns defensive copies and maps unknown/corrupt bytes to safe metadata-only states.
        renderArgs = ActivityRenderArgsCodec.decode(
            entity.renderArgsVersion,
            entity.renderArgs.copyOf(),
        ),
        coalescingKeyToken = safeCoalescingToken,
        // Rows written through the DAO are bounded. Clamp a physically corrupt row so a Flow remains
        // collectable and the UI cannot render an invalid count.
        coalescedCount = entity.coalescedCount.coerceIn(1, ActivityLimits.MAX_COUNT),
    )
}
