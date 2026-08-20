package net.extrawdw.apps.notisync.data.storage.core

internal enum class TrustCleanupState(val token: String) {
    NOT_STARTED("NOT_STARTED"),
    COMPLETE("COMPLETE"),
    BLOCKED("BLOCKED"),
    ;

    companion object {
        fun fromToken(token: String): TrustCleanupState = entries.firstOrNull { it.token == token }
            ?: error("Unknown trust cleanup state token")
    }
}

internal enum class IdentitySecurityLevel(val token: String) {
    STRONGBOX("STRONGBOX"),
    TRUSTED_ENVIRONMENT("TRUSTED_ENVIRONMENT"),
    UNKNOWN("UNKNOWN"),
    ;

    companion object {
        fun fromToken(token: String): IdentitySecurityLevel = entries.firstOrNull { it.token == token }
            ?: error("Unknown identity security level token")
    }
}

internal enum class IdentityLifecycleState(val token: String) {
    PROVISIONING("PROVISIONING"),
    ACTIVE("ACTIVE"),
    RECOVERY_REQUIRED("RECOVERY_REQUIRED"),
    RETIRED("RETIRED"),
    ;

    companion object {
        fun fromToken(token: String): IdentityLifecycleState = entries.firstOrNull { it.token == token }
            ?: error("Unknown identity lifecycle state token")
    }
}

internal enum class CryptoEpochSecurityLevel(val token: String) {
    STRONGBOX("STRONGBOX"),
    TRUSTED_ENVIRONMENT("TRUSTED_ENVIRONMENT"),
    ;

    companion object {
        fun fromToken(token: String): CryptoEpochSecurityLevel = entries.firstOrNull { it.token == token }
            ?: error("Unknown crypto epoch security level token")
    }
}

internal enum class CryptoEpochState(val token: String) {
    PROVISIONING("PROVISIONING"),
    ACTIVE("ACTIVE"),
    RETIRED("RETIRED"),
    DELETING("DELETING"),
    ;

    companion object {
        fun fromToken(token: String): CryptoEpochState = entries.firstOrNull { it.token == token }
            ?: error("Unknown crypto epoch state token")
    }
}

internal enum class KeystoreOperationKind(val token: String) {
    CREATE("CREATE"),
    ACTIVATE("ACTIVATE"),
    RETIRE("RETIRE"),
    DELETE("DELETE"),
    ;

    companion object {
        fun fromToken(token: String): KeystoreOperationKind = entries.firstOrNull { it.token == token }
            ?: error("Unknown Keystore operation kind token")
    }
}

internal enum class KeystoreOperationTarget(val token: String) {
    IDENTITY("IDENTITY"),
    CRYPTO_EPOCH("CRYPTO_EPOCH"),
    WRAPPING_KEY("WRAPPING_KEY"),
    ;

    companion object {
        fun fromToken(token: String): KeystoreOperationTarget = entries.firstOrNull { it.token == token }
            ?: error("Unknown Keystore operation target token")
    }
}

internal enum class KeystoreOperationState(val token: String) {
    PENDING("PENDING"),
    APPLIED("APPLIED"),
    RETRYABLE("RETRYABLE"),
    BLOCKED("BLOCKED"),
    ;

    companion object {
        fun fromToken(token: String): KeystoreOperationState = entries.firstOrNull { it.token == token }
            ?: error("Unknown Keystore operation state token")
    }
}

/** Result of establishing a durable Keystore intent without changing an existing operation. */
internal enum class KeystoreOperationEnsureResult {
    INSERTED,
    EXISTING_PENDING,
    EXISTING_RETRYABLE,
    EXISTING_APPLIED,
    EXISTING_BLOCKED,
    CONFLICT,
}

/** A transition is stale when another reconciler won or the persisted journal moved on. */
internal enum class KeystoreOperationTransitionResult {
    UPDATED,
    STALE,
}

internal data class CoreMaintenanceUpdate(
    val trustCleanupState: TrustCleanupState,
    val trustCleanupCompletedAt: Long? = null,
)

internal data class CoreMaintenanceSnapshot(
    val trustCleanupState: TrustCleanupState,
    val trustCleanupCompletedAt: Long?,
    val updatedAt: Long,
)

