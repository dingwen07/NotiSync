package net.extrawdw.apps.notisync.seal

import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.OpenPgpObjectKind
import net.extrawdw.notisync.protocol.OpenPgpSignAction
import net.extrawdw.notisync.protocol.OpenPgpSignSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun commitMessageSeparatesSubjectFromBody() {
        val message = "Polish Seal notification\n\nShow repository context.\nKeep approval clear.\n"

        assertEquals("Polish Seal notification", message.commitSubject())
        assertEquals("Show repository context.\nKeep approval clear.", message.commitBody())
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
