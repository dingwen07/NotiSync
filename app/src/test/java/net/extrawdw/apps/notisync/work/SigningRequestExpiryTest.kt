package net.extrawdw.apps.notisync.work

import net.extrawdw.apps.notisync.seal.OpenPgpRequestState
import net.extrawdw.apps.notisync.seal.StoredOpenPgpRequest
import net.extrawdw.apps.notisync.sshkeyprovider.SshProviderRequestKind
import net.extrawdw.apps.notisync.sshkeyprovider.SshProviderRequestState
import net.extrawdw.apps.notisync.sshkeyprovider.SshRequestHistorySnapshot
import net.extrawdw.apps.notisync.sshkeyprovider.StoredSshProviderRequest
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.OpenPgpObjectKind
import net.extrawdw.notisync.protocol.OpenPgpSignAction
import net.extrawdw.notisync.protocol.OpenPgpSignLimits
import net.extrawdw.notisync.protocol.OpenPgpSignSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SigningRequestExpiryTest {
    @Test
    fun sshHistoryRetainsTheDeadlineWithoutDecodingTheActivePayload() {
        for (kind in SshProviderRequestKind.entries) {
            val stored = ssh(kind, SshProviderRequestState.PENDING_REVIEW)
            assertNull(stored.signRequest)
            assertNull(stored.importRequest)
            assertEquals(2_000L, stored.expiryDeadline)
        }
    }

    @Test
    fun sshResponsesAndTerminalHistoryAreNeverExpiredByReviewCleanup() {
        for (state in SshProviderRequestState.entries - SshProviderRequestState.PENDING_REVIEW) {
            assertNull(state.name, ssh(SshProviderRequestKind.SIGN, state).expiryDeadline)
        }
    }

    @Test
    fun openPgpApprovalAndProviderInteractionKeepTheOriginalDeadline() {
        for (state in listOf(
            OpenPgpRequestState.PENDING_REVIEW,
            OpenPgpRequestState.USER_APPROVED,
            OpenPgpRequestState.PROVIDER_INTERACTION,
        )) {
            assertEquals(state.name, 2_000L, openPgp(state).expiryDeadline)
        }
    }

    @Test
    fun openPgpResponsesKeepTheSendGracePeriodAndTerminalHistoryHasNoDeadline() {
        for (state in listOf(OpenPgpRequestState.SIGNED_PENDING_SEND, OpenPgpRequestState.REJECTED_PENDING_SEND)) {
            assertEquals(state.name, 2_000L + OpenPgpSignLimits.CLOCK_SKEW_MILLIS, openPgp(state).expiryDeadline)
        }
        for (state in listOf(
            OpenPgpRequestState.SENT,
            OpenPgpRequestState.CANCELLED,
            OpenPgpRequestState.EXPIRED,
            OpenPgpRequestState.FAILED,
        )) {
            assertNull(state.name, openPgp(state).expiryDeadline)
        }
    }

    private fun ssh(kind: SshProviderRequestKind, state: SshProviderRequestState) = StoredSshProviderRequest(
        requestId = "1".repeat(32),
        kind = kind,
        requesterClientId = ClientId("desktop"),
        requestFingerprint = ByteArray(32),
        history = SshRequestHistorySnapshot(requestedAt = 1_000, expiresAt = 2_000, payloadSize = 0),
        state = state,
        updatedAt = 1_000,
    )

    private fun openPgp(state: OpenPgpRequestState) = StoredOpenPgpRequest(
        request = OpenPgpSignSync(
            action = OpenPgpSignAction.REQUEST,
            requestId = "1".repeat(32),
            requesterClientId = ClientId("desktop"),
            issuedAt = 1_000,
            expiresAt = 2_000,
            primaryKeyId = "0123456789abcdef",
            payloadSha256 = ByteArray(32),
            objectKind = OpenPgpObjectKind.GIT_COMMIT,
        ),
        senderClientId = ClientId("desktop"),
        state = state,
        updatedAt = 1_000,
    )
}
