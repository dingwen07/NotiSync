package net.extrawdw.notisync.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.ALWAYS
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.cbor.CborLabel

/** Stable bounds for SSH Agent protocol v1. */
object SshAgentLimits {
    const val PROTOCOL_VERSION = 1
    const val REQUEST_ID_HEX_LENGTH = 32
    const val DIGEST_BYTES = 32
    const val INVENTORY_NONCE_BYTES = 32
    const val MAX_PROVIDERS = 64
    const val MAX_KEYS_PER_SNAPSHOT = 512
    const val MAX_REMEMBERED_NAMESPACES = 64
    const val MAX_BINDING_CHAIN = 16
    const val MAX_HOST_ALIASES = 32
    const val MAX_PUBLIC_KEY_BLOB_BYTES = 16 * 1024
    const val MAX_SIGNATURE_BLOB_BYTES = 16 * 1024
    const val MAX_SIGN_DATA_BYTES = 256 * 1024
    const val MAX_IMPORT_BYTES = 256 * 1024
    const val MAX_DISPLAY_NAME_UTF8_BYTES = 256
    const val MAX_CONTEXT_TEXT_UTF8_BYTES = 1024
    const val MAX_FAILURE_MESSAGE_UTF8_BYTES = 2048
    const val MAX_SIGN_LIFETIME_MILLIS = 5 * 60_000L
    const val MAX_IMPORT_LIFETIME_MILLIS = 10 * 60_000L
    const val MAX_KEYS_REQUEST_LIFETIME_MILLIS = 60_000L
    const val MAX_FORGET_LIFETIME_MILLIS = 5 * 60_000L
    const val MAX_AGENT_ADD_LIFETIME_SECONDS = 7 * 24 * 60 * 60L

    val HIGH_PROVIDER_CAPABILITIES: Set<Capability> = setOf(
        Capability.CAPABILITY_ROUTING_V1,
        Capability.SSH_KEY_PROVIDER_V1,
        Capability.PUSH_FILTERING,
    )
    val NORMAL_PROVIDER_CAPABILITIES: Set<Capability> = setOf(
        Capability.CAPABILITY_ROUTING_V1,
        Capability.SSH_KEY_PROVIDER_V1,
    )
}

/** Selects exactly one payload on [SshAgentSync]. Append-only. */
@Serializable
enum class SshAgentSyncKind {
    KEYS_REQUEST, KEYS_SNAPSHOT, SIGN_REQUEST, SIGN_RESULT, SIGN_REQUEST_CANCELLED,
    IMPORT_REQUEST, IMPORT_RESULT, FORGET_AUTHORIZATION, FORGET_RESULT,
}

@Serializable
enum class SshKeyAlgorithm { SSH_ED25519, SSH_RSA, ECDSA_NISTP256 }
@Serializable
enum class SshSignatureAlgorithm { SSH_ED25519, RSA_SHA2_256, RSA_SHA2_512, ECDSA_NISTP256, RSA_SHA1_LEGACY }
@Serializable
enum class SshKeyOrigin { GENERATED, SAF_IMPORT, DATA_SYNC_FILE, AGENT_ADD }
@Serializable
enum class SshOperationalKeyProvider {
    /** The non-exportable private key signs entirely through Android Keystore. */
    ANDROID_KEYSTORE_PRIVATE_KEY,

    /** Android Keystore AES protects PKCS#8 at rest; each signature briefly unwraps it in the app process. */
    ANDROID_KEYSTORE_AES_WRAPPED,
}
@Serializable
enum class SshStorageSecurityLevel { STRONGBOX, TRUSTED_ENVIRONMENT }
@Serializable
enum class SshExportCopyBackendPolicy { BEST_AVAILABLE, TEE_ONLY }
@Serializable
enum class SshExportCopyAuthentication { STRONG_BIOMETRIC_OR_DEVICE_CREDENTIAL_PER_USE }
@Serializable
enum class SshApprovalPolicy { ALWAYS_ASK, ALLOW_REMEMBER }
@Serializable
enum class SshUserVerificationPolicy { NONE, PER_USE }
@Serializable
enum class SshRememberScope {
    /** Persisted authorization for this key and requesting NotiSync peer. */
    PEER,

    /** Persisted authorization additionally constrained to a verified SSH server host-key fingerprint. */
    PEER_HOST_KEY,

