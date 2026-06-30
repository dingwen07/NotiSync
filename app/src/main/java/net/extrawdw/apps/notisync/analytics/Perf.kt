package net.extrawdw.apps.notisync.analytics

import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.perf.metrics.Trace

/**
 * Crash-proof, opt-out-aware wrapper around a Firebase Performance custom [Trace].
 *
 * Every Firebase call is guarded with [runCatching] so the instrumentation degrades to a no-op rather
 * than throwing when Firebase is unavailable — chiefly the JVM unit tests that exercise [SecureChannel]
 * and the asset/transport layer without a `FirebaseApp`, where `FirebasePerformance.getInstance()` would
 * otherwise raise `IllegalStateException`. When the user turns collection off via [AnalyticsController]
 * the SDK itself makes these traces no-ops, so call sites never gate on the analytics preference.
 *
 * Mind Firebase's limits: ≤5 custom attributes per trace (keys ≤40 / values ≤100 chars) and ≤32 metrics.
 * Keep attribute values LOW-cardinality (bucket ids/counts; never put a raw clientId/messageId/timestamp
 * in an attribute) — they become filter dimensions in the console.
 */
class PerfSpan internal constructor(private val trace: Trace?) {
    /** Tag this span with a low-cardinality dimension to filter/segment by in the console. */
    fun attr(key: String, value: String) {
        trace?.let { runCatching { it.putAttribute(key, value) } }
    }

    /** Record a numeric metric — a duration in ms, a count, a byte size, … */
    fun metric(name: String, value: Long) {
        trace?.let { runCatching { it.putMetric(name, value) } }
    }

    internal fun stop() {
        trace?.let { runCatching { it.stop() } }
    }
}

/**
 * Start a custom trace, returning a started [PerfSpan] (a no-op span if Firebase is unavailable). The
 * caller MUST eventually call [PerfSpan.stop]; prefer [perfTrace] for a scoped block, and use this raw
 * form only when start/stop must straddle control flow a lambda can't express (e.g. stopping a span
 * inside a long-lived loop, or threading one through a finally with other teardown).
 */
internal fun perfSpan(name: String): PerfSpan =
    PerfSpan(
        runCatching { FirebasePerformance.getInstance().newTrace(name).also { it.start() } }.getOrNull()
    )

/** Run [block] inside a custom trace, stopping it even if [block] throws or returns non-locally. */
internal inline fun <T> perfTrace(name: String, block: (PerfSpan) -> T): T {
    val span = perfSpan(name)
    return try {
        block(span)
    } finally {
        span.stop()
    }
}
