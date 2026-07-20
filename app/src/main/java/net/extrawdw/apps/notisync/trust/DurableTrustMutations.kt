package net.extrawdw.apps.notisync.trust

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serializes Android-initiated trust writes on an I/O dispatcher.
 *
 * The peer trust store deliberately keeps a synchronous durable-before-return API because relay delivery
 * must not be acknowledged before DataStore and Keystore work completes. Android UI callers must not pay
 * that cost on the main thread, however. This coordinator is owned by the application graph, so a
 * navigation/recomposition cannot cancel an already-confirmed trust decision.
 */
internal class DurableTrustMutations(
    private val applicationScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val callbackDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val reportFailure: (Throwable) -> Unit = { failure ->
        Log.e(TAG, "Durable trust mutation failed", failure)
    },
) {
    private val mutex = Mutex()

    /** Run a trust mutation durably and serially. Exceptions are returned to the caller. */
    suspend fun <T> run(mutation: () -> T): T = withContext(ioDispatcher) {
        mutex.withLock { mutation() }
    }

    /**
     * Launch from the application lifetime. Persistence failures are contained and delivered on the main
     * dispatcher so a Compose caller can safely show feedback. Cancellation still follows structured
     * coroutine semantics.
     */
    fun launch(
        onFailure: (Throwable) -> Unit = {},
        mutation: () -> Unit,
    ): Job = applicationScope.launch {
        try {
            run(mutation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            reportFailure(failure)
            withContext(callbackDispatcher) { onFailure(failure) }
        }
    }

    private companion object {
        const val TAG = "NotiSyncTrust"
    }
}
