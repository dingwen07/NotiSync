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
    fun unshippedProtocolRemainsVersionOne() {
        assertEquals(1, SshAgentLimits.PROTOCOL_VERSION)
    }

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

    @Test
    fun webAuthnKeyRoundTripsAndRequiresCredentialProviderPolicy() {
        val publicBlob = "sk-ecdsa-sha2-nistp256@openssh.com public fields".encodeToByteArray()
        val key = SshKeyDescriptor(
            providerKeyId = id('6'),
            publicKeyBlob = publicBlob,
            publicKeyBlobSha256 = sha256(publicBlob),
            algorithm = SshKeyAlgorithm.WEBAUTHN_SK_ECDSA_NISTP256,
            displayName = "WebAuthn SSH key",
            origin = SshKeyOrigin.WEBAUTHN_CREATED,
            operationalKey = SshOperationalKeyProtection(
                provider = SshOperationalKeyProvider.CREDENTIAL_MANAGER_WEBAUTHN,
                securityLevel = SshStorageSecurityLevel.CREDENTIAL_PROVIDER,
                userVerificationPolicy = SshUserVerificationPolicy.PER_USE,
                strongBoxAttempted = false,
                strongBoxFallback = false,
            ),
            exportCopy = null,
            approvalPolicy = SshApprovalPolicy.ALWAYS_ASK,
            createdAt = 1_000,
            webAuthn = SshWebAuthnCredentialProtection(
                rpId = "notisync.apps.extrawdw.net",
                backupEligible = true,
                backupState = true,
            ),
        )

        assertNull(key.validationError(::sha256))
        assertNull(key.copy(origin = SshKeyOrigin.WEBAUTHN_RECOVERED).validationError(::sha256))
        val decoded = ProtocolCodec.decodeFromCbor<SshKeyDescriptor>(ProtocolCodec.encodeToCbor(key))
        assertEquals(SshOperationalKeyProvider.CREDENTIAL_MANAGER_WEBAUTHN, decoded.operationalKey.provider)
        assertEquals("notisync.apps.extrawdw.net", decoded.webAuthn?.rpId)
        assertNotNull(key.copy(webAuthn = null).validationError(::sha256))
        assertNotNull(key.copy(approvalPolicy = SshApprovalPolicy.ALLOW_REMEMBER).validationError(::sha256))
        assertNotNull(
            key.copy(
                operationalKey = key.operationalKey.copy(
                    provider = SshOperationalKeyProvider.ANDROID_KEYSTORE_PRIVATE_KEY,
                    securityLevel = SshStorageSecurityLevel.TRUSTED_ENVIRONMENT,
                ),
                webAuthn = null,
            ).validationError(::sha256),
        )
        assertNotNull(key.copy(origin = SshKeyOrigin.GENERATED).validationError(::sha256))
        assertNotNull(
            key.copy(webAuthn = key.webAuthn?.copy(backupEligible = false)).validationError(::sha256),
        )
    }

    @Test
    fun appleKeychainManagedKeyRoundTripsWithExistingImportOrigin() {
        val publicBlob = "ecdsa-sha2-nistp256 public fields".encodeToByteArray()
        val key = SshKeyDescriptor(
            providerKeyId = id('7'),
            publicKeyBlob = publicBlob,
            publicKeyBlobSha256 = sha256(publicBlob),
            algorithm = SshKeyAlgorithm.ECDSA_NISTP256,
            displayName = "iPhone key",
            origin = SshKeyOrigin.SAF_IMPORT,
            operationalKey = SshOperationalKeyProtection(
                provider = SshOperationalKeyProvider.APPLE_KEYCHAIN,
                securityLevel = SshStorageSecurityLevel.KEYCHAIN,
                userVerificationPolicy = SshUserVerificationPolicy.NONE,
                strongBoxAttempted = false,
                strongBoxFallback = false,
            ),
            exportCopy = null,
            approvalPolicy = SshApprovalPolicy.ALLOW_REMEMBER,
            createdAt = 1_000,
        )

        assertNull(key.validationError(::sha256))
        val decoded = ProtocolCodec.decodeFromCbor<SshKeyDescriptor>(ProtocolCodec.encodeToCbor(key))
        assertEquals(SshOperationalKeyProvider.APPLE_KEYCHAIN, decoded.operationalKey.provider)
        assertEquals(SshStorageSecurityLevel.KEYCHAIN, decoded.operationalKey.securityLevel)
        assertEquals(SshKeyOrigin.SAF_IMPORT, decoded.origin)
        assertNotNull(
            key.copy(
                operationalKey = key.operationalKey.copy(
                    securityLevel = SshStorageSecurityLevel.TRUSTED_ENVIRONMENT,
                ),
            ).validationError(::sha256),
        )
        assertNotNull(
            key.copy(
                operationalKey = key.operationalKey.copy(
                    securityLevel = SshStorageSecurityLevel.CREDENTIAL_PROVIDER,
                ),
            ).validationError(::sha256),
        )
        assertNotNull(
            key.copy(
                operationalKey = key.operationalKey.copy(strongBoxAttempted = true),
            ).validationError(::sha256),
        )
        assertNotNull(
            key.copy(algorithm = SshKeyAlgorithm.WEBAUTHN_SK_ECDSA_NISTP256).validationError(::sha256),
        )
        assertNotNull(
            key.copy(
                operationalKey = key.operationalKey.copy(
                    provider = SshOperationalKeyProvider.ANDROID_KEYSTORE_PRIVATE_KEY,
                ),
            ).validationError(::sha256),
        )
    }

    @Test
    fun appleAuthenticationServicesWebAuthnRoundTripsWithSharedInvariants() {
        val publicBlob = "sk-ecdsa-sha2-nistp256@openssh.com public fields".encodeToByteArray()
        val key = SshKeyDescriptor(
            providerKeyId = id('8'),
            publicKeyBlob = publicBlob,
            publicKeyBlobSha256 = sha256(publicBlob),
            algorithm = SshKeyAlgorithm.WEBAUTHN_SK_ECDSA_NISTP256,
            displayName = "Roaming passkey",
            origin = SshKeyOrigin.WEBAUTHN_RECOVERED,
            operationalKey = SshOperationalKeyProtection(
                provider = SshOperationalKeyProvider.APPLE_AUTHENTICATION_SERVICES_WEBAUTHN,
                securityLevel = SshStorageSecurityLevel.CREDENTIAL_PROVIDER,
                userVerificationPolicy = SshUserVerificationPolicy.PER_USE,
                strongBoxAttempted = false,
                strongBoxFallback = false,
            ),
            exportCopy = null,
            approvalPolicy = SshApprovalPolicy.ALWAYS_ASK,
            createdAt = 1_000,
            webAuthn = SshWebAuthnCredentialProtection(
                rpId = "notisync.apps.extrawdw.net",
                backupEligible = true,
                backupState = true,
            ),
        )

        assertNull(key.validationError(::sha256))
        val decoded = ProtocolCodec.decodeFromCbor<SshKeyDescriptor>(ProtocolCodec.encodeToCbor(key))
        assertEquals(
            SshOperationalKeyProvider.APPLE_AUTHENTICATION_SERVICES_WEBAUTHN,
            decoded.operationalKey.provider,
        )
        assertEquals(SshStorageSecurityLevel.CREDENTIAL_PROVIDER, decoded.operationalKey.securityLevel)
        assertEquals(SshKeyOrigin.WEBAUTHN_RECOVERED, decoded.origin)
        assertEquals("notisync.apps.extrawdw.net", decoded.webAuthn?.rpId)
        assertNotNull(
            key.copy(
                operationalKey = key.operationalKey.copy(securityLevel = SshStorageSecurityLevel.KEYCHAIN),
            ).validationError(::sha256),
        )
        assertNotNull(key.copy(algorithm = SshKeyAlgorithm.ECDSA_NISTP256).validationError(::sha256))
        assertNotNull(key.copy(webAuthn = null).validationError(::sha256))
        assertNotNull(key.copy(origin = SshKeyOrigin.SAF_IMPORT).validationError(::sha256))
        assertNotNull(
            key.copy(
                exportCopy = SshExportCopyProtection(
                    securityLevel = SshStorageSecurityLevel.TRUSTED_ENVIRONMENT,
                    backendPolicy = SshExportCopyBackendPolicy.BEST_AVAILABLE,
                    authentication = SshExportCopyAuthentication.STRONG_BIOMETRIC_OR_DEVICE_CREDENTIAL_PER_USE,
                    strongBoxAttempted = false,
                    strongBoxFallback = false,
                ),
            ).validationError(::sha256),
        )
        assertNotNull(
            key.copy(
                rememberedNamespaces = listOf(
                    SshRememberedNamespace(
                        requesterClientId = requester,
                        authorizationGeneration = id('9'),
                        authorizationEpoch = 0,
                        scopes = listOf(SshRememberScope.PEER),
                    ),
                ),
            ).validationError(::sha256),
        )
        assertNotNull(
            key.copy(
                operationalKey = key.operationalKey.copy(
                    userVerificationPolicy = SshUserVerificationPolicy.NONE,
                ),
            ).validationError(::sha256),
        )
        assertNotNull(
            key.copy(
                operationalKey = key.operationalKey.copy(
                    provider = SshOperationalKeyProvider.APPLE_KEYCHAIN,
                    securityLevel = SshStorageSecurityLevel.KEYCHAIN,
                ),
            ).validationError(::sha256),
        )
        assertNotNull(key.copy(approvalPolicy = SshApprovalPolicy.ALLOW_REMEMBER).validationError(::sha256))
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