    /**
     * Reserved for a future requester-reported process-tree heuristic. Providers must keep this scope in memory;
     * process identity is convenience context, not a security boundary.
     */
    APPLICATION_PROCESS,
}
@Serializable
enum class SshProviderHealth { HEALTHY, DEGRADED, DISABLED }
@Serializable
enum class SshDestinationProvenance {
    VERIFIED_SESSION_BIND, SIGNED_USERAUTH, KNOWN_HOSTS_MATCH, PROCESS_HINT, UNKNOWN,
}
@Serializable
enum class SshConnectionDirection { DIRECT, FORWARDED, UNKNOWN }
@Serializable
enum class SshHostAliasSource { KNOWN_HOSTS_PLAIN, KNOWN_HOSTS_HASH_CONFIRMED, PROCESS_ARGUMENT }
@Serializable
enum class SshRememberDisposition {
    NONE,
    MATCHED_PEER,
    MATCHED_PEER_HOST_KEY,
    MATCHED_APPLICATION_PROCESS,
    CREATED_PEER,
    CREATED_PEER_HOST_KEY,
    CREATED_APPLICATION_PROCESS,
    NOT_ALLOWED_FOR_KEY,
}
@Serializable
enum class SshSignResultKind { SIGNED, REJECTED_BY_USER, PROVIDER_FAILURE }
@Serializable
enum class SshUserRejectionReason { USER_TAPPED_REJECT }
@Serializable
enum class SshProviderFailureCode {
    NOT_OWNER, KEY_NOT_FOUND, UNSUPPORTED_ALGORITHM, UNSUPPORTED_FLAGS, KEY_INVALIDATED,
    USER_VERIFICATION_CANCELLED, USER_VERIFICATION_LOCKOUT, REQUEST_EXPIRED, PROVIDER_BUSY, INTERNAL_FAILURE,
}
@Serializable
enum class SshSignCancellationReason {
    SIGNED_ELSEWHERE, REJECTED_ELSEWHERE, REQUEST_TIMEOUT, AGENT_LOCKED, CALLER_DISCONNECTED, AGENT_SHUTDOWN,
}
@Serializable
enum class SshImportSourceType { PRIVATE_KEY_FILE, AGENT_IDENTITY }
@Serializable
enum class SshImportResultKind { IMPORTED, ALREADY_PRESENT, USER_DECLINED, UNSUPPORTED, EXPIRED, FAILED }
@Serializable
enum class SshForgetResultKind { APPLIED, EXPIRED, FAILED }

@Serializable
data class SshRememberedNamespace(
    @CborLabel(0) val requesterClientId: ClientId,
    @CborLabel(1) val authorizationGeneration: String,
    @CborLabel(2) val authorizationEpoch: Long,
    @CborLabel(3) val scopes: List<SshRememberScope>,
) {
    fun validationError(): String? = when {
        requesterClientId.value.isBlank() -> "remembered requesterClientId must not be blank"
        !authorizationGeneration.isSshOperationId() -> "invalid remembered authorization generation"
        authorizationEpoch < 0 -> "remembered authorization epoch must be non-negative"
        scopes.isEmpty() || scopes.size > SshRememberScope.entries.size || scopes.distinct().size != scopes.size ->
            "remembered scopes must be non-empty and unique"
        else -> null
    }
}

@Serializable
data class SshOperationalKeyProtection(
    @CborLabel(0) val provider: SshOperationalKeyProvider,
    @CborLabel(1) val securityLevel: SshStorageSecurityLevel,
    @CborLabel(2) val userVerificationPolicy: SshUserVerificationPolicy,
    @CborLabel(3) val strongBoxAttempted: Boolean,
    @CborLabel(4) val strongBoxFallback: Boolean,
) {
    fun validationError(): String? = when {
        securityLevel != SshStorageSecurityLevel.STRONGBOX &&
            securityLevel != SshStorageSecurityLevel.TRUSTED_ENVIRONMENT ->
            "operational key must be hardware-backed"
        securityLevel == SshStorageSecurityLevel.STRONGBOX && !strongBoxAttempted ->
            "operational StrongBox storage requires an attempt"
        strongBoxFallback && !strongBoxAttempted -> "operational StrongBox fallback requires an attempt"
        strongBoxFallback && securityLevel != SshStorageSecurityLevel.TRUSTED_ENVIRONMENT ->
            "operational StrongBox fallback must end in TEE"
        strongBoxAttempted && !strongBoxFallback && securityLevel != SshStorageSecurityLevel.STRONGBOX ->
            "successful operational StrongBox attempt must report StrongBox"
        else -> null
    }
}