internal data class IdentityMetadataInput(
    val keyAlias: String,
    val keyAliasVersion: Int,
    val publicSpki: ByteArray,
    val securityLevel: IdentitySecurityLevel,
    val lifecycleState: IdentityLifecycleState,
    val createdAt: Long,
)

internal data class IdentityMetadataSnapshot(
    val keyAlias: String,
    val keyAliasVersion: Int,
    val publicSpki: ByteArray,
    val clientId: String,
    val securityLevel: IdentitySecurityLevel,
    val lifecycleState: IdentityLifecycleState,
    val createdAt: Long,
    val updatedAt: Long,
)

internal enum class IdentityMetadataSaveResult {
    SAVED,
    ALREADY_CURRENT,
    CONFLICT,
}

/** Stable grammar of the exact identity-signed trust authority. */
internal enum class TrustSignatureFormat(val token: String) {
    TRUSTSTORE_V1_THREE_SECTION("TRUSTSTORE_V1_THREE_SECTION"),
    TRUSTSTORE_V1_FOUR_SECTION("TRUSTSTORE_V1_FOUR_SECTION"),
    ;

    companion object {
        fun fromToken(token: String): TrustSignatureFormat = entries.firstOrNull { it.token == token }
            ?: error("Unknown trust signature format token")
    }
}

/**
 * Exact signed trust sections remain opaque and are atomically replaced. Normalizing or re-encoding them during
 * this storage cutover would change the byte authority verified by the existing signature.
 */
internal sealed class TrustSnapshotInput private constructor(
    entriesUtf8: ByteArray,
    cardsUtf8: ByteArray,
    overlaysUtf8: ByteArray,
    signatureBase64UrlUtf8: ByteArray,
) {
    private val storedEntries = entriesUtf8.copyOf()
    private val storedCards = cardsUtf8.copyOf()
    private val storedOverlays = overlaysUtf8.copyOf()
    private val storedSignature = signatureBase64UrlUtf8.copyOf()

    abstract val signatureFormat: TrustSignatureFormat
    abstract fun epochsUtf8OrNull(): ByteArray?

    internal fun exactBytes(): TrustSnapshotExactBytes = TrustSnapshotExactBytes(
        signatureFormat = signatureFormat,
        entriesUtf8 = storedEntries.copyOf(),
        cardsUtf8 = storedCards.copyOf(),
        overlaysUtf8 = storedOverlays.copyOf(),
        epochsUtf8 = epochsUtf8OrNull(),
        signatureBase64UrlUtf8 = storedSignature.copyOf(),
    )

    class ThreeSection(
        entriesUtf8: ByteArray,
        cardsUtf8: ByteArray,
        overlaysUtf8: ByteArray,
        signatureBase64UrlUtf8: ByteArray,
    ) : TrustSnapshotInput(entriesUtf8, cardsUtf8, overlaysUtf8, signatureBase64UrlUtf8) {
        override val signatureFormat: TrustSignatureFormat =
            TrustSignatureFormat.TRUSTSTORE_V1_THREE_SECTION

        override fun epochsUtf8OrNull(): ByteArray? = null
    }

    class FourSection(
        entriesUtf8: ByteArray,
        cardsUtf8: ByteArray,
        overlaysUtf8: ByteArray,
        epochsUtf8: ByteArray,
        signatureBase64UrlUtf8: ByteArray,
    ) : TrustSnapshotInput(entriesUtf8, cardsUtf8, overlaysUtf8, signatureBase64UrlUtf8) {
        private val storedEpochs = epochsUtf8.copyOf()

        override val signatureFormat: TrustSignatureFormat =
            TrustSignatureFormat.TRUSTSTORE_V1_FOUR_SECTION

        override fun epochsUtf8OrNull(): ByteArray = storedEpochs.copyOf()
    }
}

