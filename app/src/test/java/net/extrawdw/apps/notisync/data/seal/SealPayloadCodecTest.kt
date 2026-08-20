package net.extrawdw.apps.notisync.data.seal

import net.extrawdw.apps.notisync.seal.GitCommitDisplayHeader
import net.extrawdw.apps.notisync.seal.GitCommitDisplaySnapshot
import net.extrawdw.apps.notisync.seal.OpenPgpEnrollment
import net.extrawdw.apps.notisync.seal.sealRequestFingerprint
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.OpenPgpObjectKind
import net.extrawdw.notisync.protocol.OpenPgpSignAction
import net.extrawdw.notisync.protocol.OpenPgpSignSync
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SealPayloadCodecTest {
    @Test
    fun enrollmentRoundTripPreservesProtectedMaterial() {
        val enrollment = OpenPgpEnrollment(
            enabled = true,
            providerId = "org.sufficientlysecure.keychain",
            providerKeyReference = "0x1234ABCD",
            primaryKeyId = "89ABCDEF01234567",
            displayIdentity = "Example <example@example.com>",
            enrolledAt = 1_700_000_000_000,
        )

        assertEquals(enrollment, SealPayloadCodec.decodeEnrollment(SealPayloadCodec.encodeEnrollment(enrollment)))
    }

    @Test
    fun displayRoundTripPreservesBoundedHistoryProjection() {
        val display = SealPayloadCodec.SealDisplayPayload(
            primaryKeyId = "89ABCDEF01234567",
            workingDirectory = "C:\\work\\NotiSync",
            commit = GitCommitDisplaySnapshot(
                treeId = "0123456789abcdef0123456789abcdef01234567",
                parentIds = listOf("fedcba9876543210fedcba9876543210fedcba98"),
                author = "Example <example@example.com> 1700000000 +0000",
                committer = "Example <example@example.com> 1700000000 +0000",
                message = "Store test\n",
                extraHeaders = listOf(GitCommitDisplayHeader("encoding", "UTF-8")),
                payloadBytes = 256,
            ),
        )

        val restored = SealPayloadCodec.decodeDisplay(
            SealPayloadCodec.encodeDisplay(
                primaryKeyId = display.primaryKeyId,
                workingDirectory = display.workingDirectory,
                commit = display.commit,
            ),
        )
        assertEquals(display, restored)
    }

    @Test
    fun oversizedDisplayDropsOnlyTrailingHeadersAndMarksTruncation() {
        val commit = GitCommitDisplaySnapshot(
            treeId = "0123456789abcdef0123456789abcdef01234567",
            parentIds = emptyList(),
            author = "Example",
            committer = "Example",
            message = "message",
            extraHeaders = (0 until 64).map { index ->
                GitCommitDisplayHeader("header-$index", "x".repeat(2 * 1_024))
            },
            payloadBytes = 256,
        )

        val encoded = SealPayloadCodec.encodeDisplayBounded(
            primaryKeyId = "89ABCDEF01234567",
            workingDirectory = null,
            commit = commit,
        )
        val restored = SealPayloadCodec.decodeDisplay(encoded.bytes)

        assertTrue(encoded.bytes.size <= 64 * 1_024)
        assertTrue(encoded.snapshot?.truncated == true)
        assertTrue(restored.commit?.truncated == true)
        assertTrue((restored.commit?.extraHeaders?.size ?: 0) < commit.extraHeaders.size)
    }

    @Test
    fun decoderRejectsTamperingAndTrailingBytes() {
        val encoded = SealPayloadCodec.encodeEnrollment(
            OpenPgpEnrollment(
                enabled = true,
                providerId = "provider",
                providerKeyReference = "key",
                primaryKeyId = "89ABCDEF01234567",
                displayIdentity = "Example",
                enrolledAt = 1,
            ),
        )
        val tampered = encoded.copyOf().also { it[0] = it[0].inc() }
        val trailing = encoded + byteArrayOf(1)

        assertThrows(IllegalArgumentException::class.java) {
            SealPayloadCodec.decodeEnrollment(tampered)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SealPayloadCodec.decodeEnrollment(trailing)
        }
    }

    @Test
    fun requestFingerprintMatchesTheImportedV1DoubleDigest() {
        val request = OpenPgpSignSync(
            action = OpenPgpSignAction.REQUEST,
            requestId = "0123456789abcdef0123456789abcdef",
            requesterClientId = ClientId("desktop-client"),
            issuedAt = 1_000,
            expiresAt = 100_000,
            primaryKeyId = "89ABCDEF01234567",
            payloadSha256 = MessageDigest.getInstance("SHA-256").digest("payload".encodeToByteArray()),
            objectKind = OpenPgpObjectKind.GIT_COMMIT,
            payload = null,
            workingDirectory = "C:\\work\\NotiSync",
        )

        assertEquals(
            "c6d883b5dd5581a07976f8d3c911ff619a3759badae00f7122e3f1bbebf54dd3",
            request.sealRequestFingerprint(ClientId("desktop-client")).toHex(),
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
