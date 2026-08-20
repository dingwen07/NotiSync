package net.extrawdw.apps.notisync.sshagent

import java.security.Signature
import javax.crypto.Cipher
import kotlinx.coroutines.flow.StateFlow
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.SshApprovalPolicy
import net.extrawdw.notisync.protocol.SshDestinationContext
import net.extrawdw.notisync.protocol.SshExportCopyBackendPolicy
import net.extrawdw.notisync.protocol.SshImportRequest
import net.extrawdw.notisync.protocol.SshImportResult
import net.extrawdw.notisync.protocol.SshKeyAlgorithm
import net.extrawdw.notisync.protocol.SshImportSourceType
import net.extrawdw.notisync.protocol.SshKeysSnapshot
import net.extrawdw.notisync.protocol.SshRememberScope
import net.extrawdw.notisync.protocol.SshSignRequest
import net.extrawdw.notisync.protocol.SshSignResult
import net.extrawdw.notisync.protocol.SshUserVerificationPolicy

/** Room-backed suspend boundary used by SSH UI, workers, and authenticated peer traffic. */
internal interface SshAgentProviderRepository {
    val changeVersion: StateFlow<Long>

    suspend fun snapshot(provider: ClientId, respondingToRequestId: String?, now: Long): SshKeysSnapshot
    suspend fun knownHosts(): List<SshKnownHost>
    suspend fun knownHostHostname(hostKeySha256: ByteArray): String?
    suspend fun knownHostHostname(destination: SshDestinationContext): String? =
        SshRememberAuthorizationPolicy.verifiedHostKeySha256(destination)?.let { knownHostHostname(it) }
    suspend fun updateKnownHostHostname(hostKeySha256: ByteArray, hostname: String): Boolean
    suspend fun deleteKnownHost(hostKeySha256: ByteArray): Boolean
    suspend fun rememberedAuthorizations(): List<SshRememberedAuthorization>
    suspend fun deleteRememberedAuthorization(authorizationId: String): Boolean

    suspend fun generateKey(
        algorithm: SshKeyAlgorithm,
        displayName: String,
        now: Long,
        allowExport: Boolean = false,
        exportCopyBackendPolicy: SshExportCopyBackendPolicy = SshExportCopyBackendPolicy.BEST_AVAILABLE,
        userVerificationPolicy: SshUserVerificationPolicy = SshUserVerificationPolicy.NONE,
        rsaKeySizeBits: Int = 3072,
    ): SshKeyStorageResult

    suspend fun importPrivateKeyFile(
        fileBytes: ByteArray,
        passphrase: CharArray?,
        displayName: String,
        now: Long,
        allowExport: Boolean = true,
        exportCopyBackendPolicy: SshExportCopyBackendPolicy = SshExportCopyBackendPolicy.BEST_AVAILABLE,
        userVerificationPolicy: SshUserVerificationPolicy = SshUserVerificationPolicy.NONE,
    ): SshKeyStorageResult

    suspend fun prepareExport(providerKeyId: String): PreparedSshKeyExport?
    suspend fun completeExport(prepared: PreparedSshKeyExport, authenticatedCipher: Cipher): ByteArray?
    suspend fun cancelExport(prepared: PreparedSshKeyExport)
    suspend fun completePreparedKeyStorage(
        prepared: PreparedSshKeyStorage,
        authenticatedCipher: Cipher? = null,
        authenticatedSignature: Signature? = null,
    ): SshKeyStorageResult
    suspend fun cancelPreparedKeyStorage(prepared: PreparedSshKeyStorage)
    suspend fun deleteKey(providerKeyId: String): Boolean
    suspend fun updateKeyMetadata(
        providerKeyId: String,
        displayName: String,
        approvalPolicy: SshApprovalPolicy,
        expiresAt: Long? = null,
        updatedAt: Long = System.currentTimeMillis(),
    ): Boolean
    suspend fun owns(publicKeyBlob: ByteArray, now: Long): Boolean

    suspend fun acceptSign(request: SshSignRequest, now: Long): SshProviderAcceptResult
    suspend fun acceptImport(request: SshImportRequest, now: Long): SshProviderAcceptResult
    suspend fun find(requestId: String): StoredSshProviderRequest?
    suspend fun pendingReview(): List<StoredSshProviderRequest>
    suspend fun pendingResponses(): List<StoredSshProviderRequest>
    suspend fun requests(): List<StoredSshProviderRequest>
    suspend fun recordImportPreview(requestId: String, publicKeyBlob: ByteArray): Boolean
    suspend fun keyDisplayName(publicKeyBlob: ByteArray): String?
    suspend fun availableRememberScopes(requestId: String): Set<SshRememberScope>
    suspend fun requiresPerUseUserVerification(requestId: String): Boolean
    suspend fun approve(requestId: String, provider: ClientId, now: Long): SshSignResult?
    suspend fun approveAndRemember(requestId: String, provider: ClientId, scope: SshRememberScope, now: Long): SshSignResult?
    suspend fun prepareUserVerifiedSignature(requestId: String, provider: ClientId, now: Long): PreparedSshSignature?
    suspend fun completeUserVerifiedSignature(
        prepared: PreparedSshSignature,
        signature: Signature?,
        cipher: Cipher?,
        provider: ClientId,
        now: Long,
    ): SshSignResult?
    suspend fun cancelPreparedSignature(prepared: PreparedSshSignature)
    suspend fun failUserVerification(requestId: String, provider: ClientId, now: Long, code: net.extrawdw.notisync.protocol.SshProviderFailureCode): Boolean
    suspend fun approveImport(
        requestId: String,
        provider: ClientId,
        now: Long,
        allowExport: Boolean,
        exportCopyBackendPolicy: SshExportCopyBackendPolicy,
        userVerificationPolicy: SshUserVerificationPolicy,
        passphrase: CharArray? = null,
    ): SshImportApprovalOutcome?
    suspend fun completePreparedImport(
        prepared: PreparedSshImportStorage,
        authenticatedCipher: Cipher?,
        authenticatedSignature: Signature?,
        provider: ClientId,
        now: Long,
    ): SshImportApprovalOutcome?
    suspend fun cancelPreparedImport(prepared: PreparedSshImportStorage)
    suspend fun autoApproveRemembered(requestId: String, provider: ClientId, now: Long): StoredSshProviderRequest?
    suspend fun forgetAuthorization(requester: ClientId, generation: String, invalidatedThroughEpoch: Long, now: Long): SshAuthorizationForgetOutcome
    suspend fun reject(requestId: String, provider: ClientId, now: Long): Boolean
    suspend fun cancelSign(requestId: String, requester: ClientId, now: Long): Boolean
    suspend fun markSent(requestId: String, now: Long): Boolean
    suspend fun expireDue(now: Long): List<String>
    suspend fun cancelInvalidatedPending(now: Long): List<String>

    suspend fun prepareResponse(requestId: String, now: Long): PreparedSshResponse?
    suspend fun completeResponse(prepared: PreparedSshResponse, sentAt: Long): Boolean
    suspend fun requestExpiresAt(request: StoredSshProviderRequest): Long
    suspend fun resetAllSshStorage(): SshKeyStoreResetResult
}

internal data class PreparedSshResponse(
    val requestId: String,
    val kind: SshProviderRequestKind,
    val encodedBody: ByteArray,
    val durableCustody: Boolean,
)
