package net.extrawdw.apps.notisync.sign

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.notisync.protocol.OpenPgpSignLimits

class OpenPgpSignResponseWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val requestId = inputData.getString(KEY_REQUEST_ID) ?: return Result.failure()
        val graph = (applicationContext as NotiSyncApp).awaitGraphReady() ?: return Result.retry()
        val engine = graph.openPgpSignEngine ?: return Result.retry()
        return if (runCatching { engine.sendPersistedResponse(requestId) }.getOrDefault(false)) {
            Result.success()
        } else Result.retry()
    }

    companion object {
        private const val KEY_REQUEST_ID = "request_id"

        fun enqueue(context: Context, requestId: String) {
            val request = OneTimeWorkRequestBuilder<OpenPgpSignResponseWorker>()
                .setInputData(workDataOf(KEY_REQUEST_ID to requestId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "openpgp-sign-response-$requestId",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}

class OpenPgpSignExpiryWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val requestId = inputData.getString(KEY_REQUEST_ID) ?: return Result.failure()
        val graph = (applicationContext as NotiSyncApp).awaitGraphReady() ?: return Result.retry()
        val stored = graph.openPgpSignStore.find(requestId) ?: return Result.success()
        val now = System.currentTimeMillis()
        val expiryDeadline = if (stored.state in OpenPgpSignEngine.OUTBOX_STATES) {
            stored.request.expiresAt + OpenPgpSignLimits.CLOCK_SKEW_MILLIS
        } else stored.request.expiresAt
        if (now < expiryDeadline) {
            enqueue(applicationContext, requestId, expiryDeadline)
            return Result.success()
        }
        if (graph.openPgpSignStore.markExpired(requestId, now)) {
            OpenPgpSignNotificationPresenter(applicationContext).dismiss(requestId)
        }
        return Result.success()
    }

    companion object {
        private const val KEY_REQUEST_ID = "request_id"

        fun enqueue(context: Context, requestId: String, expiresAt: Long) {
            val delay = (expiresAt - System.currentTimeMillis()).coerceAtLeast(0)
            val request = OneTimeWorkRequestBuilder<OpenPgpSignExpiryWorker>()
                .setInputData(workDataOf(KEY_REQUEST_ID to requestId))
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "openpgp-sign-expiry-$requestId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
