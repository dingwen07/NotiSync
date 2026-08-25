package net.extrawdw.apps.notisync.seal

import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.OpenPgpObjectKind
import net.extrawdw.notisync.protocol.OpenPgpSignAction
import net.extrawdw.notisync.protocol.OpenPgpSignSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SigningRequestUiTest {
    @Test
    fun durableResultWinsAfterTransportAdvancesToSent() {
        val sent = stored(OpenPgpRequestState.SENT, OpenPgpRequestResult.APPROVED)

        assertEquals(SealDisplayStatus.APPROVED, sent.sealDisplayStatus())
        assertEquals(
            SealDisplayStatus.LEGACY_FINISHED,
            sent.copy(result = null).sealDisplayStatus(),
        )
    }

    @Test
    fun cancelledRequestRendersAsTerminalAndCannotKeepApprovalActions() {
        val cancelled = stored(OpenPgpRequestState.CANCELLED, OpenPgpRequestResult.CANCELED)

        assertEquals(SealDisplayStatus.CANCELED, cancelled.sealDisplayStatus())
        assertFalse(cancelled.isSealActive())
        assertFalse(cancelled.opensSealReview())
    }

    @Test
    fun onlyAutoOpenedCancellationAndExpiryCloseTheReviewTask() {
        val cancelled = stored(OpenPgpRequestState.SENT, OpenPgpRequestResult.CANCELED)
        val expired = stored(OpenPgpRequestState.SENT, OpenPgpRequestResult.EXPIRED)
        val rejected = stored(OpenPgpRequestState.SENT, OpenPgpRequestResult.REJECTED)

        assertTrue(cancelled.shouldCloseAutoOpenedReview(autoLaunchOwned = true))
        assertTrue(expired.shouldCloseAutoOpenedReview(autoLaunchOwned = true))
        assertFalse(cancelled.shouldCloseAutoOpenedReview(autoLaunchOwned = false))
        assertFalse(expired.shouldCloseAutoOpenedReview(autoLaunchOwned = false))
        assertFalse(rejected.shouldCloseAutoOpenedReview(autoLaunchOwned = true))
    }

    @Test
    fun commitSnapshotIsAppLocalAndKeepsOnlyDisplayFacts() {
        val payload = (
            "tree 0123456789abcdef0123456789abcdef01234567\n" +
                "parent 6648f0d82d47bafb997f07ea8720e22d89471068\n" +
                "author Example <example@example.com> 1700000000 +0000\n" +
                "committer Example <example@example.com> 1700000000 +0000\n\n" +
                "Polish Seal review\n"
            ).encodeToByteArray()

        val snapshot = payload.toDisplaySnapshot()
        assertNotNull(snapshot)
        requireNotNull(snapshot)
        assertEquals("6648f0d", snapshot.parentIds.single().shortObjectId())
        assertEquals("Polish Seal review", snapshot.message.commitSubject())
        assertEquals("", snapshot.message.commitBody())
        assertEquals(payload.size, snapshot.payloadBytes)
        assertEquals("NotiSync", "C:\\work\\NotiSync".workingDirectoryName())
        assertEquals("NotiSync", "/work/NotiSync/".workingDirectoryName())
    }

    @Test
    fun tagSnapshotKeepsTheFactsRequiredForReviewAndHistory() {
        val payload = (
            "object 0123456789abcdef0123456789abcdef01234567\n" +
                "type commit\n" +
                "tag v1.0.0\n" +
                "tagger Example <example@example.com> 1700000000 +0000\n\n" +
                "Release v1.0.0\n\nStable release.\n"
            ).encodeToByteArray()

        val snapshot = requireNotNull(payload.toTagDisplaySnapshot())

        assertEquals("v1.0.0", snapshot.tagName)
        assertEquals("commit", snapshot.objectType)
        assertEquals("0123456", snapshot.objectId.shortObjectId())
        assertEquals("Release v1.0.0", snapshot.message.commitSubject())
        assertEquals("Stable release.", snapshot.message.commitBody())
        assertEquals(payload.size, snapshot.payloadBytes)
    }

    @Test
    fun commitMessageSeparatesSubjectFromBody() {
        val message = "Polish Seal notification\n\nShow repository context.\nKeep approval clear.\n"

        assertEquals("Polish Seal notification", message.commitSubject())
        assertEquals("Show repository context.\nKeep approval clear.", message.commitBody())
    }

    @Test
    fun oversizedCommitDisplayFactsAreBoundedForHistory() {
        val oversizedMessage = "x".repeat(20_000)
        val payload = (
            "tree 0123456789abcdef0123456789abcdef01234567\n" +
                "author Example <example@example.com> 1700000000 +0000\n" +
                "committer Example <example@example.com> 1700000000 +0000\n\n" +
                oversizedMessage
            ).encodeToByteArray()

        val snapshot = requireNotNull(payload.toDisplaySnapshot())

        assertEquals(16 * 1_024, snapshot.message.length)
        assertEquals(true, snapshot.truncated)
    }

    @Test
    fun payloadDigestProducesTheSharedVerificationCode() {
        assertEquals("0123456", byteArrayOf(0x01, 0x23, 0x45, 0x67).toHex().take(7))
    }

    private fun stored(
        state: OpenPgpRequestState,
        result: OpenPgpRequestResult?,
    ) = StoredOpenPgpRequest(
        request = OpenPgpSignSync(
            action = OpenPgpSignAction.REQUEST,
            requestId = "0123456789abcdef0123456789abcdef",
            requesterClientId = ClientId("desktop"),
            issuedAt = 1,
            expiresAt = 2,
            primaryKeyId = "0123456789ABCDEF",
            payloadSha256 = ByteArray(32),
            objectKind = OpenPgpObjectKind.GIT_COMMIT,
            payload = byteArrayOf(1),
            workingDirectory = "C:\\work\\NotiSync",
        ),
        senderClientId = ClientId("desktop"),
        state = state,
        updatedAt = 3,
        result = result,
    )
}