@Serializable
data class SshExportCopyProtection(
    @CborLabel(0) val securityLevel: SshStorageSecurityLevel,
    @CborLabel(1) val backendPolicy: SshExportCopyBackendPolicy,
    @CborLabel(2) val authentication: SshExportCopyAuthentication,
    @CborLabel(3) val strongBoxAttempted: Boolean,
    @CborLabel(4) val strongBoxFallback: Boolean,
) {
    fun validationError(): String? = when {
        securityLevel != SshStorageSecurityLevel.STRONGBOX &&
            securityLevel != SshStorageSecurityLevel.TRUSTED_ENVIRONMENT ->
            "export copy must be hardware-encrypted"
        securityLevel == SshStorageSecurityLevel.STRONGBOX && !strongBoxAttempted ->
            "export-copy StrongBox storage requires an attempt"
        backendPolicy == SshExportCopyBackendPolicy.TEE_ONLY && strongBoxAttempted ->
            "TEE-only export copy cannot attempt StrongBox"
        backendPolicy == SshExportCopyBackendPolicy.TEE_ONLY &&
            securityLevel != SshStorageSecurityLevel.TRUSTED_ENVIRONMENT ->
            "TEE-only export copy must use TEE"
        strongBoxFallback && !strongBoxAttempted -> "export-copy StrongBox fallback requires an attempt"
        strongBoxFallback && securityLevel != SshStorageSecurityLevel.TRUSTED_ENVIRONMENT ->
            "export-copy StrongBox fallback must end in TEE"
        strongBoxAttempted && !strongBoxFallback && securityLevel != SshStorageSecurityLevel.STRONGBOX ->
            "successful export-copy StrongBox attempt must report StrongBox"
        else -> null
    }
}

@Serializable
data class SshKeyDescriptor(
    @CborLabel(0) val providerKeyId: String,
    @CborLabel(1) @ByteString val publicKeyBlob: ByteArray,
    @CborLabel(2) @ByteString val publicKeyBlobSha256: ByteArray,
    @CborLabel(3) val algorithm: SshKeyAlgorithm,
    @CborLabel(4) val displayName: String,
    @CborLabel(5) val origin: SshKeyOrigin,
    @CborLabel(6) val operationalKey: SshOperationalKeyProtection,
    @CborLabel(7) val exportCopy: SshExportCopyProtection?,
    @CborLabel(8) val approvalPolicy: SshApprovalPolicy,
    @CborLabel(9) val rememberedNamespaces: List<SshRememberedNamespace> = emptyList(),
    @CborLabel(10) val createdAt: Long,
) {
    fun validationError(sha256: ((ByteArray) -> ByteArray)? = null): String? = when {
        !providerKeyId.isSshOperationId() -> "invalid provider key id"
        publicKeyBlob.isEmpty() || publicKeyBlob.size > SshAgentLimits.MAX_PUBLIC_KEY_BLOB_BYTES ->
            "public key blob is outside the allowed bounds"
        publicKeyBlobSha256.size != SshAgentLimits.DIGEST_BYTES -> "invalid public key digest length"
        sha256 != null && !publicKeyBlobSha256.contentEquals(sha256(publicKeyBlob)) -> "public key digest mismatch"
        displayName.isBlank() || !displayName.isBoundedSshDisplayText(SshAgentLimits.MAX_DISPLAY_NAME_UTF8_BYTES) ->
            "display name is outside the allowed bounds"
        rememberedNamespaces.size > SshAgentLimits.MAX_REMEMBERED_NAMESPACES -> "too many remembered namespaces"
        rememberedNamespaces.any { it.validationError() != null } -> "invalid remembered namespace"
        rememberedNamespaces.map { Triple(it.requesterClientId, it.authorizationGeneration, it.authorizationEpoch) }
            .distinct().size != rememberedNamespaces.size -> "duplicate remembered namespace"
        operationalKey.validationError() != null -> operationalKey.validationError()
        exportCopy?.validationError() != null -> exportCopy.validationError()
        operationalKey.provider == SshOperationalKeyProvider.ANDROID_KEYSTORE_AES_WRAPPED &&
            algorithm != SshKeyAlgorithm.SSH_ED25519 ->
            "wrapped operational storage is allowed only for Ed25519"
        operationalKey.userVerificationPolicy == SshUserVerificationPolicy.PER_USE &&
            approvalPolicy == SshApprovalPolicy.ALLOW_REMEMBER -> "per-use verification cannot allow remember"
        createdAt <= 0 -> "createdAt must be positive"
        else -> null
    }
}

