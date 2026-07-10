package net.extrawdw.apps.notisync.domain

import net.extrawdw.notisync.protocol.CallType
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.MirrorCategory
import net.extrawdw.notisync.protocol.OriginPlatform
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallMirrorPolicyTest {
    private fun capture(
        callType: CallType? = null,
        category: MirrorCategory = MirrorCategory.NONE,
        originPlatform: OriginPlatform = OriginPlatform.ANDROID_LOCAL,
        isForegroundService: Boolean = false,
        postTime: Long = 1_000L,
    ) = CapturedNotification(
        sourceClientId = ClientId("source-device"),
        sourceKey = "call-key",
        packageName = "com.example.calls",
        appLabel = "Example Calls",
        postTime = postTime,
        callType = callType,
        category = category,
        originPlatform = originPlatform,
        isForegroundService = isForegroundService,
    )

    @Test
    fun callStyleTypeDecidesRinging() {
        assertTrue(capture(callType = CallType.INCOMING).isRingingCall())
        assertTrue(capture(callType = CallType.SCREENING).isRingingCall())
        assertFalse(capture(callType = CallType.ONGOING).isRingingCall())
    }

    @Test
    fun categoryCallWithoutStyleUsesForegroundServiceProxy() {
        assertTrue(capture(category = MirrorCategory.CALL).isRingingCall())
        assertFalse(capture(category = MirrorCategory.CALL, isForegroundService = true).isRingingCall())
    }

    @Test
    fun ancsCategoryCallNeverUsesTheProxy() {
        // An ANCS CALL category also covers missed calls / voicemail; only an explicit INCOMING type rings.
        assertFalse(capture(category = MirrorCategory.CALL, originPlatform = OriginPlatform.IOS_ANCS).isRingingCall())
        assertTrue(
            capture(callType = CallType.INCOMING, originPlatform = OriginPlatform.IOS_ANCS).isRingingCall()
        )
    }

    @Test
    fun nonCallCapturesNeverRing() {
        assertFalse(capture().isRingingCall())
        assertFalse(capture(category = MirrorCategory.MESSAGE).isRingingCall())
    }

    @Test
    fun freshnessIsBoundedByStaleWindowAndToleratesAheadClocks() {
        val posted = 100_000L
        val call = capture(postTime = posted)
        assertTrue(call.isFreshCall(nowMs = posted))
        assertTrue(call.isFreshCall(nowMs = posted + STALE_CALL_RING_MS))
        assertFalse(call.isFreshCall(nowMs = posted + STALE_CALL_RING_MS + 1))
        // Source clock slightly ahead of ours: negative age still counts as fresh.
        assertTrue(call.isFreshCall(nowMs = posted - 5_000L))
    }

    @Test
    fun quietRenderPromotesOnlyFirstAppliedFreshRingingCalls() {
        val now = 50_000L
        val ringing = capture(callType = CallType.INCOMING, postTime = now - 1_000L)
        // Never-applied key + fresh ringing call: the alerting post lost the race — promote.
        assertTrue(promoteQuietRenderToAlert(firstApplied = true, notif = ringing, nowMs = now))
        // Already applied before (shown, possibly since dismissed): stay a silent in-place update.
        assertFalse(promoteQuietRenderToAlert(firstApplied = false, notif = ringing, nowMs = now))
        // Stale ringing call (late relay/backlog): never promote to an alert.
        val stale = capture(callType = CallType.INCOMING, postTime = now - STALE_CALL_RING_MS - 1)
        assertFalse(promoteQuietRenderToAlert(firstApplied = true, notif = stale, nowMs = now))
        // Non-ringing states (answered/ongoing, media, progress) always apply silently.
        val ongoing = capture(callType = CallType.ONGOING, postTime = now)
        assertFalse(promoteQuietRenderToAlert(firstApplied = true, notif = ongoing, nowMs = now))
    }
}
