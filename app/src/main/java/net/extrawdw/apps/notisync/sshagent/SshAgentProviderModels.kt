package net.extrawdw.apps.notisync.sshagent

import java.security.PublicKey
import java.security.Signature
import javax.crypto.Cipher
import kotlinx.serialization.Serializable
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DesktopProcessIdentity
import net.extrawdw.notisync.protocol.SshExportCopyBackendPolicy
import net.extrawdw.notisync.protocol.SshImportRequest
import net.extrawdw.notisync.protocol.SshImportSourceType
import net.extrawdw.notisync.protocol.SshKeyAlgorithm
import net.extrawdw.notisync.protocol.SshKeyDescriptor
import net.extrawdw.notisync.protocol.SshKeyOrigin
import net.extrawdw.notisync.protocol.SshOperationalKeyProvider
import net.extrawdw.notisync.protocol.SshRememberScope
import net.extrawdw.notisync.protocol.SshSignRequest
import net.extrawdw.notisync.protocol.SshStorageSecurityLevel
import net.extrawdw.notisync.protocol.SshUserVerificationPolicy
import net.extrawdw.notisync.ssh.core.SshSignatureMethod

enum class SshProviderRequestState { PENDING_REVIEW, RESPONSE_PENDING_SEND, SENT, CANCELLED, EXPIRED }
enum class SshProviderRequestKind { SIGN, IMPORT }
enum class SshProviderRequestOutcome { SIGNED, IMPORTED, ALREADY_PRESENT, REJECTED, FAILED, CANCELLED, EXPIRED }
enum class SshRequestApprovalKind { MANUAL, REMEMBERED_AUTHORIZATION }
enum class SshProviderAcceptResult {
    STORED,
    DUPLICATE,
    CONFLICT,
    RATE_LIMITED,
    AUTHORIZATION_INVALIDATED,
    KEY_NOT_FOUND,
}

data class SshAuthorizationForgetOutcome(
    val inventoryChanged: Boolean,
    val cancelledRequestIds: List<String>,
)

data class StoredSshProviderRequest(
    val requestId: String,
    val kind: SshProviderRequestKind,
    val requesterClientId: ClientId,
    val requestFingerprint: ByteArray,
    val signRequest: SshSignRequest? = null,
    val importRequest: SshImportRequest? = null,
    val history: SshRequestHistorySnapshot,
    val state: SshProviderRequestState,
    val outcome: SshProviderRequestOutcome? = null,
    val resultAt: Long? = null,
    val encodedResponse: ByteArray? = null,
    val updatedAt: Long,
)

@Serializable
data class SshRequestHistorySnapshot(
    val requestedAt: Long,
    val expiresAt: Long,
    @kotlinx.serialization.cbor.ByteString val publicKeyBlob: ByteArray? = null,
    val keyName: String? = null,
    val suggestedName: String? = null,
    val importSourceType: SshImportSourceType? = null,
    val encryptedImport: Boolean = false,
    val signatureAlgorithm: net.extrawdw.notisync.protocol.SshSignatureAlgorithm? = null,
    val processLineage: List<DesktopProcessIdentity> = emptyList(),
    val destinationUsername: String? = null,
    val destinationHost: String? = null,
    val destinationHostKeyFingerprint: String? = null,
    val payloadSize: Int,
    val approvalKind: SshRequestApprovalKind? = null,
    val rememberedAuthorizationId: String? = null,
    val rememberedScope: SshRememberScope? = null,
)

data class SshKnownHost(
    val hostKeySha256: ByteArray,
    val hostname: String?,
    val firstApprovedAt: Long,
    val lastApprovedAt: Long,
)

data class SshRememberedAuthorization(
    val authorizationId: String,
    val providerKeyId: String,
    val requesterClientId: ClientId,
    val authorizationGeneration: String,
    val authorizationEpoch: Long,
    val scope: SshRememberScope,
    val hostKeySha256: ByteArray?,
    val hostname: String?,
    val createdAt: Long,
)

class PreparedSshSignature internal constructor(
    val requestId: String,
    val requestFingerprint: ByteArray,
    val signature: Signature?,
    val cipher: Cipher?,
    internal val method: SshSignatureMethod,
    internal val operation: PreparedSignatureOperation,
) : AutoCloseable {
    init {
        require((signature == null) != (cipher == null)) { "exactly one authenticated signing operation is required" }
    }

    override fun close() {
        requestFingerprint.fill(0)
        (operation as? PreparedSignatureOperation.Wrapped)?.unwrap?.close()
    }
}

internal sealed interface PreparedSignatureOperation {
    data object Direct : PreparedSignatureOperation
    data class Wrapped(val unwrap: PreparedWrappedOperationalUnwrap) : PreparedSignatureOperation
}

class PreparedSshKeyExport internal constructor(
    val providerKeyId: String,
    val cipher: Cipher,
    internal val publicHash: ByteArray,
    internal val unwrap: PreparedSshKeyUnwrap,
    internal val securityLevel: SshStorageSecurityLevel,
)

