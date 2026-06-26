package net.extrawdw.notisync.server

import net.extrawdw.notisync.protocol.AttestationType
import net.extrawdw.notisync.protocol.ClientId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttestationMetricsTest {
    private val cid = ClientId("metrics-test-client")

    @Test
    fun aggregatesPerMethodAndBucketWithVerdictTallies() {
        val m = AttestationMetrics(bucketMillis = 1000, retainBuckets = 10, recentLogSize = 10)
        val t = 1_000_000L // aligned to a 1000ms bucket
        m.record(
            AttestationType.PLAY_INTEGRITY, cid,
            IntegrityDecision.Accepted(
                debugBypass = true,
                detail = VerificationDetail(
                    appLicensingVerdict = "UNEVALUATED", appRecognitionVerdict = "UNRECOGNIZED_VERSION",
                    deviceRecognitionVerdict = listOf("MEETS_DEVICE_INTEGRITY"),
                    deviceActivityLevel = "LEVEL_1", playProtectVerdict = "UNEVALUATED",
                ),
            ),
            nowMillis = t,
        )
        m.record(
            AttestationType.PLAY_INTEGRITY, cid,
            IntegrityDecision.Rejected(
                "bad_appLicensingVerdict",
                detail = VerificationDetail(appLicensingVerdict = "UNEVALUATED", appRecognitionVerdict = "UNRECOGNIZED_VERSION"),
            ),
            nowMillis = t + 10,
        )
        m.record(
            AttestationType.FIREBASE_APP_CHECK, cid,
            IntegrityDecision.Accepted(debugBypass = false, detail = VerificationDetail(appId = "1:873059270463:android:b74")),
            nowMillis = t + 20,
        )

        val snap = m.snapshot(nowMillis = t + 30)
        assertEquals(1, snap.buckets.size)
        val bucket = snap.buckets.single()
        assertEquals(t, bucket.start)

        val pi = bucket.methods.getValue(AttestationType.PLAY_INTEGRITY)
        assertEquals(1, pi.accepted)
        assertEquals(1, pi.rejected)
        assertEquals(1, pi.debugBypass)
        assertEquals(1, pi.rejectReasons.getValue("bad_appLicensingVerdict"))
        // both the accept and the reject carried appRecognition=UNRECOGNIZED_VERSION
        assertEquals(2, pi.verdicts.getValue("appRecognition").getValue("UNRECOGNIZED_VERSION"))
        assertEquals(1, pi.verdicts.getValue("deviceRecognition").getValue("MEETS_DEVICE_INTEGRITY"))

        val ac = bucket.methods.getValue(AttestationType.FIREBASE_APP_CHECK)
        assertEquals(1, ac.accepted)
        assertEquals(1, ac.apps.getValue("1:873059270463:android:b74"))
        assertTrue("App Check exposes no device verdicts", ac.verdicts.isEmpty())

        assertEquals(3, snap.recent.size)
        assertEquals("recent is newest-first", AttestationType.FIREBASE_APP_CHECK, snap.recent.first().method)
    }

    @Test
    fun prunesOldBucketsAndCapsRecentRing() {
        val m = AttestationMetrics(bucketMillis = 1000, retainBuckets = 2, recentLogSize = 3)
        for (i in 0 until 5) {
            m.record(AttestationType.PLAY_INTEGRITY, cid, IntegrityDecision.Accepted(debugBypass = false), nowMillis = i * 1000L)
        }
        val snap = m.snapshot(nowMillis = 5000L)
        assertTrue("retains at most 2 buckets", snap.buckets.size <= 2)
        assertEquals("recent capped at 3", 3, snap.recent.size)
    }
}
