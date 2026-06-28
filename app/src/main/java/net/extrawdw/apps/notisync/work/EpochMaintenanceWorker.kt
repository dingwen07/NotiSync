package net.extrawdw.apps.notisync.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.foundation.RotationManager
import java.util.concurrent.TimeUnit

/**
 * Low-frequency, message-independent epoch upkeep. Background delivery is FCM-only and FCM wakes fire ONLY
 * when a notification arrives, so a quiet device would otherwise never make rotation progress — this worker
 * is the time-driven guarantee. Two jobs each run:
 *
 *  1. **Converge** peer key-epochs — pull a rotated / keyless / stripped peer's current key-epoch from the
 *     broker so it stays sealable. Runs regardless of [RotationManager] (an anti-entropy backstop that is
 *     useful even with rotation off, healing peers that missed an E2E announce).
 *  2. **Rotation maintenance** — only when `BuildConfig.ENABLE_ROTATION` built the [RotationManager]:
 *     - **Initiate** a fresh rotation once the live epoch is older than [RotationManager.DEFAULT_ROTATION_INTERVAL_MS]
 *       (14 days) and none is already in flight.
 *     - **Advance** any in-flight rotation across its activation / retirement boundaries via [RotationManager.tick].
 *
 * The worker interval ([INTERVAL_HOURS], daily) is deliberately far shorter than that 14-day *initiation*
 * cadence: `tick()` must land inside the days-long overlap to activate N+1 before N expires, and to retire N
 * (destroying its now-unneeded private keys) shortly after the relay-TTL grace. Inexact, Doze-deferred
 * scheduling only ever makes a boundary fire LATE — the retired epoch's keys are then retained a little
 * longer, never destroyed early — which the overlap absorbs without dropping any in-flight notification.
 *
 * Deferrable + constrained (connected, battery-not-low) so it is near-free on battery.
 */
class EpochMaintenanceWorker(ctx: Context, params: WorkerParameters) :
    CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as NotiSyncApp).awaitGraphReady() ?: return Result.retry()

        // 1. Anti-entropy: pull peers whose key-epoch is missing / expired / stripped (flag-independent).
        runCatching { graph.foundationEngine?.convergeKeyEpochs() }

        // 2. Rotation upkeep — only when the rotation machine was built (ENABLE_ROTATION).
        val rm = graph.rotationManager
        if (rm != null) {
            val activatedAt = runCatching { graph.settings.selfEpochActivatedAt() }.getOrDefault(0L)
            val age = System.currentTimeMillis() - activatedAt
            // Initiate only when the clock is seeded, the live epoch is old enough, and nothing is pending.
            // (beginRotation re-checks pendingRotation internally; this guard just avoids the work + log.)
            if (activatedAt > 0L &&
                age >= RotationManager.DEFAULT_ROTATION_INTERVAL_MS &&
                graph.trust.pendingRotation() == null
            ) {
                runCatching { rm.beginRotation() }
            }
            // Advance an in-flight rotation across its next boundary (idempotent; a no-op until then).
            runCatching { rm.tick() }
            // Forward-secrecy backstop: guarantee retired private keys are gone even if a retirement's delete
            // was skipped (offline at the boundary) or silently failed. No-op while a rotation is pending.
            runCatching { graph.gcStaleEpochs() }
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "epoch-maintenance"

        /** Daily — short enough that tick() always lands well inside the days-long overlap / grace windows. */
        private const val INTERVAL_HOURS = 24L

        /** Schedule the periodic upkeep. Idempotent (KEEP) — safe to call on every app start. */
        fun schedulePeriodic(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<EpochMaintenanceWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
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
