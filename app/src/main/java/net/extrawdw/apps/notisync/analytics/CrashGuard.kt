package net.extrawdw.apps.notisync.analytics

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Last-resort [CoroutineExceptionHandler] for the app's fire-and-forget scopes (the graph scope, the
 * broadcast-receiver scopes, the iOS bridge service scope). Work launched into them — broker sends, asset
 * repair, trust broadcasts — is best-effort: each launch site guards its own network I/O with
 * `runCatching`, and losing one unit of work is always preferable to losing the process. Without a
 * handler, an exception that slips past a site guard (e.g. a `SocketTimeoutException` out of a
 * broker send) reaches the default handler and kills the app — tearing down the notification
 * listener and the iOS bridge over routine network weather. This demotes such an escape to a log
 * line plus a Crashlytics NON-FATAL, so a missed guard stays diagnosable without costing a restart.
 *
 * Crash-proof itself: the Crashlytics call is guarded for environments without a `FirebaseApp` (JVM
 * unit tests), and recording respects the analytics opt-out (the SDK no-ops when collection is
 * disabled via [AnalyticsController]). Normal cancellation is never delivered to a
 * [CoroutineExceptionHandler], so only real failures land here.
 */
internal fun crashGuard(where: String): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, e ->
        // Each call individually guarded: a handler that throws would itself reach the default
        // handler — exactly what this exists to prevent (and Log isn't mocked on a JVM test runner).
        runCatching { Log.w(TAG, "Uncaught coroutine failure in $where (recorded as non-fatal)", e) }
        runCatching { FirebaseCrashlytics.getInstance().recordException(e) }
    }

private const val TAG = "NotiSyncCrashGuard"
