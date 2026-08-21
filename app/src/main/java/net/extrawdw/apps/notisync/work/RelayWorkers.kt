package net.extrawdw.apps.notisync.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import net.extrawdw.apps.notisync.data.relay.RelayOperationalContinuity
import net.extrawdw.apps.notisync.messaging.inbound.InboundAcknowledgementResult
import net.extrawdw.apps.notisync.messaging.inbound.InboundCoordinatorResult
import net.extrawdw.apps.notisync.messaging.inbound.RelayBatchDrainCoordinator
import net.extrawdw.apps.notisync.messaging.inbound.RelayBatchDrainResult
import net.extrawdw.apps.notisync.messaging.inbound.RelayExactFetchResult
import net.extrawdw.apps.notisync.messaging.inbound.SerializedDirectInboundProcessor

internal enum class RelayWorkerExecutionResult {
    COMPLETE,
    RETRY_REQUIRED,
}

/** Narrow broker-custody surface; callers never own message custody or ACK state. */
internal interface RelayWorkerRuntime {
    suspend fun fetchAndProcessExact(messageId: String): RelayWorkerExecutionResult
    suspend fun drainFiniteBatch(): RelayWorkerExecutionResult
}

internal sealed interface RelayWorkerRuntimeAvailability {
    data class Ready(val runtime: RelayWorkerRuntime) : RelayWorkerRuntimeAvailability
    /**
     * Existing-authority initialization found no usable local authority. The broker still owns every
     * unacknowledged message, so a worker must stop without creating a WorkManager retry lifecycle. A successful
     * user-started initialization schedules a fresh backlog drain.
     */
    data object Unavailable : RelayWorkerRuntimeAvailability
}

/** Application composition implements this without exposing AppGraph, Room, or transport types to WorkManager. */
internal fun interface RelayWorkerRuntimeProvider {
    suspend fun relayWorkerRuntimeAvailability(): RelayWorkerRuntimeAvailability
}

internal fun interface RelayWorkerContinuityPort {
    suspend fun current(): RelayOperationalContinuity?
}

internal fun interface RelayWorkerExactFetchPort {
    suspend fun fetch(
        messageId: String,
        continuity: RelayOperationalContinuity,
    ): RelayExactFetchResult
}

/** Storage/wire-independent runtime shared by direct FCM fetches and the periodic broker drain. */
internal class BrokerCustodyRelayWorkerRuntime(
    private val continuity: RelayWorkerContinuityPort,
    private val exactSource: RelayWorkerExactFetchPort,
    private val direct: SerializedDirectInboundProcessor,
    private val batch: RelayBatchDrainCoordinator,
) : RelayWorkerRuntime {
    override suspend fun fetchAndProcessExact(messageId: String): RelayWorkerExecutionResult {
        val capturedContinuity = continuity.current() ?: return RelayWorkerExecutionResult.RETRY_REQUIRED
        return when (val fetched = exactSource.fetch(messageId, capturedContinuity)) {
            RelayExactFetchResult.Missing -> RelayWorkerExecutionResult.COMPLETE
            RelayExactFetchResult.RetryRequired -> RelayWorkerExecutionResult.RETRY_REQUIRED
            is RelayExactFetchResult.Found -> {
                if (
                    fetched.arrival.messageId != messageId ||
                    fetched.arrival.continuity != capturedContinuity
                ) return RelayWorkerExecutionResult.RETRY_REQUIRED
                val result = direct.process(fetched.arrival)
                if (
                    result.processing is InboundCoordinatorResult.AcknowledgementPending &&
                    result.acknowledgement == InboundAcknowledgementResult.Acknowledged
                ) {
                    RelayWorkerExecutionResult.COMPLETE
                } else {
                    RelayWorkerExecutionResult.RETRY_REQUIRED
                }
            }
        }
    }

    override suspend fun drainFiniteBatch(): RelayWorkerExecutionResult {
        val capturedContinuity = continuity.current() ?: return RelayWorkerExecutionResult.RETRY_REQUIRED
        return when (batch.drain(capturedContinuity)) {
            RelayBatchDrainResult.COMPLETE -> RelayWorkerExecutionResult.COMPLETE
            RelayBatchDrainResult.RETRY_REQUIRED -> RelayWorkerExecutionResult.RETRY_REQUIRED
        }
    }
}

private suspend fun Context.relayWorkerRuntimeAvailability(): RelayWorkerRuntimeAvailability =
    (applicationContext as? RelayWorkerRuntimeProvider)?.relayWorkerRuntimeAvailability()
        ?: RelayWorkerRuntimeAvailability.Unavailable

/** Normal and periodic broker drain. This is the backlog safety net, not the FCM delivery path. */
class RelayDrainWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withRuntime(RelayWorkerRuntime::drainFiniteBatch)

    companion object {
        private const val PERIODIC_UNIQUE_NAME = "relay-drain-periodic"
        private const val NORMAL_UNIQUE_NAME = "relay-drain-normal"
        private const val INTERVAL_HOURS = 6L
        private const val BACKOFF_SECONDS = 30L

        fun enqueueNormal(context: Context, initialDelayMillis: Long = 0L) {
            require(initialDelayMillis >= 0) { "relay drain delay must not be negative" }
            val request = OneTimeWorkRequestBuilder<RelayDrainWorker>()
                .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
                .setConstraints(connectedConstraint())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                NORMAL_UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<RelayDrainWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

    }
}

private suspend fun CoroutineWorker.withRuntime(
    operation: suspend (RelayWorkerRuntime) -> RelayWorkerExecutionResult,
): ListenableWorker.Result = when (val availability = applicationContext.relayWorkerRuntimeAvailability()) {
    is RelayWorkerRuntimeAvailability.Ready -> execute { operation(availability.runtime) }
    // The provider has already waited for existing-authority initialization. A genuinely unavailable
    // process must not start migration; Ready publication after user initialization schedules a new drain.
    RelayWorkerRuntimeAvailability.Unavailable -> ListenableWorker.Result.success()
}

private suspend fun CoroutineWorker.execute(
    operation: suspend () -> RelayWorkerExecutionResult,
): ListenableWorker.Result = try {
    when (operation()) {
        RelayWorkerExecutionResult.COMPLETE -> ListenableWorker.Result.success()
        RelayWorkerExecutionResult.RETRY_REQUIRED -> ListenableWorker.Result.retry()
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    ListenableWorker.Result.retry()
}

private fun connectedConstraint(): Constraints =
    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
