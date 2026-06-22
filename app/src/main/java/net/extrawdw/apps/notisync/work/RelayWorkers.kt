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
import net.extrawdw.apps.notisync.transport.DeliveryMode
import java.util.concurrent.TimeUnit

/**
 * Retry an FCM-wake relay fetch out-of-band. The messaging service enqueues this ONLY when its inline
 * fetch failed (e.g. no network in the wake window). Unlike the inline path it survives process death
 * and retries with backoff once the network returns, so an oversized notification still lands rather
 * than waiting for the next app foreground. Delivery is idempotent (the channel dedups by message id).
 */
class WakeFetchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val messageId = inputData.getString(KEY_MESSAGE_ID) ?: return Result.success()
        val graph = (applicationContext as NotiSyncApp).graph
        val channel = graph.secureChannel ?: return Result.retry()
        val envelope = runCatching { graph.transport.fetchRelayMessage(messageId) }.getOrNull()
        return when {
            envelope != null -> {
                channel.deliver(envelope, DeliveryMode.FCM_RELAY_FETCH)
                Result.success()
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
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("wake-fetch-$messageId", ExistingWorkPolicy.KEEP, request)
        }

        private const val BACKOFF_SECONDS = 30L
    }
}

/**
 * Low-frequency catch-all: list whatever is still queued in the broker relay and pull + deliver each.
 * Backs up the FCM wake path for messages FCM deferred (normal-priority DATA_SYNC) or whose wake fetch
 * failed while offline. Deferrable + constrained (connected, battery-not-low) so it is near-free on
 * battery; runs opportunistically in maintenance windows. Idempotent — the channel dedups by id.
 */
class RelayDrainWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as NotiSyncApp).graph
        val channel = graph.secureChannel ?: return Result.success()
        val ids = runCatching { graph.transport.fetchPendingRelayIds() }.getOrElse { return Result.retry() }
        for (id in ids) {
            val envelope = runCatching { graph.transport.fetchRelayMessage(id) }.getOrNull()
            if (envelope != null) channel.deliver(envelope, DeliveryMode.FCM_RELAY_FETCH)
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "relay-drain"
        /** Low frequency by design: the FCM + foreground-WS paths are primary; this only sweeps the tail. */
        private const val INTERVAL_HOURS = 6L

        /** Schedule the periodic backstop. Idempotent (KEEP) — safe to call on every app start. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<RelayDrainWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
