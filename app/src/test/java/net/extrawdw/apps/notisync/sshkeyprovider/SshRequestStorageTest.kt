package net.extrawdw.apps.notisync.sshkeyprovider

import net.extrawdw.apps.notisync.data.storage.operational.SshRequestStorage
import net.extrawdw.notisync.protocol.*
import org.junit.Assert.*
import org.junit.Test

class SshRequestStorageTest {
    @Test
    fun completeSignRequestAndSignedResponseRoundTripEveryFieldAfterCompletion() {
        val request = signRequest()
        val response = SshSignResult(
            request.requestId, request.requesterClientId, ByteArray(32) { 4 }, SshSignResultKind.SIGNED,
            1_500, ClientId("provider"),
            SshSignatureResult(byteArrayOf(6, 7), SshRememberDisposition.CREATED_PEER_HOST_KEY,
                request.authorizationGeneration, request.authorizationEpoch),
        )
        val original = stored(request).copy(
            state = SshProviderRequestState.SENT, outcome = SshProviderRequestOutcome.SIGNED, resultAt = 1_500,
            encodedResponse = ProtocolCodec.encodeToCbor(response),
            history = stored(request).history.copy(
                approvalKind = SshRequestApprovalKind.REMEMBERED_AUTHORIZATION,
                rememberedAuthorizationId = "remembered", rememberedScope = SshRememberScope.PEER_HOST_KEY,
            ),
        )
        val actual = roundTrip(original)
        assertArrayEquals(ProtocolCodec.encodeToCbor(request), ProtocolCodec.encodeToCbor(requireNotNull(actual.signRequest)))
        assertArrayEquals(original.encodedResponse, actual.encodedResponse)
        assertEquals(original.history.copy(publicKeyBlob = null), actual.history.copy(publicKeyBlob = null))
        assertArrayEquals(original.history.publicKeyBlob, actual.history.publicKeyBlob)
        assertEquals(SshProviderRequestState.SENT, actual.state)
        assertEquals(SshProviderRequestOutcome.SIGNED, actual.outcome)
    }

    @Test
    fun cancelledAndExpiredSignRequestsRetainFullPayload() {
        for (state in listOf(SshProviderRequestState.CANCELLED, SshProviderRequestState.EXPIRED)) {
            val original = stored(signRequest()).copy(state = state)
            assertArrayEquals(original.signRequest!!.data, roundTrip(original).signRequest!!.data)
        }
    }

    @Test
    fun activeImportsRoundTripAndTerminalImportsEraseOnlyPrivateSources() {
        for (source in SshImportSourceType.entries) {
            val request = SshImportRequest(
                "b".repeat(32), ClientId("desktop"), 1_000, 2_000, source,
                fileBytes = if (source == SshImportSourceType.PRIVATE_KEY_FILE) byteArrayOf(1, 2) else null,
                agentIdentity = if (source == SshImportSourceType.AGENT_IDENTITY) byteArrayOf(3, 4) else null,
                constraints = if (source == SshImportSourceType.AGENT_IDENTITY) SshImportConstraints(60, true) else null,
                suggestedName = "Imported key",
            )
            val original = StoredSshProviderRequest(
                request.requestId, SshProviderRequestKind.IMPORT, request.requesterClientId, byteArrayOf(9),
                importRequest = request,
                history = SshRequestHistorySnapshot(1_000, 2_000, keyName = "Imported key",
                    suggestedName = request.suggestedName, importSourceType = source, payloadSize = 2),
                state = SshProviderRequestState.PENDING_REVIEW, updatedAt = 1_000,
            )
            assertArrayEquals(ProtocolCodec.encodeToCbor(request),
                ProtocolCodec.encodeToCbor(requireNotNull(roundTrip(original).importRequest)))
            for (state in SshProviderRequestState.entries.filter { it != SshProviderRequestState.PENDING_REVIEW }) {
                val terminal = original.copy(state = state)
                val values = SshRequestStorage.values(terminal)
                assertNull(values["import_file_bytes"])
                assertNull(values["import_agent_identity"])
                assertEquals(0, values["request_complete"])
                assertEquals(request.constraints?.lifetimeSeconds, values["import_lifetime_seconds"])
                assertEquals("Imported key", roundTrip(terminal).history.suggestedName)
                assertNull(roundTrip(terminal).importRequest)
            }
        }
    }

