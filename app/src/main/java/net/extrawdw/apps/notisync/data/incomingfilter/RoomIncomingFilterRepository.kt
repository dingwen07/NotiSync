package net.extrawdw.apps.notisync.data.incomingfilter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.extrawdw.apps.notisync.data.storage.operational.IncomingFilterAggregateRow
import net.extrawdw.apps.notisync.data.storage.operational.IncomingFilterDao
import net.extrawdw.apps.notisync.data.storage.operational.IncomingFilterEntity
import net.extrawdw.apps.notisync.data.storage.operational.IncomingFilterReplaceResult as StorageIncomingFilterReplaceResult
import net.extrawdw.apps.notisync.data.storage.operational.IncomingFilterRuleEntity
import net.extrawdw.apps.notisync.data.storage.operational.NotificationOriginPlatform

/** Sole Room adapter for [IncomingFilterRepository]. */
internal class RoomIncomingFilterRepository(
    private val dao: IncomingFilterDao,
    scope: CoroutineScope,
) : IncomingFilterRepository {
    override val projection: IncomingFilterProjection = IncomingFilterProjection()

    private val writeMutex = Mutex()
    private val projectionHydrated = CompletableDeferred<Unit>()

    init {
        scope.launch {
            try {
                dao.observeAggregates().collect { rows ->
                    // A missing requester in a full-list emission is not interpreted as a deletion: an
                    // older delayed emission must never clear a newer hot-path snapshot. Explicit deletes
                    // update the projection after the owning Room command commits.
                    rows.forEach { row -> projection.accept(row.toDomain()) }
                    projectionHydrated.complete(Unit)
                }
            } catch (error: Throwable) {
                projectionHydrated.completeExceptionally(error)
                if (error is CancellationException) throw error
                throw error
            }
        }
    }

    override fun observe(requesterClientId: String): Flow<IncomingFilterSnapshot?> {
        requireRequesterIdForRepository(requesterClientId)
        return dao.observeAggregate(requesterClientId).map { row -> row?.toDomain() }
    }

    override fun observeAll(): Flow<List<IncomingFilterSnapshot>> =
        dao.observeAggregates().map { rows -> rows.map { it.toDomain() } }

    override suspend fun read(requesterClientId: String): IncomingFilterSnapshot? {
        requireRequesterIdForRepository(requesterClientId)
        return dao.findAggregate(requesterClientId)?.toDomain()
    }

    override suspend fun replace(update: IncomingFilterUpdate): IncomingFilterReplaceResult =
        writeMutex.withLock {
            val canonical = IncomingFilterCanonicalizer.canonicalize(
                update.rules.map { it.toCanonicalValue() },
            )
            val header = IncomingFilterEntity(
                requesterClientId = update.requesterClientId,
                canonicalizationVersion = IncomingFilterCanonicalizer.VERSION,
                updatedAt = update.updatedAt,
                receivedAt = update.receivedAt,
                ruleSetDigest = canonical.digestCopy(),
            )
            val rules = canonical.rules.map { rule ->
                IncomingFilterRuleEntity(
                    requesterClientId = update.requesterClientId,
                    ruleDigest = rule.digestCopy(),
                    position = rule.position,
                    originPlatform = rule.value.origin.toStorageOrigin(),
                    appId = rule.value.appId,
                    channelId = rule.value.channelId,
                )
            }
            val result = dao.replace(header, rules).toDomainResult()
            // Read back the committed aggregate for every result. This preserves the stored local receipt
            // timestamp for UNCHANGED/STALE/CONFLICT and fences the projection against any delayed emission.
            val persisted = dao.findAggregate(update.requesterClientId)?.toDomain()
            if (persisted != null) {
                if (result == IncomingFilterReplaceResult.INSERTED ||
                    result == IncomingFilterReplaceResult.REPLACED
                ) {
                    // The Room result/readback is the owner-write commit point. This narrowly permits
                    // an exact same-version reinsertion to clear a delete tombstone; delayed Flow
                    // emissions still go through the ordinary fenced accept path above.
                    projection.acceptOwnerWrite(persisted)
                } else {
                    projection.accept(persisted)
                }
            } else if (result == IncomingFilterReplaceResult.INSERTED ||
                result == IncomingFilterReplaceResult.REPLACED
            ) {
                error("incoming filter replacement committed without a persisted aggregate")
            }
            result
        }

    override suspend fun remove(requesterClientId: String): Boolean = writeMutex.withLock {
        requireRequesterIdForRepository(requesterClientId)
        val before = dao.findAggregate(requesterClientId)?.toDomain()
        val removed = dao.remove(requesterClientId)
        if (removed == 1) {
            projection.remove(requesterClientId, before)
            true
        } else {
            false
        }
    }

    override suspend fun awaitProjectionHydrated() {
        projectionHydrated.await()
    }
}

