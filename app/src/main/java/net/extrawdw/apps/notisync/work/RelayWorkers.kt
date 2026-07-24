package net.extrawdw.apps.notisync.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.data.MessageStore
import net.extrawdw.apps.notisync.data.InboxInsertResult
import net.extrawdw.notisync.peer.channel.DeliveryOutcome
import net.extrawdw.notisync.peer.channel.safeToAck
import net.extrawdw.notisync.peer.transport.DeliveryMode
import net.extrawdw.notisync.peer.transport.RelayBatchFetchResult
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class RelayHandleResult { COMPLETE, RETRY }

/** Ack only after SecureChannel and its inline durable handler completed successfully. */
internal suspend fun finishRelayDelivery(
    messageId: String,
    outcome: DeliveryOutcome,
    ack: suspend (List<String>) -> Boolean,
    queueAck: (String) -> Unit,
): RelayHandleResult {
    if (!outcome.safeToAck) return RelayHandleResult.RETRY
    if (!runCatching { ack(listOf(messageId)) }.getOrDefault(false)) queueAck(messageId)
    return RelayHandleResult.COMPLETE
}

/**
 * Handle an FCM-wake relay fetch out-of-band. The messaging service always enqueues wake pointers here so
 * the FCM callback does not block on graph init, network, or relay fetch time. It survives process death and
 * retries with backoff once the network returns, so an oversized notification still lands rather than waiting
 * for the next app foreground. Fetch is a broker peek; explicit ack follows only after durable handling.
 */
class WakeFetchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val messageId = inputData.getString(KEY_MESSAGE_ID) ?: return Result.success()
        val pushedAcceptedAt = inputData.getLong(KEY_ACCEPTED_AT, NO_ACCEPTED_AT).takeIf { it != NO_ACCEPTED_AT }
        val graph = (applicationContext as NotiSyncApp).awaitGraphReady() ?: return Result.retry()
        if (graph.secureChannel == null) return Result.retry()
        val delivery = runCatching { graph.transport.fetchRelayDelivery(messageId) }.getOrNull()
        return when {
            delivery != null -> {
                val outcome = graph.deliverInbound(
                    delivery.envelope,
                    DeliveryMode.FCM_RELAY_FETCH,
                    delivery.acceptedAt ?: pushedAcceptedAt,
                )
                when (
                    finishRelayDelivery(
                        messageId = messageId,
                        outcome = outcome,
                        ack = graph.transport::ackRelayMessages,
                        queueAck = graph.messageStore::enqueueAck,
                    )
                ) {
                    RelayHandleResult.COMPLETE -> Result.success()
                    RelayHandleResult.RETRY -> if (runAttemptCount < MAX_ATTEMPTS) {
                        Result.retry()
                    } else Result.success() // Still queued at the broker for the periodic/foreground backstop.
                }
            }
            // null = not fetchable right now (offline/transient) OR already gone (delivered over a
            // foreground socket / acked / TTL-expired). Retry a bounded number of times for the
            // transient case, then stop — the periodic drain and foreground flush remain as backstops.
            runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        private const val KEY_MESSAGE_ID = "messageId"
        private const val KEY_ACCEPTED_AT = "acceptedAt"
        private const val NO_ACCEPTED_AT = Long.MIN_VALUE
        private const val MAX_ATTEMPTS = 5

        /** Enqueue a retry for [messageId]. KEEP: a duplicate wake for the same id never piles up a 2nd chain. */
        fun enqueue(context: Context, messageId: String, acceptedAt: Long? = null) {
            val input = if (acceptedAt == null) {
                workDataOf(KEY_MESSAGE_ID to messageId)
            } else {
                workDataOf(KEY_MESSAGE_ID to messageId, KEY_ACCEPTED_AT to acceptedAt)
            }
            val request = OneTimeWorkRequestBuilder<WakeFetchWorker>()
                .setInputData(input)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("wake-fetch-$messageId", ExistingWorkPolicy.KEEP, request)
        }

        private const val BACKOFF_SECONDS = 30L
    }
}

/**
 * Shared periodic/quiet-window drain: flush ACKs, stage one complete finite broker snapshot, reconcile
 * notification lifecycle state, then prune. Nothing is rendered from a partial snapshot.
 *
 * Deferrable + constrained (connected, battery-not-low) so it is near-free on battery.
 */
class RelayDrainWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = drainMutex.withLock {
        val graph = (applicationContext as NotiSyncApp).awaitGraphReady() ?: return@withLock Result.retry()
        val channel = graph.secureChannel ?: return@withLock Result.success()
        val store = graph.messageStore

        // The periodic worker must respect the same quiet window as the fast worker; otherwise it could
        // happen to fire in the middle of a burst and render the very backlog the fast drain is coalescing.
        val quietWatermark = store.lastDeferredAt()
        if (store.hasDeferredInbox()) {
            val remaining = remainingDeferredQuietDelay(quietWatermark, allowImmediate = true)
            if (remaining > 0) {
                scheduleAfterDeferredQuiet(applicationContext, remaining)
                return@withLock Result.success()
            }
        }

