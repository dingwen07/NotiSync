package net.extrawdw.notisync.protocol

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenPgpSignProtocolTest {
    private val commit = (
        "tree " + "a".repeat(40) + "\n" +
            "parent " + "b".repeat(40) + "\n" +
            "author Alice <alice@example.com> 1700000000 +0800\n" +
            "committer Bob <bob@example.com> 1700000001 +0800\n" +
            "encoding UTF-8\n\nSubject\n\nBody\r\n"
        ).encodeToByteArray()

    @Test
    fun allActionsRoundTripWithStableShape() {
        val request = request()
        val actions = listOf(
            request,
            request.copy(
                action = OpenPgpSignAction.RESULT,
                payload = null,
                signatureArmor = armor(),
                actionAt = 1_050,
                workingDirectory = null,
            ),
            request.copy(
                action = OpenPgpSignAction.REJECT,
                payload = null,
                rejectReason = OpenPgpRejectReason.USER_REJECTED,
                actionAt = 1_050,
                workingDirectory = null,
            ),
            request.copy(
                action = OpenPgpSignAction.CANCEL,
                payload = null,
                actionAt = 1_050,
                workingDirectory = null,
            ),
        )

        actions.forEach { value ->
            assertNull(value.validationError(::sha256))
            val decoded = ProtocolCodec.decodeFromCbor<DataSync>(
                ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.OPENPGP_SIGN, openPgpSign = value))
            ).openPgpSign!!
            assertEquals(value.action, decoded.action)
            assertEquals(value.requestId, decoded.requestId)
            assertArrayEquals(value.payloadSha256, decoded.payloadSha256)
            assertArrayEquals(value.payload, decoded.payload)
            assertEquals(value.signatureArmor, decoded.signatureArmor)
            assertEquals(value.rejectReason, decoded.rejectReason)
            assertEquals(value.workingDirectory, decoded.workingDirectory)
        }
    }

    @Test
    fun jsonProjectionExposesStableGenericFilterPaths() {
        val json = ProtocolCodec.encodeToJson(
            DataSync(DataSyncKind.OPENPGP_SIGN, openPgpSign = request())
        )

        assertTrue(json.contains("\"kind\":\"OPENPGP_SIGN\""))
        assertTrue(json.contains("\"openPgpSign\""))
        assertTrue(json.contains("\"requestId\":\"0123456789abcdef0123456789abcdef\""))
    }

    @Test
    fun actionValidationRejectsMalformedAndMismatchedRequests() {
        assertTrue(request().copy(requestId = "ABC").validationError(::sha256)!!.contains("requestId"))
        assertTrue(request().copy(primaryKeyId = "abcdef0123456789").validationError(::sha256)!!.contains("primaryKeyId"))
        assertTrue(request().copy(expiresAt = 121_001).validationError(::sha256)!!.contains("lifetime"))
        assertTrue(request().copy(payloadSha256 = ByteArray(32)).validationError(::sha256)!!.contains("mismatch"))
        assertTrue(request().copy(workingDirectory = "bad\npath").validationError(::sha256)!!.contains("workingDirectory"))
        assertTrue(
            request().copy(workingDirectory = "x".repeat(OpenPgpSignLimits.MAX_WORKING_DIRECTORY_UTF8_BYTES + 1))
                .validationError(::sha256)!!.contains("workingDirectory")
        )
        assertTrue(
            request().copy(
                action = OpenPgpSignAction.RESULT,
                payload = null,
                actionAt = 1_100,
                workingDirectory = null,
            )
                .validationError(::sha256)!!.contains("signature")
        )
    }

    @Test
    fun commitParserPreservesBytesAndDerivesReviewFields() {
        val parsed = GitCommitPayloadParser.parse(commit)

        assertArrayEquals(commit, parsed.bytes)
        assertEquals("a".repeat(40), parsed.treeId)
        assertEquals(listOf("b".repeat(40)), parsed.parentIds)
        assertEquals("Alice <alice@example.com> 1700000000 +0800", parsed.author)
        assertEquals("Bob <bob@example.com> 1700000001 +0800", parsed.committer)
        assertEquals("Subject\n\nBody\r\n", parsed.message)
        assertEquals("UTF-8", parsed.headers.single { it.name == "encoding" }.value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun commitParserRejectsExistingSignature() {
        GitCommitPayloadParser.parse(
            commit.decodeToString().replace("committer ", "gpgsig -----BEGIN\n committer ").encodeToByteArray()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun commitParserRejectsAnnotatedTagPayload() {
        GitCommitPayloadParser.parse(
            ("object " + "a".repeat(40) + "\ntype commit\ntag v1\ntagger A <a@b> 1 +0000\n\ntag\n")
                .encodeToByteArray()
        )
    }

    private fun request() = OpenPgpSignSync(
        action = OpenPgpSignAction.REQUEST,
        requestId = "0123456789abcdef0123456789abcdef",
        requesterClientId = ClientId("desktop"),
        issuedAt = 1_000,
        expiresAt = 121_000,
        primaryKeyId = "0123456789ABCDEF",
        payloadSha256 = sha256(commit),
        objectKind = OpenPgpObjectKind.GIT_COMMIT,
        payload = commit,
        workingDirectory = "/work/notisync",
    )

    private fun armor() = "-----BEGIN PGP SIGNATURE-----\nAA==\n-----END PGP SIGNATURE-----\n"
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}
