package net.extrawdw.apps.notisync.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.work.SigningRequestKind
import net.extrawdw.apps.notisync.work.expireSigningRequests

/** Also handles returning from the background when scheduled expiry was delayed by the OS. */
@Composable
internal fun SigningRequestExpiryEffect(kind: SigningRequestKind) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(graph, kind) {
        val cleanup = scope.launch(Dispatchers.IO) {
            try {
                graph.expireSigningRequests(kind)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("SigningRequestExpiry", "Could not expire $kind requests on page entry", failure)
            }
        }
        onPauseOrDispose { cleanup.cancel() }
    }
}
