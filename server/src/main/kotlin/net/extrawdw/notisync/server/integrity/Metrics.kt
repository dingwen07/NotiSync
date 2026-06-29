package net.extrawdw.notisync.server.integrity

import kotlinx.serialization.Serializable
import net.extrawdw.notisync.protocol.ClientId

/**
 * Method-specific data captured for the /v2/metrics endpoint, carried on [IntegrityDecision]. The live
 * method, **App Check**, fills only [appId] — it exposes no device verdicts to a custom backend (Firebase
 * enforces those at token issuance). The verdict fields are legacy (the retired Play Integrity path filled
 * them); no current verifier populates them, but they're retained so historical metrics still deserialize.
 */
data class VerificationDetail(
    val appLicensingVerdict: String? = null,
    val appRecognitionVerdict: String? = null,
    val deviceRecognitionVerdict: List<String> = emptyList(),
    val deviceActivityLevel: String? = null,
    val playProtectVerdict: String? = null,
    val appId: String? = null,
)

/**
 * In-memory attestation metrics for the /v2/metrics diagnostics endpoint: per-[bucketMillis] aggregates
 * (accept/reject counts, reject reasons, debug-bypass count, and any per-method verdict tallies) plus a
 * recent-events ring buffer.
 *
 * Purpose: diagnose attestation health — e.g. watch App Check accepts climb (and, later, observe Apple App
 * Attest the same way) before flipping `NOTISYNC_INTEGRITY_REQUIRED` on. State is in-memory and resets on
 * restart — scrape the endpoint periodically (Prometheus / a cron file) for long-term history.
 */
class AttestationMetrics(
    private val bucketMillis: Long = 30 * 60 * 1000L,
    private val retainBuckets: Int = 7 * 48,
    private val recentLogSize: Int = 200,
) {
    private class Agg {
        var accepted = 0
        var rejected = 0
        var debugBypass = 0
        val rejectReasons = LinkedHashMap<String, Int>()
        val verdicts = LinkedHashMap<String, LinkedHashMap<String, Int>>()
        val apps = LinkedHashMap<String, Int>()
    }

    private val buckets = LinkedHashMap<Long, LinkedHashMap<String, Agg>>()
    private val recent = ArrayDeque<MetricsLogEntry>()

    @Synchronized
    fun record(
        method: String,
        clientId: ClientId,
        decision: IntegrityDecision,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val bucketStart = nowMillis - (nowMillis % bucketMillis)
        val agg = buckets.getOrPut(bucketStart) { LinkedHashMap() }.getOrPut(method) { Agg() }

        val debugBypass = (decision as? IntegrityDecision.Accepted)?.debugBypass ?: false
        when (decision) {
            is IntegrityDecision.Accepted -> {
                agg.accepted++
                if (debugBypass) agg.debugBypass++
            }
            is IntegrityDecision.Rejected -> {
                agg.rejected++
                agg.rejectReasons.merge(decision.reason, 1, Int::plus)
            }
        }
        decision.detail?.let { d ->
            d.appLicensingVerdict?.let { agg.verdicts.tally("appLicensing", it) }
            d.appRecognitionVerdict?.let { agg.verdicts.tally("appRecognition", it) }
            d.deviceRecognitionVerdict.forEach { agg.verdicts.tally("deviceRecognition", it) }
            d.deviceActivityLevel?.let { agg.verdicts.tally("deviceActivity", it) }
            d.playProtectVerdict?.let { agg.verdicts.tally("playProtect", it) }
            d.appId?.let { agg.apps.merge(it, 1, Int::plus) }
        }
        while (buckets.size > retainBuckets) buckets.remove(buckets.keys.first())

        recent.addLast(
            MetricsLogEntry(
                at = nowMillis,
                method = method,
                client = clientId.shortForm(),
                accepted = decision is IntegrityDecision.Accepted,
                reason = (decision as? IntegrityDecision.Rejected)?.reason ?: "accepted",
                debugBypass = debugBypass,
                detail = decision.detail?.toLogMap() ?: emptyMap(),
            )
        )
        while (recent.size > recentLogSize) recent.removeFirst()
    }

    @Synchronized
    fun snapshot(nowMillis: Long = System.currentTimeMillis()): MetricsSnapshot = MetricsSnapshot(
        now = nowMillis,
        bucketMillis = bucketMillis,
        retainedBuckets = retainBuckets,
        buckets = buckets.entries.sortedBy { it.key }.map { (start, methods) ->
            MetricsBucket(
                start = start,
                methods = methods.mapValues { (_, a) ->
                    MethodStats(
                        accepted = a.accepted,
                        rejected = a.rejected,
                        debugBypass = a.debugBypass,
                        rejectReasons = a.rejectReasons.toMap(),
                        verdicts = a.verdicts.mapValues { it.value.toMap() },
                        apps = a.apps.toMap(),
                    )
                },
            )
        },
        recent = recent.reversed(),
    )

    private fun LinkedHashMap<String, LinkedHashMap<String, Int>>.tally(dimension: String, value: String) {
        getOrPut(dimension) { LinkedHashMap() }.merge(value, 1, Int::plus)
    }
}

private fun VerificationDetail.toLogMap(): Map<String, String> = buildMap {
    appLicensingVerdict?.let { put("appLicensing", it) }
    appRecognitionVerdict?.let { put("appRecognition", it) }
    if (deviceRecognitionVerdict.isNotEmpty()) put("deviceRecognition", deviceRecognitionVerdict.joinToString(","))
    deviceActivityLevel?.let { put("deviceActivity", it) }
    playProtectVerdict?.let { put("playProtect", it) }
    appId?.let { put("appId", it) }
}

/** GET /v2/metrics response: per-bucket aggregates (oldest→newest) + the recent-events ring (newest→oldest). */
@Serializable
data class MetricsSnapshot(
    val now: Long,
    val bucketMillis: Long,
    val retainedBuckets: Int,
    val buckets: List<MetricsBucket>,
    val recent: List<MetricsLogEntry>,
)

@Serializable
data class MetricsBucket(
    /** Bucket start, epoch millis (aligned to [MetricsSnapshot.bucketMillis]). */
    val start: Long,
    /** Per attestation method (see AttestationType): firebaseAppCheck / … (legacy playIntegrity for history). */
    val methods: Map<String, MethodStats>,
)

@Serializable
data class MethodStats(
    val accepted: Int,
    val rejected: Int,
    val debugBypass: Int,
    val rejectReasons: Map<String, Int>,
    /** Legacy (retired Play Integrity): verdict dimension → label → count. Empty for the App Check method. */
    val verdicts: Map<String, Map<String, Int>>,
    /** App Check only: appId (token `sub`) → count. */
    val apps: Map<String, Int>,
)

@Serializable
data class MetricsLogEntry(
    val at: Long,
    val method: String,
    /** Short, non-reversible client fingerprint (same form used in the broker logs). */
    val client: String,
    val accepted: Boolean,
    val reason: String,
    val debugBypass: Boolean,
    val detail: Map<String, String>,
)
