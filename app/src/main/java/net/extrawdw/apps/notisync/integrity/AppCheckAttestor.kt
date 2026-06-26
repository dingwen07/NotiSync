package net.extrawdw.apps.notisync.integrity

import com.google.android.gms.tasks.Task
import com.google.firebase.appcheck.FirebaseAppCheck
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Obtains a Firebase App Check token attesting this is a genuine, unmodified NotiSync build on a genuine
 * device. App Check uses Play Integrity internally — handled entirely by the Firebase SDK; we never call the
 * Play Integrity API or carry a cloud project number ourselves. The broker verifies the token locally
 * against the App Check JWKS. The provider factory is variant-specific (Play Integrity in release, the debug
 * provider in debug builds) — see the `appCheckProviderFactory()` in src/release and src/debug.
 */
class AppCheckAttestor {
    private val appCheck = FirebaseAppCheck.getInstance().apply {
        installAppCheckProviderFactory(appCheckProviderFactory())
        setTokenAutoRefreshEnabled(true)
    }

    /** A current App Check token (the SDK caches and auto-refreshes it). Throws on failure. */
    suspend fun token(): String = appCheck.getAppCheckToken(false).await().token
}

private suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { value -> if (cont.isActive) cont.resume(value) }
        addOnFailureListener { error -> if (cont.isActive) cont.resumeWithException(error) }
        addOnCanceledListener { cont.cancel() }
    }
