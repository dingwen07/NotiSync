package net.extrawdw.apps.notisync.data.storage.importer.target.core.model

import java.nio.ByteBuffer
import java.security.MessageDigest

internal fun interface CoreV51PlanSource {
    suspend fun readPlan(): CoreV51ImportPlan
}

/** Captures the app-wide semantic legacy-origin decision before the first target write. */
internal fun interface V51LegacySourceInventorySource {
    suspend fun capture(): V51LegacySourceInventory
}

internal enum class CoreV51PrepareResult {
    READY,
    ALREADY_COMPLETE,
    KEYSTORE_RECOVERY_REQUIRED,
}

internal enum class CoreV51FailureDisposition {
    RETRYABLE,
    BLOCKED,
}

/** Clean cross-target identity produced by the one Operational rebuild attempt. */
internal data class CoreV51OperationalStorageBinding(
    val operationalGeneration: Long,
    val storageIncarnationId: String,
) {
    init {
        require(operationalGeneration > 0) { "Operational generation must be positive" }
        require(storageIncarnationId.isNotBlank()) { "Operational storage incarnation is missing" }
    }
}

/** Value-free cutover failure. Codes are stable diagnostics, never source values or provider text. */
internal class CoreV51ImportFailure(
    val disposition: CoreV51FailureDisposition,
    val errorCode: String,
    cause: Throwable? = null,
) : IllegalStateException("Core v51 import failed: $errorCode", cause) {
    init {
        requireSafeCoreV51Code(errorCode)
    }
}

/** Runs the complete disposable Operational rebuild after Core has purged its pre-authority projections. */
internal fun interface CoreV51OperationalRebuildStep {
    suspend fun rebuildAndVerify(): CoreV51OperationalStorageBinding
}

/**
 * Exact staged candidates supplied to the injected non-mutating activation gate.
 *
 * The optional token remains in this immutable plan snapshot until final commit because its authoritative table has
 * a foreign key to transport. Persisting it earlier would publish the very transport authority this gate protects.
 */
internal class CoreV51ActivationSnapshot(
    planDigest: CoreV51Digest,
    val identity: CoreV51IdentityCommand,
    val wrappingKey: CoreV51WrappingKeyCommand,
    epochs: List<CoreV51EpochCommand>,
    val authToken: CoreV51WrappedAuthTokenCommand?,
) {
    val planDigest: CoreV51Digest = CoreV51Digest.sha256(planDigest.copyBytes())
    val epochs: List<CoreV51EpochCommand> = epochs.toList()
    val candidateDigest: CoreV51Digest = computeCandidateDigest()

    init {
        require(this.epochs.isNotEmpty()) { "Core v51 activation candidates are empty" }
        require(this.epochs.map { it.epoch }.distinct().size == this.epochs.size) {
            "Core v51 activation candidates contain duplicate epochs"
        }
    }

    private fun computeCandidateDigest(): CoreV51Digest {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.framed("notisync-core-v51-persisted-activation-candidates-v1".encodeToByteArray())
        digest.framed(planDigest.copyBytes())
        digest.framed(identity.alias.encodeToByteArray())
        digest.int(identity.aliasVersion)
        digest.framed(identity.publicSpkiCopy())
        digest.framed(wrappingKey.alias.encodeToByteArray())
        digest.int(wrappingKey.aliasVersion)
        epochs.forEach { epoch ->
            digest.int(epoch.epoch)
            digest.framed(epoch.operationalSignerAlias.encodeToByteArray())
            digest.int(epoch.operationalSignerAliasVersion)
            digest.framed(epoch.operationalSignerPublicSpkiCopy())
            digest.framed(epoch.hpkePublicKeysetCopy())
            digest.framed(epoch.hpkePrivateKeysetWrappedCopy())
        }
        authToken.let { token ->
            digest.update(if (token == null) 0.toByte() else 1.toByte())
            token?.let { digest.framed(it.wrappedTokenCopy()) }
        }
        return CoreV51Digest.sha256(digest.digest())
    }

    override fun toString(): String =
        "CoreV51ActivationSnapshot(epochs=${epochs.map { it.epoch }}, values=<redacted>)"
}