    @Test
    fun legacySummaryWithoutOriginalRequestRemainsReadable() {
        val original = stored(signRequest()).copy(signRequest = null, state = SshProviderRequestState.EXPIRED,
            outcome = SshProviderRequestOutcome.EXPIRED)
        val actual = roundTrip(original)
        assertNull(actual.signRequest)
        assertEquals(original.history.processLineage, actual.history.processLineage)
        assertEquals(original.history.destinationHost, actual.history.destinationHost)
        assertArrayEquals(original.history.publicKeyBlob, actual.history.publicKeyBlob)
        assertEquals(original.outcome, actual.outcome)
        val values = SshRequestStorage.values(original)
        assertNull(values["process_source"])
        assertNull(values["process_boot_id"])
        assertNull(values["eligible_provider_client_ids_json"])
        assertNull(values["host_aliases_json"])
        assertNull(values["binding_chain_json"])
    }

    @Test
    fun listProjectionOmitsPrivateAndLargePayloadsButPreservesReviewMetadata() {
        val original = stored(signRequest())
        val values = SshRequestStorage.values(original)
        // Context needed only for signing must not be parsed by the list projection.
        values["eligible_provider_client_ids_json"] = "invalid"
        values["host_aliases_json"] = "invalid"
        values["binding_chain_json"] = "invalid"
        val actual = SshRequestStorage.reconstruct(values, false)
        assertNull(actual.signRequest)
        assertNull(actual.encodedResponse)
        assertEquals(original.history.processLineage, actual.history.processLineage)
        assertFalse(SshRequestStorage.summaryColumns.split(", ").contains("sign_data"))
        assertFalse(SshRequestStorage.summaryColumns.contains("import_file_bytes"))
        assertFalse(SshRequestStorage.summaryColumns.contains("import_agent_identity"))
        assertTrue(SshRequestStorage.summaryColumns.contains("process_lineage_json"))
        assertFalse(SshRequestStorage.summaryColumns.contains("eligible_provider_client_ids_json"))
        assertFalse(SshRequestStorage.summaryColumns.contains("host_aliases_json"))
        assertFalse(SshRequestStorage.summaryColumns.contains("binding_chain_json"))
        assertTrue(SshRequestStorage.summaryColumns.split(", ").none { it.startsWith("response_") })
        val projection = values.filterKeys { it in SshRequestStorage.summaryColumns.split(", ") }
        assertFalse(projection.values.any { it is ByteArray })
        val summary = SshRequestStorage.reconstruct(projection, false)
        assertTrue(summary.requestFingerprint.isEmpty())
        assertNull(summary.history.publicKeyBlob)
        assertEquals(original.history.processLineage, summary.history.processLineage)
    }

    private fun roundTrip(stored: StoredSshProviderRequest): StoredSshProviderRequest {
        return SshRequestStorage.reconstruct(SshRequestStorage.values(stored), true)
    }

    private fun stored(request: SshSignRequest) = StoredSshProviderRequest(
        request.requestId, SshProviderRequestKind.SIGN, request.requesterClientId, byteArrayOf(1, 2),
        signRequest = request,
        history = SshRequestHistorySnapshot(
            request.requestedAt, request.expiresAt, request.publicKeyBlob, "Signing key",
            signatureAlgorithm = request.requestedSignatureAlgorithm, processLineage = request.processContext.processLineage,
            destinationUsername = request.destinationContext.username,
            destinationHost = request.destinationContext.hostAliases.first().value,
            destinationHostKeyFingerprint = "SHA256:known", payloadSize = request.data.size,
        ),
        state = SshProviderRequestState.PENDING_REVIEW, updatedAt = 1_000,
    )

    private fun signRequest() = SshSignRequest(
        "a".repeat(32), ClientId("desktop"), 1_000, 2_000, byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6),
        0xffff_ffff, SshSignatureAlgorithm.RSA_SHA2_512, listOf(ClientId("provider"), ClientId("provider-2")),
        "c".repeat(32), 42,
        DesktopProcessContext(DesktopProcessContextSource.PEER_CREDENTIALS,
            listOf(DesktopProcessIdentity(10, "/usr/bin/ssh", "ssh"), DesktopProcessIdentity(9, "/bin/bash", "bash")),
            "12345678-abcd-1234-abcd-123456789abc"),
        SshDestinationContext(
            SshDestinationProvenance.VERIFIED_SESSION_BIND, SshConnectionDirection.FORWARDED, "user", "ssh-connection",
            "publickey", ByteArray(32) { 1 }, byteArrayOf(7, 8), ByteArray(32) { 2 },
            listOf(SshHostAlias("host", SshHostAliasSource.KNOWN_HOSTS_PLAIN),
                SshHostAlias("alias", SshHostAliasSource.PROCESS_ARGUMENT)),
            listOf(SshVerifiedBinding(ByteArray(32) { 3 }, false), SshVerifiedBinding(ByteArray(32) { 4 }, true)),
        ),
        "d".repeat(32), true,
    )
}