        // Early-acked inbox rows should normally disappear from the snapshot. A failed flush is safe:
        // the batch may contain the same id, and relay_inbox's primary key collapses that duplicate.
        flushPendingAcks(graph)

        val batchResult = graph.transport.fetchRelayBatch { frame ->
            val result = store.stageBatchItem(
                messageId = requireNotNull(frame.messageId),
                envelope = requireNotNull(frame.envelope),
                acceptedAt = requireNotNull(frame.acceptedAt),
            )
            check(result != InboxInsertResult.FAILED) { "failed to stage relay batch item" }
        }
        when (batchResult) {
            is RelayBatchFetchResult.Complete -> Unit
            RelayBatchFetchResult.Failed -> return@withLock Result.retry()
            RelayBatchFetchResult.Unsupported -> {
                if (!stageLegacySnapshot(graph)) return@withLock Result.retry()
            }
        }

        // A new distinct deferred id may have arrived while the stream was in flight. WorkManager REPLACE
        // cancels this coroutine, and this persisted check closes the small scheduling/cancellation race.
        val latestDeferredAt = store.lastDeferredAt()
        if (store.hasDeferredInbox() && latestDeferredAt != quietWatermark) {
            scheduleAfterDeferredQuiet(
                applicationContext,
                remainingDeferredQuietDelay(latestDeferredAt),
            )
            return@withLock Result.success()
        }

        // The client only reaches here after the explicit END frame and validated item count. Reconcile the
        // complete staged set, then ack broker-owned rows in bounded batches.
        var retryNeeded = RelayInboxReconciler(channel, store).reconcile()
        if (!flushPendingAcks(graph)) retryNeeded = true
        runCatching { store.prune() }
        if (retryNeeded) Result.retry() else Result.success()
    }

    private suspend fun flushPendingAcks(graph: net.extrawdw.apps.notisync.AppGraph): Boolean {
        val store = graph.messageStore
        var batches = 0
        while (batches++ < MAX_ACK_BATCHES) {
            val pending = store.pendingAcks()
            if (pending.isEmpty()) return true
            if (!runCatching { graph.transport.ackRelayMessages(pending) }.getOrDefault(false)) return false
            store.clearAcks(pending)
            if (pending.size < MessageStore.MAX_ACK_BATCH) return true
        }
        return store.pendingAcks(1).isEmpty()
    }

    /** Rolling-deploy fallback for a broker that predates `/v2/relay/batch`. */
    private suspend fun stageLegacySnapshot(graph: net.extrawdw.apps.notisync.AppGraph): Boolean {
        val ids = graph.transport.fetchPendingRelayIdsOrNull() ?: return false
        for (id in ids) {
            val delivery = graph.transport.fetchRelayDelivery(id) ?: return false
            val result = graph.messageStore.stageBatchItem(
                messageId = id,
                envelope = net.extrawdw.notisync.protocol.ProtocolCodec.encodeToCbor(delivery.envelope),
                acceptedAt = delivery.acceptedAt ?: System.currentTimeMillis(),
            )
            if (result == InboxInsertResult.FAILED) return false
        }
        return true
    }

    companion object {
        private const val PERIODIC_UNIQUE_NAME = "relay-drain"
        private const val DEFERRED_UNIQUE_NAME = "relay-drain-deferred"
        private const val DEFERRED_QUIET_MS = 120_000L
        private val drainMutex = Mutex()

        /** Bound the ack loop so a persistent clear failure can't spin it; the rest carries to next run. */
        private const val MAX_ACK_BATCHES = 40

        /** Low frequency by design: the FCM + foreground-WS paths are primary; this only sweeps the tail. */
        private const val INTERVAL_HOURS = 6L

        /** Schedule the periodic backstop. Idempotent (KEEP) — safe to call on every app start. */
        fun schedulePeriodic(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<RelayDrainWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .setRequiresBatteryNotLow(true)
                            .build()
                    )
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Every newly inserted deferred id replaces this delay; duplicate ids never call this method. */
        fun scheduleAfterDeferredQuiet(context: Context, delayMillis: Long = DEFERRED_QUIET_MS) {
            val request = OneTimeWorkRequestBuilder<RelayDrainWorker>()
                .setInitialDelay(delayMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(DEFERRED_UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        internal fun remainingDeferredQuietDelay(
            lastDeferredAt: Long?,
            now: Long = System.currentTimeMillis(),
            allowImmediate: Boolean = false,
        ): Long {
            if (lastDeferredAt == null) return if (allowImmediate) 0L else DEFERRED_QUIET_MS
            val remaining = DEFERRED_QUIET_MS - (now - lastDeferredAt)
            return if (allowImmediate) remaining.coerceAtLeast(0L) else remaining.coerceAtLeast(1L)
        }
    }
}
