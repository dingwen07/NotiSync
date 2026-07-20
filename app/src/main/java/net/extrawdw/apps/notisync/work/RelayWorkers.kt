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
import net.extrawdw.notisync.peer.channel.DeliveryOutcome
import net.extrawdw.notisync.peer.channel.safeToAck
import net.extrawdw.notisync.peer.transport.DeliveryMode
import java.util.concurrent.TimeUnit

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
        val graph = (applicationContext as NotiSyncApp).awaitGraphReady() ?: return Result.retry()
        val channel = graph.secureChannel ?: return Result.retry()
        val envelope = runCatching { graph.transport.fetchRelayMessage(messageId) }.getOrNull()
        return when {
            envelope != null -> {
                val outcome = channel.deliver(envelope, DeliveryMode.FCM_RELAY_FETCH)
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
        private const val MAX_ATTEMPTS = 5

        /** Enqueue a retry for [messageId]. KEEP: a duplicate wake for the same id never piles up a 2nd chain. */
        fun enqueue(context: Context, messageId: String) {
            val request = OneTimeWorkRequestBuilder<WakeFetchWorker>()
                .setInputData(workDataOf(KEY_MESSAGE_ID to messageId))
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
 * Low-frequency catch-all with three jobs, in order:
 *  1. **Ack** the pending-ack queue (deliveries we handled but couldn't ack inline — chiefly FCM-inline
 *     pushes and locally-dismissed mirrors) in ONE batched request. Doing this BEFORE the fetch means
 *     the drain below won't re-list what we've already handled, and it stops the relay backlog that was
 *     re-posting after a restart from accumulating in the first place.
 *  2. **Drain** whatever is still queued (FCM-deferred normal-priority, or a wake fetch that failed
 *     offline): peek + deliver + ack each only after durable handling. Idempotent across restarts.
 *  3. **Prune** handled-message history past its retention window.
 *
 * Deferrable + constrained (connected, battery-not-low) so it is near-free on battery.
 */
class RelayDrainWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as NotiSyncApp).awaitGraphReady() ?: return Result.retry()
        val channel = graph.secureChannel ?: return Result.success()
        val store = graph.messageStore

        // 1. Batch-ack handled-but-unacked ids, oldest first, in bounded chunks. A failed ack KEEPS the
        //    ids for next run — a dropped ack only costs a later (deduped) redelivery, never a loss.
        var batches = 0
        while (batches++ < MAX_ACK_BATCHES) {
            val pending = store.pendingAcks()
            if (pending.isEmpty()) break
            if (!runCatching { graph.transport.ackRelayMessages(pending) }.getOrDefault(false)) break
            store.clearAcks(pending)
            if (pending.size < MessageStore.MAX_ACK_BATCH) break // last (partial) chunk
        }

        // 2. Drain anything still queued and deliver it.
        val ids =
            runCatching { graph.transport.fetchPendingRelayIds() }.getOrElse { return Result.retry() }
        var retryNeeded = false
        for (id in ids) {
            val envelope = runCatching { graph.transport.fetchRelayMessage(id) }.getOrNull()
            if (envelope == null) {
                retryNeeded = true
                continue
            }
            val outcome = channel.deliver(envelope, DeliveryMode.RELAY_DRAIN)
            if (
                finishRelayDelivery(
                    messageId = id,
                    outcome = outcome,
                    ack = graph.transport::ackRelayMessages,
                    queueAck = store::enqueueAck,
                ) == RelayHandleResult.RETRY
            ) retryNeeded = true
        }

        // 3. Trim dedup/mapping/ack history past its retention window.
        runCatching { store.prune() }
        return if (retryNeeded) Result.retry() else Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "relay-drain"

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
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