@Serializable
data class SshKeysRequest(
    @CborLabel(0) val requestId: String,
    @CborLabel(1) val requesterClientId: ClientId,
    @CborLabel(2) val requestedAt: Long,
    @CborLabel(3) val expiresAt: Long,
    @CborLabel(4) val startup: Boolean,
    @CborLabel(5) val targetProviderClientIds: List<ClientId>,
    @CborLabel(6) @ByteString val requesterInventoryNonce: ByteArray,
) {
    fun validationError(): String? = when {
        !requestId.isSshOperationId() -> "invalid keys request id"
        requesterClientId.value.isBlank() -> "requesterClientId must not be blank"
        !validSshLifetime(requestedAt, expiresAt, SshAgentLimits.MAX_KEYS_REQUEST_LIFETIME_MILLIS) ->
            "invalid keys request lifetime"
        !targetProviderClientIds.isCanonicalProviderList(requesterClientId) ->
            "target provider list must be canonical, bounded, and exclude the requester"
        requesterInventoryNonce.size != SshAgentLimits.INVENTORY_NONCE_BYTES -> "invalid inventory nonce length"
        else -> null
    }
}

@Serializable
data class SshKeysSnapshot(
    @CborLabel(0) val providerClientId: ClientId,
    @CborLabel(1) val inventoryGeneration: String,
    @CborLabel(2) val revision: Long,
    @CborLabel(3) val generatedAt: Long,
    @CborLabel(4) val respondingToRequestId: String? = null,
    @CborLabel(5) val keys: List<SshKeyDescriptor>,
    @CborLabel(6) val providerHealth: SshProviderHealth,
) {
    fun validationError(sha256: ((ByteArray) -> ByteArray)? = null): String? = when {
        providerClientId.value.isBlank() -> "providerClientId must not be blank"
        !inventoryGeneration.isSshOperationId() -> "invalid inventory generation"
        revision <= 0 -> "snapshot revision must be positive"
        generatedAt <= 0 -> "snapshot generatedAt must be positive"
        respondingToRequestId != null && !respondingToRequestId.isSshOperationId() -> "invalid responding request id"
        keys.size > SshAgentLimits.MAX_KEYS_PER_SNAPSHOT -> "too many keys in snapshot"
        keys.any { it.validationError(sha256) != null } -> "invalid key descriptor"
        keys.map { it.providerKeyId }.distinct().size != keys.size -> "duplicate provider key id"
        keys.map { it.publicKeyBlobSha256.toList() }.distinct().size != keys.size ->
            "duplicate public key in provider snapshot"
        else -> null
    }
}

@Serializable
data class SshHostAlias(
    @CborLabel(0) val value: String,
    @CborLabel(1) val source: SshHostAliasSource,
) {
    fun validationError(): String? =
        if (value.isBlank() || !value.isBoundedSshDisplayText(SshAgentLimits.MAX_CONTEXT_TEXT_UTF8_BYTES)) {
            "host alias is outside the allowed bounds"
        } else null
}

@Serializable
data class SshVerifiedBinding(
    @CborLabel(0) @ByteString val hostKeyBlobSha256: ByteArray,
    @CborLabel(1) val forwarded: Boolean,
) {
    fun validationError(): String? =
        if (hostKeyBlobSha256.size == SshAgentLimits.DIGEST_BYTES) null else "invalid binding host-key digest"
}

