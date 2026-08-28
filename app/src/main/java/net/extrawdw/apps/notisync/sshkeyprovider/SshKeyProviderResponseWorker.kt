package net.extrawdw.apps.notisync.sshkeyprovider

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.extrawdw.apps.notisync.NotiSyncApp

class SshKeyProviderResponseWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val requestId = inputData.getString(KEY_REQUEST_ID) ?: return Result.failure()
        val graph = (applicationContext as? NotiSyncApp)?.awaitGraphReady() ?: return Result.retry()
        val engine = graph.sshKeyProviderEngine ?: return Result.retry()
        return if (runCatching { engine.sendPersistedResponse(requestId) }.getOrDefault(false)) {
            Result.success()
        } else Result.retry()
    }

    companion object {
        private const val KEY_REQUEST_ID = "ssh_agent_request_id"
        fun enqueue(context: Context, requestId: String) {
            val request = OneTimeWorkRequestBuilder<SshKeyProviderResponseWorker>()
                .setInputData(Data.Builder().putString(KEY_REQUEST_ID, requestId).build())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "ssh-agent-response-$requestId",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