sealed interface SshKeyStorageResult {
    data class Stored(val descriptor: SshKeyDescriptor) : SshKeyStorageResult
    data class AuthenticationRequired(val prepared: PreparedSshKeyStorage) : SshKeyStorageResult
}

internal interface SshProviderStoreOwner

class PreparedSshKeyStorage internal constructor(
    val cipher: Cipher?,
    val signature: Signature?,
    val promptAuthenticators: Int,
    internal val owner: SshProviderStoreOwner,
    internal val storeResetEpoch: Long,
    internal val provisioning: PendingSshKeyProvisioning,
    internal val stage: PreparedStorageStage,
) {
    init {
        require((cipher == null) != (signature == null)) { "exactly one authenticated operation is required" }
    }
}

data class SshKeyStoreResetResult(
    val removedKeyCount: Int,
    val removedRequestIds: List<String>,
)

sealed interface SshImportApprovalOutcome {
    data object Completed : SshImportApprovalOutcome
    data class AuthenticationRequired(val prepared: PreparedSshImportStorage) : SshImportApprovalOutcome
}

class PreparedSshImportStorage internal constructor(
    val keyStorage: PreparedSshKeyStorage,
    internal val requestId: String,
    internal val requestFingerprint: ByteArray,
    internal val requesterClientId: ClientId,
    internal val publicKeyBlob: ByteArray,
)

internal sealed interface PreparedStorageStage {
    data class OperationalSelfTest(
        val signature: Signature,
        val challenge: ByteArray,
        val publicKey: PublicKey,
        val strongBox: Boolean,
    ) : PreparedStorageStage

    data class OperationalWrapEncrypt(
        val protection: PreparedWrappedOperationalProtection,
        val strongBox: Boolean,
    ) : PreparedStorageStage

    data class OperationalWrapDecrypt(
        val unwrap: PreparedWrappedOperationalUnwrap,
        val material: ProtectedSshKeyMaterial,
        val strongBox: Boolean,
    ) : PreparedStorageStage

    data class ExportEncrypt(
        val protection: PreparedSshKeyProtection,
        val strongBox: Boolean,
    ) : PreparedStorageStage

    data class ExportDecrypt(
        val unwrap: PreparedSshKeyUnwrap,
        val material: ProtectedSshKeyMaterial,
        val strongBox: Boolean,
    ) : PreparedStorageStage
}

internal data class PendingSshKeyRecord(
    val providerKeyId: String,
    val publicBlob: ByteArray,
    val publicHash: ByteArray,
    val algorithm: SshKeyAlgorithm,
    val displayName: String,
    val origin: SshKeyOrigin,
    val operationalProvider: SshOperationalKeyProvider,
    val operationalSecurityLevel: SshStorageSecurityLevel,
    val operationalStrongBoxAttempted: Boolean,
    val operationalStrongBoxFallback: Boolean,
    val userVerificationPolicy: SshUserVerificationPolicy,
    val keyAlias: String,
    val createdAt: Long,
    val expiresAt: Long?,
)

internal class PendingSshKeyProvisioning(
    var record: PendingSshKeyRecord,
    val privateKeyPkcs8: SensitiveBytes?,
    val sourcePublicKey: PublicKey?,
    val exportCopyBackendPolicy: SshExportCopyBackendPolicy?,
    val rsaKeySizeBits: Int,
) : AutoCloseable {
    var wrappedOperationalMaterial: ProtectedSshKeyMaterial? = null
    var exportMaterial: ProtectedSshKeyMaterial? = null
    var exportStrongBoxAttempted: Boolean = false
    var exportStrongBoxFallback: Boolean = false
    var finished: Boolean = false

    override fun close() {
        privateKeyPkcs8?.close()
        wrappedOperationalMaterial?.ciphertext?.fill(0)
        wrappedOperationalMaterial?.nonce?.fill(0)
        wrappedOperationalMaterial = null
        exportMaterial?.ciphertext?.fill(0)
        exportMaterial?.nonce?.fill(0)
        exportMaterial = null
    }
}

internal class SshOperationalCandidateException(
    val strongBox: Boolean,
    cause: Exception,
    val stage: SshOperationalCandidateStage = SshOperationalCandidateStage.OTHER,
) : Exception(
    "Android Keystore could not create the requested SSH operational-key candidate: ${cause.failureSummary()}",
    cause,
)

internal class SshOperationalOperationException(cause: Exception) : Exception(
    "Android Keystore SSH signing operation failed: ${cause.failureSummary()}",
    cause,
)

internal enum class SshOperationalCandidateStage {
    DIRECT_PRIVATE_KEY_IMPORT,
    OTHER,
}

internal class SshHardwareBackedKeystoreUnavailableException(keyPurpose: String) :
    IllegalStateException("$keyPurpose is not hardware-backed")

internal fun Throwable.isHardwareBackedSshKeystoreUnavailable(): Boolean =
    generateSequence(this) { it.cause }.any { it is SshHardwareBackedKeystoreUnavailableException }

private fun Throwable.failureSummary(): String =
    message?.takeIf(String::isNotBlank) ?: javaClass.simpleName