@Serializable
data class SshDestinationContext(
    @CborLabel(0) val provenance: SshDestinationProvenance,
    @CborLabel(1) val connectionDirection: SshConnectionDirection,
    @CborLabel(2) val username: String? = null,
    @CborLabel(3) val service: String? = null,
    @CborLabel(4) val authenticationMethod: String? = null,
    @CborLabel(5) @ByteString val sessionIdSha256: ByteArray? = null,
    @CborLabel(6) @ByteString val serverHostKeyBlob: ByteArray? = null,
    @CborLabel(7) @ByteString val serverHostKeyBlobSha256: ByteArray? = null,
    @CborLabel(8) val hostAliases: List<SshHostAlias> = emptyList(),
    @CborLabel(9) val bindingChain: List<SshVerifiedBinding> = emptyList(),
) {
    fun validationError(sha256: ((ByteArray) -> ByteArray)? = null): String? = when {
        listOfNotNull(username, service, authenticationMethod).any {
            it.isBlank() || !it.isBoundedSshDisplayText(SshAgentLimits.MAX_CONTEXT_TEXT_UTF8_BYTES)
        } -> "destination text is outside the allowed bounds"
        sessionIdSha256 != null && sessionIdSha256.size != SshAgentLimits.DIGEST_BYTES -> "invalid session id digest"
        serverHostKeyBlob != null &&
            (serverHostKeyBlob.isEmpty() || serverHostKeyBlob.size > SshAgentLimits.MAX_PUBLIC_KEY_BLOB_BYTES) ->
            "server host key is outside the allowed bounds"
        (serverHostKeyBlob == null) != (serverHostKeyBlobSha256 == null) ->
            "server host key and digest must appear together"
        serverHostKeyBlobSha256 != null && serverHostKeyBlobSha256.size != SshAgentLimits.DIGEST_BYTES ->
            "invalid server host-key digest"
        sha256 != null && serverHostKeyBlob != null &&
            !serverHostKeyBlobSha256!!.contentEquals(sha256(serverHostKeyBlob)) -> "server host-key digest mismatch"
        provenance == SshDestinationProvenance.VERIFIED_SESSION_BIND && serverHostKeyBlob == null ->
            "verified session-bind provenance requires a host key"
        hostAliases.size > SshAgentLimits.MAX_HOST_ALIASES || hostAliases.any { it.validationError() != null } ->
            "invalid host aliases"
        bindingChain.size > SshAgentLimits.MAX_BINDING_CHAIN || bindingChain.any { it.validationError() != null } ->
            "invalid binding chain"
        else -> null
    }
}

@Serializable
data class SshSignRequest(
    @CborLabel(0) val requestId: String,
    @CborLabel(1) val requesterClientId: ClientId,
    @CborLabel(2) val requestedAt: Long,
    @CborLabel(3) val expiresAt: Long,
    @CborLabel(4) @ByteString val publicKeyBlob: ByteArray,
    @CborLabel(5) @ByteString val data: ByteArray,
    @CborLabel(6) val flags: Long,
    @CborLabel(7) val requestedSignatureAlgorithm: SshSignatureAlgorithm,
    @CborLabel(8) val eligibleProviderClientIds: List<ClientId>,
    @CborLabel(9) val authorizationGeneration: String,
    @CborLabel(10) val authorizationEpoch: Long,
    @CborLabel(11) val processContext: DesktopProcessContext,
    @CborLabel(12) val destinationContext: SshDestinationContext,
    @CborLabel(13) val connectionId: String,
    @CborLabel(14) val confirmationRequired: Boolean = false,
) {
    fun validationError(sha256: ((ByteArray) -> ByteArray)? = null): String? = when {
        !requestId.isSshOperationId() -> "invalid sign request id"
        requesterClientId.value.isBlank() -> "requesterClientId must not be blank"
        !validSshLifetime(requestedAt, expiresAt, SshAgentLimits.MAX_SIGN_LIFETIME_MILLIS) ->
            "invalid sign request lifetime"
        publicKeyBlob.isEmpty() || publicKeyBlob.size > SshAgentLimits.MAX_PUBLIC_KEY_BLOB_BYTES ->
            "public key blob is outside the allowed bounds"
        data.isEmpty() || data.size > SshAgentLimits.MAX_SIGN_DATA_BYTES -> "sign data is outside the allowed bounds"
        flags !in 0..0xffff_ffffL -> "sign flags must fit uint32"
        !eligibleProviderClientIds.isCanonicalProviderList(requesterClientId) ->
            "eligible provider list must be canonical, bounded, and exclude requester"
        !authorizationGeneration.isSshOperationId() -> "invalid authorization generation"
        authorizationEpoch < 0 -> "authorization epoch must be non-negative"
        !connectionId.isSshOperationId() -> "invalid local connection id"
        processContext.validationError() != null -> "invalid process context"
        destinationContext.validationError(sha256) != null -> "invalid destination context"
        else -> null
    }
}

@Serializable
data class SshSignatureResult(
    @CborLabel(0) @ByteString val signatureBlob: ByteArray,
    @CborLabel(1) val rememberDisposition: SshRememberDisposition,
    @CborLabel(2) val authorizationGeneration: String,
    @CborLabel(3) val authorizationEpoch: Long,
) {
    fun validationError(): String? = when {
        signatureBlob.isEmpty() || signatureBlob.size > SshAgentLimits.MAX_SIGNATURE_BLOB_BYTES ->
            "signature blob is outside the allowed bounds"
        !authorizationGeneration.isSshOperationId() -> "invalid result authorization generation"
        authorizationEpoch < 0 -> "result authorization epoch must be non-negative"
        else -> null
    }
}

