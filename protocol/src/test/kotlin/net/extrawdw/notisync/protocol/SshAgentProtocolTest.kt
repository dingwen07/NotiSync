package net.extrawdw.notisync.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class SshAgentProtocolTest {
    private val requester = ClientId("a".repeat(52))
    private val providerA = ClientId("b".repeat(52))
    private val providerB = ClientId("c".repeat(52))

    @Test
    fun keysRequestRoundTripsAtDataSyncLabel10() {
        val request = SshKeysRequest(
            requestId = id('1'),
            requesterClientId = requester,
            requestedAt = 1_000,
            expiresAt = 61_000,
            startup = true,
            targetProviderClientIds = listOf(providerA, providerB),
            requesterInventoryNonce = ByteArray(32) { it.toByte() },
        )
        val body = DataSync(
            kind = DataSyncKind.SSH_AGENT,
            sshAgent = SshAgentSync(kind = SshAgentSyncKind.KEYS_REQUEST, keysRequest = request),
        )

        val decoded = ProtocolCodec.decodeFromCbor<DataSync>(ProtocolCodec.encodeToCbor(body))

        assertEquals(DataSyncKind.SSH_AGENT, decoded.kind)
        assertEquals(SshAgentSyncKind.KEYS_REQUEST, decoded.sshAgent?.kind)
        assertEquals(request.requestId, decoded.sshAgent?.keysRequest?.requestId)
        assertArrayEquals(request.requesterInventoryNonce, decoded.sshAgent?.keysRequest?.requesterInventoryNonce)
        assertNull(decoded.openPgpSign)
        assertNull(decoded.sshAgent?.validationError(::sha256))
    }

    @Test
    fun topLevelUnionRejectsMixedAndMismatchedShapes() {
        val request = validKeysRequest()
        val snapshot = SshKeysSnapshot(
            providerClientId = providerA,
            inventoryGeneration = id('2'),
            revision = 1,
            generatedAt = 2_000,
            keys = emptyList(),
            providerHealth = SshProviderHealth.HEALTHY,
        )

        assertNotNull(
            SshAgentSync(
                kind = SshAgentSyncKind.KEYS_REQUEST,
                keysRequest = request,
                keysSnapshot = snapshot,
            ).validationError(::sha256),
        )
        assertNotNull(
            SshAgentSync(
                kind = SshAgentSyncKind.KEYS_SNAPSHOT,
                keysRequest = request,
            ).validationError(::sha256),
        )
        assertNotNull(
            SshAgentSync(
                protocolVersion = 2,
                kind = SshAgentSyncKind.KEYS_REQUEST,
                keysRequest = request,
            ).validationError(::sha256),
        )
    }

    @Test
    fun signResultKeepsUserRejectionDistinctFromProviderFailure() {
        val base = SshSignResult(
            requestId = id('3'),
            requestDigest = ByteArray(32),
            requesterClientId = requester,
            publicKeyBlobSha256 = ByteArray(32) { 1 },
            kind = SshSignResultKind.REJECTED_BY_USER,
            resultAt = 2_000,
            providerClientId = providerA,
            rejection = SshUserRejection(SshUserRejectionReason.USER_TAPPED_REJECT),
        )

        assertNull(base.validationError())
        assertNotNull(
            base.copy(
                rejection = null,
                failure = SshProviderFailure(SshProviderFailureCode.USER_VERIFICATION_CANCELLED),
            ).validationError(),
        )
        assertNull(
            base.copy(
                kind = SshSignResultKind.PROVIDER_FAILURE,
                rejection = null,
                failure = SshProviderFailure(SshProviderFailureCode.USER_VERIFICATION_CANCELLED),
            ).validationError(),
        )
    }

    @Test
    fun requestProviderListsMustBeSortedUniqueAndExcludeRequester() {
        val valid = validKeysRequest()

        assertNull(valid.validationError())
        assertTrue(valid.copy(targetProviderClientIds = listOf(providerB, providerA)).validationError() != null)
        assertTrue(valid.copy(targetProviderClientIds = listOf(providerA, providerA)).validationError() != null)
        assertTrue(valid.copy(targetProviderClientIds = listOf(requester)).validationError() != null)
    }

    @Test
    fun snapshotRecomputesPublicBlobDigestAndRejectsPerUseRememberPolicy() {
        val publicBlob = byteArrayOf(0, 0, 0, 11) + "ssh-ed25519".encodeToByteArray() + ByteArray(32)
        val key = SshKeyDescriptor(
            providerKeyId = id('4'),
            publicKeyBlob = publicBlob,
            publicKeyBlobSha256 = sha256(publicBlob),
            algorithm = SshKeyAlgorithm.SSH_ED25519,
            displayName = "Phone key",
            origin = SshKeyOrigin.GENERATED,
            operationalKey = SshOperationalKeyProtection(
                provider = SshOperationalKeyProvider.ANDROID_KEYSTORE_PRIVATE_KEY,
                securityLevel = SshStorageSecurityLevel.TRUSTED_ENVIRONMENT,
                userVerificationPolicy = SshUserVerificationPolicy.PER_USE,
                strongBoxAttempted = false,
                strongBoxFallback = false,
            ),
            exportCopy = null,
            approvalPolicy = SshApprovalPolicy.ALWAYS_ASK,
            createdAt = 1_000,
        )
        val snapshot = SshKeysSnapshot(
            providerClientId = providerA,
            inventoryGeneration = id('5'),
            revision = 1,
            generatedAt = 2_000,
            keys = listOf(key),
            providerHealth = SshProviderHealth.HEALTHY,
        )

        assertNull(snapshot.validationError(::sha256))
        val wrappedSnapshot = snapshot.copy(
            keys = listOf(
                key.copy(
                    operationalKey = key.operationalKey.copy(
                        provider = SshOperationalKeyProvider.ANDROID_KEYSTORE_AES_WRAPPED,
                    ),
                ),
            ),
        )
        assertNull(wrappedSnapshot.validationError(::sha256))
        assertEquals(
            SshOperationalKeyProvider.ANDROID_KEYSTORE_AES_WRAPPED,
            ProtocolCodec.decodeFromCbor<SshKeysSnapshot>(
                ProtocolCodec.encodeToCbor(wrappedSnapshot),
            ).keys.single().operationalKey.provider,
        )
        assertNotNull(snapshot.copy(keys = listOf(key.copy(publicKeyBlobSha256 = ByteArray(32)))).validationError(::sha256))
        assertNotNull(
            snapshot.copy(
                keys = listOf(key.copy(approvalPolicy = SshApprovalPolicy.ALLOW_REMEMBER)),
            ).validationError(::sha256),
        )
        assertNotNull(
            key.copy(
                operationalKey = key.operationalKey.copy(
                    securityLevel = SshStorageSecurityLevel.STRONGBOX,
                    strongBoxAttempted = false,
                ),
            ).validationError(::sha256),
        )
        assertNotNull(
            key.copy(
                algorithm = SshKeyAlgorithm.SSH_RSA,
                operationalKey = key.operationalKey.copy(
                    provider = SshOperationalKeyProvider.ANDROID_KEYSTORE_AES_WRAPPED,
                ),
            ).validationError(::sha256),
        )
        assertNotNull(
            key.copy(
                exportCopy = SshExportCopyProtection(
                    securityLevel = SshStorageSecurityLevel.STRONGBOX,
                    backendPolicy = SshExportCopyBackendPolicy.TEE_ONLY,
                    authentication = SshExportCopyAuthentication.STRONG_BIOMETRIC_OR_DEVICE_CREDENTIAL_PER_USE,
                    strongBoxAttempted = false,
                    strongBoxFallback = false,
                ),
            ).validationError(::sha256),
        )
    }

    private fun validKeysRequest() = SshKeysRequest(
        requestId = id('1'),
        requesterClientId = requester,
        requestedAt = 1_000,
        expiresAt = 61_000,
        startup = true,
        targetProviderClientIds = listOf(providerA, providerB),
        requesterInventoryNonce = ByteArray(32),
    )

    private fun id(character: Char) = character.toString().repeat(32)
    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
