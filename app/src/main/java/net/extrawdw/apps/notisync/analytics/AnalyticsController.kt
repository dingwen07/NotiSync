package net.extrawdw.apps.notisync.analytics

import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance

/**
 * Single opt-out switch for the app's analytics SDKs: Firebase Crashlytics (crash reporting) and
 * Firebase Performance Monitoring.
 *
 * Both SDKs auto-collect by default — there are no manifest flags disabling them — so this is an
 * *opt-out*: the stored preference (`SettingsRepository.analyticsEnabled`) defaults to enabled and the
 * user can turn collection off in Settings. Each SDK persists its own runtime enabled flag across
 * launches, but we still re-apply the stored preference on every startup so DataStore stays the single
 * source of truth.
 */
object AnalyticsController {
    /** Enable or disable Crashlytics + Performance collection. Idempotent; safe to call on every change. */
    fun apply(enabled: Boolean) {
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled)
        FirebasePerformance.getInstance().isPerformanceCollectionEnabled = enabled
    }
}
