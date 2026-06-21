package net.extrawdw.apps.notisync.integrity

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PlayIntegrityAttestor(
    context: Context,
    private val cloudProjectNumber: Long,
) {
    private val manager = IntegrityManagerFactory.createStandard(context.applicationContext)
    private val mutex = Mutex()

    @Volatile
    private var provider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null

    suspend fun requestToken(requestHash: String): String {
        require(cloudProjectNumber > 0) { "PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER is not configured" }
        val prepared = provider ?: prepareProvider()
        return runCatching { prepared.requestToken(requestHash) }.getOrElse {
            provider = null
            prepareProvider().requestToken(requestHash)
        }
    }

    private suspend fun prepareProvider(): StandardIntegrityManager.StandardIntegrityTokenProvider =
        mutex.withLock {
            provider ?: manager.prepareIntegrityToken(
                StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                    .setCloudProjectNumber(cloudProjectNumber)
                    .build()
            ).await().also { provider = it }
        }

    private suspend fun StandardIntegrityManager.StandardIntegrityTokenProvider.requestToken(requestHash: String): String =
        request(
            StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                .setRequestHash(requestHash)
                .build()
        ).await().token()
}

private suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { value -> if (cont.isActive) cont.resume(value) }
        addOnFailureListener { error -> if (cont.isActive) cont.resumeWithException(error) }
        addOnCanceledListener { cont.cancel() }
    }