/** Immutable projection of the exact verified row plus its repository-computed identity digest. */
internal class TrustSnapshot(
    val signatureFormat: TrustSignatureFormat,
    entriesUtf8: ByteArray,
    cardsUtf8: ByteArray,
    overlaysUtf8: ByteArray,
    epochsUtf8: ByteArray?,
    signatureBase64UrlUtf8: ByteArray,
    snapshotDigest: ByteArray,
    val updatedAt: Long,
) {
    private val storedEntries = entriesUtf8.copyOf()
    private val storedCards = cardsUtf8.copyOf()
    private val storedOverlays = overlaysUtf8.copyOf()
    private val storedEpochs = epochsUtf8?.copyOf()
    private val storedSignature = signatureBase64UrlUtf8.copyOf()
    private val storedDigest = snapshotDigest.copyOf()

    val entriesUtf8: ByteArray get() = storedEntries.copyOf()
    val cardsUtf8: ByteArray get() = storedCards.copyOf()
    val overlaysUtf8: ByteArray get() = storedOverlays.copyOf()
    val epochsUtf8: ByteArray? get() = storedEpochs?.copyOf()
    val signatureBase64UrlUtf8: ByteArray get() = storedSignature.copyOf()
    val snapshotDigest: ByteArray get() = storedDigest.copyOf()

    init {
        require(
            (signatureFormat == TrustSignatureFormat.TRUSTSTORE_V1_FOUR_SECTION) == (storedEpochs != null),
        ) { "trust signature format and epoch-section presence disagree" }
        require(storedDigest.size == TRUST_SNAPSHOT_DIGEST_BYTES) { "trust snapshot digest must be SHA-256" }
    }
}

/** Internal byte carrier used only between the clean repository and its Room store. */
internal class TrustSnapshotExactBytes(
    val signatureFormat: TrustSignatureFormat,
    val entriesUtf8: ByteArray,
    val cardsUtf8: ByteArray,
    val overlaysUtf8: ByteArray,
    val epochsUtf8: ByteArray?,
    val signatureBase64UrlUtf8: ByteArray,
) {
    init {
        require(
            (signatureFormat == TrustSignatureFormat.TRUSTSTORE_V1_FOUR_SECTION) == (epochsUtf8 != null),
        ) { "trust signature format and epoch-section presence disagree" }
    }
}

internal enum class TrustSnapshotWriteResult {
    APPLIED,
    ALREADY_CURRENT,
    CONFLICT,
    MISSING_IDENTITY,
}

internal enum class CoreTrustIntegrityIssue {
    MISSING_IDENTITY,
    IDENTITY_PROJECTION_MISMATCH,
    UNKNOWN_SIGNATURE_FORMAT,
    INVALID_SECTION_SHAPE,
    INVALID_SECTION_ENCODING,
    INVALID_SIGNATURE_ENCODING,
    SIGNATURE_MISMATCH,
    DIGEST_MISMATCH,
    PERSISTED_READBACK_MISMATCH,
}

/** Value-free readiness failure: signed content, aliases, and key bytes never enter diagnostics. */
internal class CoreTrustIntegrityException(
    val issue: CoreTrustIntegrityIssue,
) : IllegalStateException("Core trust integrity validation failed: ${issue.name}")

internal const val TRUST_SNAPSHOT_DIGEST_BYTES = 32

internal data class CryptoEpochInput(
    val epoch: Int,
    val operationalSignerAlias: String,
    val operationalSignerPublicSpki: ByteArray,
    val hpkePublicKeyset: ByteArray,
    val hpkePrivateKeysetWrapped: ByteArray?,
    val securityLevel: CryptoEpochSecurityLevel,
    val lifecycleState: CryptoEpochState,
    val antiRollbackFloor: Long,
    val activationAt: Long? = null,
    val retirementAt: Long? = null,
    val createdAt: Long,
)

internal data class CryptoEpochSnapshot(
    val epoch: Int,
    val operationalSignerAlias: String,
    val operationalSignerPublicSpki: ByteArray,
    val hpkePublicKeyset: ByteArray,
    val hpkePrivateKeysetWrapped: ByteArray?,
    val securityLevel: CryptoEpochSecurityLevel,
    val lifecycleState: CryptoEpochState,
    val antiRollbackFloor: Long,
    val activationAt: Long?,
    val retirementAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** New lifecycle rows always begin as a pending intent before the Keystore side effect runs. */
internal data class KeystoreOperationIntent(
    val operationId: String,
    val targetType: KeystoreOperationTarget,
    val targetId: String,
    val operationKind: KeystoreOperationKind,
    val createdAt: Long,
)

internal data class KeystoreOperationSnapshot(
    val operationId: String,
    val targetType: KeystoreOperationTarget,
    val targetId: String,
    val operationKind: KeystoreOperationKind,
    val state: KeystoreOperationState,
    val attempts: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    val lastErrorCode: String?,
)
