package net.extrawdw.apps.notisync.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import net.extrawdw.apps.notisync.AppGraph
import net.extrawdw.apps.notisync.NotiSyncApp

enum class SigningRequestKind { OPENPGP, SSH }

/** Shared by scheduled work and page entry; store mutations also invalidate the displayed history. */
internal fun AppGraph.expireSigningRequests(kind: SigningRequestKind, now: Long = System.currentTimeMillis()) {
    when (kind) {
        SigningRequestKind.OPENPGP -> openPgpSignStore.expireDue(now).forEach(openPgpSignNotifications::dismiss)
        SigningRequestKind.SSH -> sshKeyProviderStore.expireDue(now).forEach(sshKeyProviderNotifications::dismiss)
    }
}

/** Remains open for the legacy OpenPgpSignExpiryWorker class persisted in WorkManager's database. */
open class SigningRequestExpiryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val requestId = inputData.getString(KEY_REQUEST_ID) ?: return Result.failure()
        // Existing OpenPGP jobs predate the kind field.
        val kind = inputData.getString(KEY_KIND)?.let { value ->
            SigningRequestKind.entries.firstOrNull { it.name == value } ?: return Result.failure()
        } ?: SigningRequestKind.OPENPGP
        val graph = (applicationContext as NotiSyncApp).awaitGraphReady() ?: return Result.retry()
        val deadline = when (kind) {
            SigningRequestKind.OPENPGP -> graph.openPgpSignStore.find(requestId)?.expiryDeadline
            SigningRequestKind.SSH -> graph.sshKeyProviderStore.find(requestId)?.expiryDeadline
        } ?: return Result.success()
        val now = System.currentTimeMillis()
        // A changed wall clock or an OpenPGP response's grace period can move the deadline past this run.
        if (now <= deadline) return Result.retry()
        graph.expireSigningRequests(kind, now)
        return Result.success()
    }

    companion object {
        private const val KEY_REQUEST_ID = "request_id"
        private const val KEY_KIND = "request_kind"

        fun enqueue(context: Context, kind: SigningRequestKind, requestId: String, expiresAt: Long) {
            val request = OneTimeWorkRequestBuilder<SigningRequestExpiryWorker>()
                .setInputData(workDataOf(KEY_REQUEST_ID to requestId, KEY_KIND to kind.name))
                .setInitialDelay((expiresAt - System.currentTimeMillis()).coerceAtLeast(0) + 1, TimeUnit.MILLISECONDS)
                .build()
            val prefix = when (kind) {
                SigningRequestKind.OPENPGP -> "openpgp-sign-expiry"
                SigningRequestKind.SSH -> "ssh-request-expiry"
            }
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$prefix-$requestId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
