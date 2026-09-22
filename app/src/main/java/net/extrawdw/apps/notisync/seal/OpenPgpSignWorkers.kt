package net.extrawdw.apps.notisync.seal

import android.content.Context
import androidx.annotation.Keep
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
import net.extrawdw.apps.notisync.work.SigningRequestExpiryWorker

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

/** Compatibility entry point for jobs queued by versions before the shared signing expiry worker. */
@Keep
class OpenPgpSignExpiryWorker(context: Context, params: WorkerParameters) : SigningRequestExpiryWorker(context, params)