@Serializable
data class SshUserRejection(@CborLabel(0) val reason: SshUserRejectionReason)

@Serializable
data class SshProviderFailure(
    @CborLabel(0) val code: SshProviderFailureCode,
    @CborLabel(1) val retryable: Boolean = false,
    @CborLabel(2) val message: String? = null,
) {
    fun validationError(): String? =
        if (message != null && !message.isBoundedSshDisplayText(SshAgentLimits.MAX_FAILURE_MESSAGE_UTF8_BYTES)) {
            "provider failure message is outside the allowed bounds"
        } else null
}

@Serializable
data class SshSignResult(
    @CborLabel(0) val requestId: String,
    @CborLabel(1) val requesterClientId: ClientId,
    @CborLabel(2) @ByteString val publicKeyBlobSha256: ByteArray,
    @CborLabel(3) val kind: SshSignResultKind,
    @CborLabel(4) val resultAt: Long,
    @CborLabel(5) val providerClientId: ClientId,
    @CborLabel(6) val signature: SshSignatureResult? = null,
    @CborLabel(7) val rejection: SshUserRejection? = null,
    @CborLabel(8) val failure: SshProviderFailure? = null,
) {
    fun validationError(): String? {
        if (!requestId.isSshOperationId()) return "invalid sign result request id"
        if (requesterClientId.value.isBlank()) return "result requesterClientId must not be blank"
        if (publicKeyBlobSha256.size != SshAgentLimits.DIGEST_BYTES) return "invalid public key digest"
        if (resultAt <= 0) return "resultAt must be positive"
        if (providerClientId.value.isBlank() || providerClientId == requesterClientId) return "invalid providerClientId"
        return when (kind) {
            SshSignResultKind.SIGNED -> when {
                signature == null || rejection != null || failure != null -> "SIGNED requires only signature details"
                else -> signature.validationError()
            }
            SshSignResultKind.REJECTED_BY_USER ->
                if (rejection == null || signature != null || failure != null) {
                    "REJECTED_BY_USER requires only rejection details"
                } else null
            SshSignResultKind.PROVIDER_FAILURE -> when {
                failure == null || signature != null || rejection != null ->
                    "PROVIDER_FAILURE requires only failure details"
                else -> failure.validationError()
            }
        }
    }
}

@Serializable
data class SshSignRequestCancelled(
    @CborLabel(0) val requestId: String,
    @CborLabel(1) val requesterClientId: ClientId,
    @CborLabel(2) val cancelledAt: Long,
    @CborLabel(3) val reason: SshSignCancellationReason,
    @CborLabel(4) val targetProviderClientIds: List<ClientId>,
) {
    fun validationError(): String? = when {
        !requestId.isSshOperationId() -> "invalid cancelled request id"
        requesterClientId.value.isBlank() -> "cancel requesterClientId must not be blank"
        cancelledAt <= 0 -> "cancelledAt must be positive"
        !targetProviderClientIds.isCanonicalProviderList(requesterClientId) ->
            "cancel target provider list must be canonical, bounded, and exclude requester"
        else -> null
    }
}

@Serializable
data class SshImportConstraints(
    @CborLabel(0) val lifetimeSeconds: Long?,
    @CborLabel(1) val confirmationRequired: Boolean,
) {
    fun validationError(): String? =
        if (lifetimeSeconds != null && lifetimeSeconds !in 1..SshAgentLimits.MAX_AGENT_ADD_LIFETIME_SECONDS) {
            "agent identity lifetime is outside the allowed bounds"
        } else null
}

