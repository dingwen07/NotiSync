package net.extrawdw.apps.notisync.run

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.notisync.protocol.TrustStatus

/** Durable notification-action drain. Failed controls remain in SQLite and retry with their original request id. */
class RunControlDrainWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as NotiSyncApp).awaitGraphReady() ?: return Result.retry()
        val engine = graph.runEngine ?: return Result.retry()
        val outbox = RunControlOutbox(applicationContext)
        return try {
            val roster = graph.trust.roster.value.associateBy { it.clientId }
            if (
                RunControlOutboxDrainer.drain(
                    queue = outbox,
                    classify = { control ->
                        val host = roster[control.hostClientId]
                        when {
                            host == null || !host.ownDevice || host.status == TrustStatus.REVOKED ->
                                QueuedRunControlDisposition.DROP
                            host.status == TrustStatus.TRUSTED -> QueuedRunControlDisposition.SEND
                            else -> QueuedRunControlDisposition.RETAIN
                        }
                    },
                    send = engine::sendPersistedControl,
                )
            ) {
                Result.success()
            } else Result.retry()
        } finally {
            outbox.close()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "run-control-outbox"
        private const val BACKOFF_SECONDS = 30L

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<RunControlDrainWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            // Every durable enqueue appends a drain behind any running/retrying drain, closing the race where a
            // KEEP worker observes an empty queue immediately before a new control commits.
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
