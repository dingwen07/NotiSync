package net.extrawdw.apps.notisync.sshkeyprovider

import net.extrawdw.notisync.protocol.ClientId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshRequestUiTest {
    @Test
    fun cancelledRequestRendersAsTerminalAndCannotKeepApprovalActions() {
        val cancelled = stored(
            state = SshProviderRequestState.CANCELLED,
            outcome = SshProviderRequestOutcome.CANCELLED,
        )

        assertEquals(SshRequestDisplayStatus.CANCELED, cancelled.displayStatus())
        assertFalse(cancelled.isActiveRequest())
    }

    @Test
    fun pendingRequestRemainsActionable() {
        val pending = stored(state = SshProviderRequestState.PENDING_REVIEW)

        assertEquals(SshRequestDisplayStatus.WAITING, pending.displayStatus())
        assertTrue(pending.isActiveRequest())
    }

    @Test
    fun onlyAutoOpenedCancellationAndExpiryCloseTheReviewTask() {
        val cancelled = stored(SshProviderRequestState.SENT, SshProviderRequestOutcome.CANCELLED)
        val expired = stored(SshProviderRequestState.SENT, SshProviderRequestOutcome.EXPIRED)
        val rejected = stored(SshProviderRequestState.SENT, SshProviderRequestOutcome.REJECTED)

        assertTrue(cancelled.shouldCloseAutoOpenedReview(autoLaunchOwned = true))
        assertTrue(expired.shouldCloseAutoOpenedReview(autoLaunchOwned = true))
        assertFalse(cancelled.shouldCloseAutoOpenedReview(autoLaunchOwned = false))
        assertFalse(expired.shouldCloseAutoOpenedReview(autoLaunchOwned = false))
        assertFalse(rejected.shouldCloseAutoOpenedReview(autoLaunchOwned = true))
    }

    private fun stored(
        state: SshProviderRequestState,
        outcome: SshProviderRequestOutcome? = null,
    ) = StoredSshProviderRequest(
        requestId = "1".repeat(32),
        kind = SshProviderRequestKind.SIGN,
        requesterClientId = ClientId("desktop"),
        requestFingerprint = ByteArray(32),
        history = SshRequestHistorySnapshot(
            requestedAt = 1_000,
            expiresAt = 2_000,
            payloadSize = 0,
        ),
        state = state,
        outcome = outcome,
        updatedAt = 1_500,
    )
}