@Serializable
data class SshImportRequest(
    @CborLabel(0) val requestId: String,
    @CborLabel(1) val requesterClientId: ClientId,
    @CborLabel(2) val requestedAt: Long,
    @CborLabel(3) val expiresAt: Long,
    @CborLabel(4) val sourceType: SshImportSourceType,
    @CborLabel(5) @ByteString val fileBytes: ByteArray? = null,
    @CborLabel(6) @ByteString val agentIdentity: ByteArray? = null,
    @CborLabel(7) val constraints: SshImportConstraints? = null,
    @CborLabel(8) val suggestedName: String? = null,
) {
    fun validationError(): String? {
        if (!requestId.isSshOperationId()) return "invalid import request id"
        if (requesterClientId.value.isBlank()) return "import requesterClientId must not be blank"
        if (!validSshLifetime(requestedAt, expiresAt, SshAgentLimits.MAX_IMPORT_LIFETIME_MILLIS)) {
            return "invalid import request lifetime"
        }
        if (suggestedName != null &&
            (suggestedName.isBlank() || !suggestedName.isBoundedSshDisplayText(SshAgentLimits.MAX_DISPLAY_NAME_UTF8_BYTES))
        ) return "suggested key name is outside the allowed bounds"
        if (constraints?.validationError() != null) return "invalid import constraints"
        return when (sourceType) {
            SshImportSourceType.PRIVATE_KEY_FILE -> when {
                fileBytes == null || fileBytes.isEmpty() || fileBytes.size > SshAgentLimits.MAX_IMPORT_BYTES ->
                    "private key file is outside the allowed bounds"
                agentIdentity != null || constraints != null -> "private key file import contains agent-only fields"
                else -> null
            }
            SshImportSourceType.AGENT_IDENTITY -> when {
                agentIdentity == null || agentIdentity.isEmpty() || agentIdentity.size > SshAgentLimits.MAX_IMPORT_BYTES ->
                    "agent identity is outside the allowed bounds"
                fileBytes != null -> "agent identity import contains file bytes"
                else -> null
            }
        }
    }
}

@Serializable
data class SshImportResult(
    @CborLabel(0) val requestId: String,
    @CborLabel(1) val requesterClientId: ClientId,
    @CborLabel(2) val providerClientId: ClientId,
    @CborLabel(3) val resultAt: Long,
    @CborLabel(4) val kind: SshImportResultKind,
    @CborLabel(5) val providerKeyId: String? = null,
    @CborLabel(6) @ByteString val publicKeyBlob: ByteArray? = null,
    @CborLabel(7) val message: String? = null,
) {
    fun validationError(): String? {
        if (!requestId.isSshOperationId()) return "invalid import result request id"
        if (requesterClientId.value.isBlank()) return "import result requesterClientId must not be blank"
        if (providerClientId.value.isBlank() || providerClientId == requesterClientId) return "invalid import provider"
        if (resultAt <= 0) return "import resultAt must be positive"
        if (message != null && !message.isBoundedSshDisplayText(SshAgentLimits.MAX_FAILURE_MESSAGE_UTF8_BYTES)) {
            return "import result message is outside the allowed bounds"
        }
        val successful = kind == SshImportResultKind.IMPORTED || kind == SshImportResultKind.ALREADY_PRESENT
        return when {
            successful && (providerKeyId?.isSshOperationId() != true || publicKeyBlob == null ||
                publicKeyBlob.isEmpty() || publicKeyBlob.size > SshAgentLimits.MAX_PUBLIC_KEY_BLOB_BYTES) ->
                "successful import result requires a valid key id and public blob"
            !successful && (providerKeyId != null || publicKeyBlob != null) ->
                "unsuccessful import result must omit key details"
            else -> null
        }
    }
}

@Serializable
data class SshForgetAuthorization(
    @CborLabel(0) val requestId: String,
    @CborLabel(1) val requesterClientId: ClientId,
    @CborLabel(2) val authorizationGeneration: String,
    @CborLabel(3) val invalidatedThroughEpoch: Long,
    @CborLabel(4) val requestedAt: Long,
    @CborLabel(5) val expiresAt: Long,
    @CborLabel(6) val targetProviderClientIds: List<ClientId>,
) {
    fun validationError(): String? = when {
        !requestId.isSshOperationId() -> "invalid forget request id"
        requesterClientId.value.isBlank() -> "forget requesterClientId must not be blank"
        !authorizationGeneration.isSshOperationId() -> "invalid forget authorization generation"
        invalidatedThroughEpoch < 0 -> "invalidated authorization epoch must be non-negative"
        !validSshLifetime(requestedAt, expiresAt, SshAgentLimits.MAX_FORGET_LIFETIME_MILLIS) ->
            "invalid forget request lifetime"
        !targetProviderClientIds.isCanonicalProviderList(requesterClientId) ->
            "forget target list must be canonical, bounded, and exclude requester"
        else -> null
    }
}

@Serializable
data class SshForgetResult(
    @CborLabel(0) val requestId: String,
    @CborLabel(1) val requesterClientId: ClientId,
    @CborLabel(2) val providerClientId: ClientId,
    @CborLabel(3) val resultAt: Long,
    @CborLabel(4) val kind: SshForgetResultKind,
    @CborLabel(5) val invalidatedThroughEpoch: Long,
) {
    fun validationError(): String? = when {
        !requestId.isSshOperationId() -> "invalid forget result request id"
        requesterClientId.value.isBlank() -> "forget result requesterClientId must not be blank"
        providerClientId.value.isBlank() || providerClientId == requesterClientId -> "invalid forget result provider"
        resultAt <= 0 -> "forget resultAt must be positive"
        invalidatedThroughEpoch < 0 -> "forget result epoch must be non-negative"
        else -> null
    }
}