private fun IncomingFilterAggregateRow.toDomain(): IncomingFilterSnapshot = try {
    require(header.requesterClientId.isNotBlank()) { "persisted incoming filter requester is invalid" }
    require(rules.all { it.requesterClientId == header.requesterClientId }) {
        "persisted incoming filter rule ownership is invalid"
    }
    val orderedRules = rules.sortedBy { it.position }
    IncomingFilterSnapshot(
        requesterClientId = header.requesterClientId,
        canonicalizationVersion = header.canonicalizationVersion,
        updatedAt = header.updatedAt,
        receivedAt = header.receivedAt,
        ruleSetDigest = IncomingFilterDigest.of(header.ruleSetDigest.copyOf()),
        rules = orderedRules.map { rule ->
            IncomingFilterRule(
                position = rule.position,
                origin = rule.originPlatform.toDomainOrigin(),
                appId = rule.appId,
                channelId = rule.channelId,
                digest = IncomingFilterDigest.of(rule.ruleDigest.copyOf()),
            )
        },
    )
} catch (error: IllegalArgumentException) {
    throw IllegalStateException("Persisted incoming filter aggregate is invalid", error)
}

private fun StorageIncomingFilterReplaceResult.toDomainResult(): IncomingFilterReplaceResult = when (this) {
    StorageIncomingFilterReplaceResult.INSERTED -> IncomingFilterReplaceResult.INSERTED
    StorageIncomingFilterReplaceResult.REPLACED -> IncomingFilterReplaceResult.REPLACED
    StorageIncomingFilterReplaceResult.UNCHANGED -> IncomingFilterReplaceResult.UNCHANGED
    StorageIncomingFilterReplaceResult.STALE -> IncomingFilterReplaceResult.STALE
    StorageIncomingFilterReplaceResult.CONFLICT -> IncomingFilterReplaceResult.CONFLICT
}

private fun CanonicalIncomingFilterOrigin.toStorageOrigin(): NotificationOriginPlatform = when (this) {
    CanonicalIncomingFilterOrigin.ANDROID_LOCAL -> NotificationOriginPlatform.ANDROID_LOCAL
    CanonicalIncomingFilterOrigin.IOS_ANCS -> NotificationOriginPlatform.IOS_ANCS
}

private fun NotificationOriginPlatform.toDomainOrigin(): IncomingFilterOrigin = when (this) {
    NotificationOriginPlatform.ANDROID_LOCAL -> IncomingFilterOrigin.ANDROID_LOCAL
    NotificationOriginPlatform.IOS_ANCS -> IncomingFilterOrigin.IOS_ANCS
}

private fun requireRequesterIdForRepository(value: String) {
    require(value.isNotBlank()) { "filter requester id must not be blank" }
    require(value.length <= IncomingFilterLimits.MAX_REQUESTER_ID_CHARS) {
        "filter requester id is too long"
    }
    require(value.none(Char::isISOControl)) { "filter requester id contains a control character" }
}
