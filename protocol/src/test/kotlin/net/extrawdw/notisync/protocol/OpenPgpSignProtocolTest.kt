package net.extrawdw.notisync.protocol

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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
    private val tag = (
        "object " + "c".repeat(40) + "\n" +
            "type commit\n" +
            "tag v1.0.0\n" +
            "tagger Alice <alice@example.com> 1700000002 +0800\n\n" +
            "Release v1.0.0\n\nStable release.\n"
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
                processContext = null,
            ),
            request.copy(
                action = OpenPgpSignAction.REJECT,
                payload = null,
                rejectReason = OpenPgpRejectReason.USER_REJECTED,
                actionAt = 1_050,
                workingDirectory = null,
                processContext = null,
            ),
            request.copy(
                action = OpenPgpSignAction.CANCEL,
                payload = null,
                actionAt = 1_050,
                workingDirectory = null,
                processContext = null,
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
            assertEquals(value.processContext, decoded.processContext)
        }
    }

    @Test
    fun legacyRequestsWithoutProcessContextStillDecodeAndValidate() {
        val legacy = request().copy(processContext = null)
        // A null default is omitted from CBOR, preserving the pre-lineage request shape.
        val decoded = ProtocolCodec.decodeFromCbor<OpenPgpSignSync>(ProtocolCodec.encodeToCbor(legacy))
        assertNull(decoded.processContext)
        assertNull(decoded.validationError(::sha256))
        assertArrayEquals(legacy.payload, decoded.payload)
        val legacyJson = ProtocolCodec.encodeToJson(legacy).replace(",\"processContext\":null", "")
        assertNull(ProtocolCodec.decodeFromJson<OpenPgpSignSync>(legacyJson).processContext)
    }

    @Test
    fun processContextIsBoundedAndOnlyAllowedOnRequests() {
        val context = requireNotNull(request().processContext)
        listOf(
            context.copy(processLineage = emptyList()),
            context.copy(processLineage = List(17) { DesktopProcessIdentity((it + 1).toLong()) }),
            context.copy(processLineage = listOf(DesktopProcessIdentity(0))),
            context.copy(processLineage = listOf(DesktopProcessIdentity(1, "relative/path"))),
            context.copy(processLineage = listOf(context.leaf!!, context.leaf!!)),
        ).forEach { assertNotNull(request().copy(processContext = it).validationError(::sha256)) }
        assertNull(request().copy(
            processContext = DesktopProcessContext(DesktopProcessContextSource.UNAVAILABLE),
        ).validationError(::sha256))
        listOf(OpenPgpSignAction.RESULT, OpenPgpSignAction.REJECT, OpenPgpSignAction.CANCEL).forEach { action ->
            val terminal = request().copy(
                action = action, payload = null, workingDirectory = null, actionAt = 1_100,
                signatureArmor = if (action == OpenPgpSignAction.RESULT) armor() else null,
                rejectReason = if (action == OpenPgpSignAction.REJECT) OpenPgpRejectReason.USER_REJECTED else null,
            )
            assertNotNull(terminal.validationError(::sha256))
            assertNull(terminal.copy(processContext = null).validationError(::sha256))
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
                processContext = null,
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

    @Test
    fun tagParserPreservesBytesAndDerivesReviewFields() {
        val parsed = GitTagPayloadParser.parse(tag)

        assertArrayEquals(tag, parsed.bytes)
        assertEquals("c".repeat(40), parsed.objectId)
        assertEquals("commit", parsed.objectType)
        assertEquals("v1.0.0", parsed.tagName)
        assertEquals("Alice <alice@example.com> 1700000002 +0800", parsed.tagger)
        assertEquals("Release v1.0.0\n\nStable release.\n", parsed.message)
        assertEquals(OpenPgpObjectKind.GIT_TAG, GitSigningPayloadParser.parse(tag).objectKind)
    }

    @Test
    fun tagRequestsRequireTheTagSigningCapability() {
        val request = request().copy(
            payloadSha256 = sha256(tag),
            objectKind = OpenPgpObjectKind.GIT_TAG,
            payload = tag,
        )

        assertNull(request.validationError(::sha256))
        val decoded = ProtocolCodec.decodeFromCbor<DataSync>(
            ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.OPENPGP_SIGN, openPgpSign = request))
        ).openPgpSign!!
        assertEquals(OpenPgpObjectKind.GIT_TAG, decoded.objectKind)
        assertArrayEquals(tag, decoded.payload)
        assertEquals(
            setOf(
                Capability.OPENPGP_SIGN_V1,
                Capability.OPENPGP_SIGN_GIT_TAG_V1,
                Capability.BACKGROUND_WAKE,
                Capability.PUSH_FILTERING,
            ),
            request.requiredSignerCapabilities(),
        )
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

    @Test(expected = IllegalArgumentException::class)
    fun tagParserRejectsMissingTagger() {
        GitTagPayloadParser.parse(
            ("object " + "a".repeat(40) + "\ntype commit\ntag v1\n\ntag\n").encodeToByteArray()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun declaredObjectKindMustMatchThePayloadGrammar() {
        GitSigningPayloadParser.parse(OpenPgpObjectKind.GIT_TAG, commit)
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
        processContext = DesktopProcessContext(
            DesktopProcessContextSource.CURRENT_PROCESS,
            listOf(DesktopProcessIdentity(123, "/opt/notisync/bin/notisync-gpg"), DesktopProcessIdentity(100, "/usr/bin/git")),
        ),
    )

    private fun armor() = "-----BEGIN PGP SIGNATURE-----\nAA==\n-----END PGP SIGNATURE-----\n"
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}