/** Flat, non-polymorphic SSH Agent union carried by [DataSync.sshAgent]. */
@Serializable
data class SshAgentSync(
    @CborLabel(0) @EncodeDefault(ALWAYS) val protocolVersion: Int = SshAgentLimits.PROTOCOL_VERSION,
    @CborLabel(1) val kind: SshAgentSyncKind,
    @CborLabel(2) val keysRequest: SshKeysRequest? = null,
    @CborLabel(3) val keysSnapshot: SshKeysSnapshot? = null,
    @CborLabel(4) val signRequest: SshSignRequest? = null,
    @CborLabel(5) val signResult: SshSignResult? = null,
    @CborLabel(6) val signRequestCancelled: SshSignRequestCancelled? = null,
    @CborLabel(7) val importRequest: SshImportRequest? = null,
    @CborLabel(8) val importResult: SshImportResult? = null,
    @CborLabel(9) val forgetAuthorization: SshForgetAuthorization? = null,
    @CborLabel(10) val forgetResult: SshForgetResult? = null,
) {
    fun validationError(sha256: ((ByteArray) -> ByteArray)? = null): String? {
        if (protocolVersion != SshAgentLimits.PROTOCOL_VERSION) return "unsupported SSH Agent protocol version"
        val populated = listOfNotNull(
            keysRequest, keysSnapshot, signRequest, signResult, signRequestCancelled,
            importRequest, importResult, forgetAuthorization, forgetResult,
        )
        if (populated.size != 1) return "SshAgentSync must carry exactly one payload"
        return when (kind) {
            SshAgentSyncKind.KEYS_REQUEST ->
                if (keysRequest == null) "KEYS_REQUEST requires keysRequest" else keysRequest.validationError()
            SshAgentSyncKind.KEYS_SNAPSHOT -> if (keysSnapshot == null) {
                "KEYS_SNAPSHOT requires keysSnapshot"
            } else keysSnapshot.validationError(sha256)
            SshAgentSyncKind.SIGN_REQUEST -> if (signRequest == null) {
                "SIGN_REQUEST requires signRequest"
            } else signRequest.validationError(sha256)
            SshAgentSyncKind.SIGN_RESULT ->
                if (signResult == null) "SIGN_RESULT requires signResult" else signResult.validationError()
            SshAgentSyncKind.SIGN_REQUEST_CANCELLED -> if (signRequestCancelled == null) {
                "SIGN_REQUEST_CANCELLED requires cancellation"
            } else signRequestCancelled.validationError()
            SshAgentSyncKind.IMPORT_REQUEST -> if (importRequest == null) {
                "IMPORT_REQUEST requires importRequest"
            } else importRequest.validationError()
            SshAgentSyncKind.IMPORT_RESULT ->
                if (importResult == null) "IMPORT_RESULT requires importResult" else importResult.validationError()
            SshAgentSyncKind.FORGET_AUTHORIZATION -> if (forgetAuthorization == null) {
                "FORGET_AUTHORIZATION requires forgetAuthorization"
            } else forgetAuthorization.validationError()
            SshAgentSyncKind.FORGET_RESULT ->
                if (forgetResult == null) "FORGET_RESULT requires forgetResult" else forgetResult.validationError()
        }
    }
}

private fun String.isSshOperationId(): Boolean = SSH_OPERATION_ID.matches(this)

private fun String.isBoundedSshDisplayText(maxUtf8Bytes: Int): Boolean =
    encodeToByteArray().size <= maxUtf8Bytes && none { it.isISOControl() }

private fun List<ClientId>.isCanonicalProviderList(requesterClientId: ClientId): Boolean =
    isNotEmpty() && size <= SshAgentLimits.MAX_PROVIDERS && requesterClientId !in this &&
        all { it.value.isNotBlank() } && distinct().size == size &&
        map(ClientId::value) == map(ClientId::value).sorted()

private fun validSshLifetime(requestedAt: Long, expiresAt: Long, maximum: Long): Boolean =
    requestedAt > 0 && expiresAt > requestedAt && expiresAt - requestedAt <= maximum

private val SSH_OPERATION_ID = Regex("[0-9a-f]{${SshAgentLimits.REQUEST_ID_HEX_LENGTH}}")
