package net.extrawdw.apps.notisync.hotspot.provider

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.notisync.peer.channel.RetryableDeliveryException
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.HotspotAction
import net.extrawdw.notisync.protocol.HotspotSync
import net.extrawdw.notisync.protocol.ProtocolCodec

/** Only authenticated, validated, credential-free requests enter WorkManager's input data. */
class HotspotRequestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sender = inputData.getString("sender")?.let(::ClientId) ?: return Result.success()
        val request = inputData.getByteArray("request")?.let {
            runCatching { ProtocolCodec.decodeFromCbor<HotspotSync>(it) }.getOrNull()
        } ?: return Result.success()
        val createdAt = inputData.getLong("createdAt", 0)
        if (request.action == HotspotAction.STATUS || !request.isValid(System.currentTimeMillis(), createdAt)) return Result.success()
        val graph = (applicationContext as NotiSyncApp).awaitGraphReady() ?: return Result.retry()
        // The provider rechecks trust, screen-sharing permission and expiry after obtaining its operation lock.
        graph.hotspotProvider.executeScheduledRequest(sender, request, createdAt)
        return Result.success()
    }

    companion object {
        internal fun enqueue(context: Context, sender: ClientId, request: HotspotSync, createdAt: Long) {
            require(request.action != HotspotAction.STATUS && request.snapshot == null)
            val work = OneTimeWorkRequestBuilder<HotspotRequestWorker>()
                .setInputData(workDataOf(
                    "sender" to sender.value, "request" to ProtocolCodec.encodeToCbor(request), "createdAt" to createdAt,
                ))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            try {
                // Finish the durable enqueue before SecureChannel marks the message handled/relay-ackable.
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "hotspot-${sender.value}-${request.requestId}", ExistingWorkPolicy.KEEP, work,
                ).result.get(5, TimeUnit.SECONDS)
            } catch (_: Exception) {
                throw RetryableDeliveryException("Hotspot work could not be scheduled")
            }
        }
    }
}