internal class CoreV51EpochActivationEvidence(
    val epoch: Int,
    hpkePublicKeysetFingerprint: ByteArray,
    val operationalSignerSelfTestedAt: Long,
    val hpkePairSelfTestedAt: Long,
) {
    private val storedHpkeFingerprint = hpkePublicKeysetFingerprint.copyOf()
    val hpkePublicKeysetFingerprint: ByteArray get() = storedHpkeFingerprint.copyOf()

    init {
        require(epoch > 0 && storedHpkeFingerprint.size == CoreV51Digest.BYTES) {
            "invalid Core v51 epoch activation evidence"
        }
        require(operationalSignerSelfTestedAt >= 0 && hpkePairSelfTestedAt >= operationalSignerSelfTestedAt) {
            "Core v51 epoch activation evidence is out of order"
        }
    }
}

/**
 * Capability evidence from a gate that exercised only the observed aliases and exact target-persisted ciphertext.
 * The gate must sign/verify with the identity and every operational alias, unwrap every HPKE private candidate,
 * prove its public/private pairing, and unwrap/parse an optional auth token without returning plaintext or provider
 * responses. It must never generate, rotate, delete, or rewrite a key/file.
 */
internal class CoreV51ActivationEvidence(
    planDigest: CoreV51Digest,
    candidateDigest: CoreV51Digest,
    val identityClientId: String,
    val identitySelfTestedAt: Long,
    val wrappingKeySelfTestedAt: Long,
    epochEvidence: List<CoreV51EpochActivationEvidence>,
    val authTokenSelfTestedAt: Long?,
    val validatedAt: Long,
) {
    val planDigest: CoreV51Digest = CoreV51Digest.sha256(planDigest.copyBytes())
    val candidateDigest: CoreV51Digest = CoreV51Digest.sha256(candidateDigest.copyBytes())
    val epochEvidence: List<CoreV51EpochActivationEvidence> = epochEvidence.toList()

    init {
        require(identityClientId.isNotBlank()) { "Core v51 identity activation evidence is missing" }
        require(identitySelfTestedAt >= 0 && wrappingKeySelfTestedAt >= identitySelfTestedAt) {
            "Core v51 key activation evidence is out of order"
        }
        require(this.epochEvidence.isNotEmpty() &&
            this.epochEvidence.map { it.epoch }.distinct().size == this.epochEvidence.size
        ) { "Core v51 epoch activation evidence is incomplete" }
        require(authTokenSelfTestedAt == null || authTokenSelfTestedAt >= wrappingKeySelfTestedAt) {
            "Core v51 token activation evidence is out of order"
        }
        val latestEpochValidation = this.epochEvidence.maxOf { it.hpkePairSelfTestedAt }
        require(validatedAt >= latestEpochValidation &&
            validatedAt >= (authTokenSelfTestedAt ?: 0L) && validatedAt >= wrappingKeySelfTestedAt
        ) { "Core v51 final activation validation is out of order" }
    }

    override fun toString(): String =
        "CoreV51ActivationEvidence(epochs=${epochEvidence.map { it.epoch }}, evidence=<redacted>)"
}

internal fun interface CoreV51ActivationGate {
    suspend fun validate(snapshot: CoreV51ActivationSnapshot): CoreV51ActivationEvidence
}

internal interface CoreV51ImportTarget {
    /** A valid transport row is the only completed-cutover flag. */
    suspend fun hasCompletedTransport(): Boolean

    /** Validates completed Core state without opening any legacy source. */
    suspend fun validateCompletedTransport()

    /** Atomically discards every transport-absent projection before Operational begins rebuilding. */
    suspend fun prepareForRebuild(): CoreV51PrepareResult

    suspend fun stage(plan: CoreV51ImportPlan)
    suspend fun readActivationSnapshot(plan: CoreV51ImportPlan): CoreV51ActivationSnapshot
    suspend fun finalize(
        plan: CoreV51ImportPlan,
        activation: CoreV51ActivationEvidence,
        operationalStorage: CoreV51OperationalStorageBinding,
    )
}

private fun MessageDigest.framed(value: ByteArray) {
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.size).array())
    update(value)
}

private fun MessageDigest.int(value: Int) = update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())

private fun requireSafeCoreV51Code(value: String) {
    require(value.isNotBlank() && value.length <= 128) { "Core v51 diagnostic code is invalid" }
    require(value.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) {
        "Core v51 diagnostic code contains unsupported characters"
    }
}
