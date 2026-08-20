package net.extrawdw.apps.notisync.data.storage.importer.target

import java.security.MessageDigest
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedPayload
import net.extrawdw.apps.notisync.data.storage.importer.target.preferences.OperationalPreferencesRebuildPlan

/**
 * Clean description of one shipped source contract. Source identity remains in memory only and is
 * never persisted in OperationalDatabase.
 */
internal data class OperationalImportSource(
    val sourceId: String,
    val expectedSchemaVersion: Int,
    val optional: Boolean,
    val batchSize: Int,
) {
    init {
        require(sourceId.isNotBlank() && sourceId.length <= MAX_ID_CHARS) { "invalid import source id" }
        require(sourceId.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) {
            "import source id contains unsupported characters"
        }
        require(expectedSchemaVersion > 0) { "source schema version must be positive" }
        require(batchSize in 1..MAX_BATCH_SIZE) { "import batch size is outside its bound" }
    }
}

internal object OperationalImportSources {
    val MESSAGE_LEDGER_V2 = OperationalImportSource(
        sourceId = "legacy.message_ledger.v2",
        expectedSchemaVersion = 2,
        optional = true,
        batchSize = 128,
    )

    val RUNS_V2 = OperationalImportSource(
        sourceId = "legacy.runs.v2",
        expectedSchemaVersion = 2,
        optional = true,
        // Row count is an upper bound; the staging snapshot also stops each page at 2 MiB.
        batchSize = 64,
    )

    val SEAL_HISTORY_V3 = OperationalImportSource(
        sourceId = "legacy.seal_history.v3",
        expectedSchemaVersion = 3,
        optional = true,
        batchSize = 16,
    )

    val SQLITE_V51: List<OperationalImportSource> = listOf(
        MESSAGE_LEDGER_V2,
        RUNS_V2,
        SEAL_HISTORY_V3,
    )
}

/** Defensive, content-equal SHA-256 evidence retained only for one in-memory rebuild attempt. */
internal class ImportDigest private constructor(bytes: ByteArray) {
    private val value = bytes.copyOf()

    fun copyBytes(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean =
        other is ImportDigest && MessageDigest.isEqual(value, other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "ImportDigest(SHA-256)"

    companion object {
        const val BYTES = 32

        fun sha256(bytes: ByteArray): ImportDigest {
            require(bytes.size == BYTES) { "import evidence must be SHA-256" }
            return ImportDigest(bytes)
        }
    }
}

internal enum class ImportRunPhase {
    RUNNING,
    BLOCKED,
    COMPLETED,
    FAILED_TO_START,
}

/** Clean target commands. Source-specific DTOs and parsing remain in importer/legacy. */
internal sealed interface OperationalImportCommand {
    data class HandledMessageIdOnly(
        val messageId: String,
        val handledAt: Long,
    ) : OperationalImportCommand {
        init {
            requireIdentifier(messageId)
            require(handledAt > 0) { "handled time must be positive" }
        }
    }

    data class MirrorLifecycle(
        val sourceClientId: String,
        val sourceKey: String,
        val postTime: Long?,
        val dismissedAt: Long?,
        val updatedAt: Long,
    ) : OperationalImportCommand {
        init {
            requireIdentifier(sourceClientId)
            requireIdentifier(sourceKey)
            require(postTime != null || dismissedAt != null) { "empty mirror lifecycle has no target state" }
            require(postTime == null || postTime > 0) { "mirror post time must be positive" }
            require(dismissedAt == null || dismissedAt > 0) { "mirror dismissal time must be positive" }
            require(postTime == null || dismissedAt == null || dismissedAt >= postTime) {
                "mirror dismissal precedes its post"
            }
            require(updatedAt > 0) { "mirror update time must be positive" }
        }
    }

    /**
     * The payload is borrowed from an immutable source snapshot and is never exposed to coordinator
     * callers. The Room Run command performs the required defensive copy before its transaction.
     */
    class RunState internal constructor(
        val hostClientId: String,
        val runId: String,
        val revision: Long,
        val phase: ImportRunPhase,
        val presentedRevision: Long,
        val active: Boolean,
        val updatedAt: Long,
        val endedAt: Long?,
        val receivedAt: Long,
        payload: ByteArray,
        payloadDigest: ImportDigest,
    ) : OperationalImportCommand {
        private val borrowedPayload = payload
        private val digest = payloadDigest

        init {
            requireIdentifier(hostClientId)
            requireIdentifier(runId)
            require(revision > 0 && (presentedRevision == -1L || presentedRevision in 0..revision)) {
                "Run revision projection is invalid"
            }
            require(!active || phase == ImportRunPhase.RUNNING || phase == ImportRunPhase.BLOCKED) {
                "active Run projection contradicts its phase"
            }
            require(updatedAt > 0 && receivedAt > 0) { "Run timestamps must be positive" }
            require(endedAt == null || endedAt > 0) { "Run end time must be positive" }
            require(borrowedPayload.isNotEmpty()) { "Run payload must not be empty" }
        }

        internal inline fun <T> withBorrowedPayload(block: (ByteArray, ByteArray) -> T): T =
            block(borrowedPayload, digest.copyBytes())
    }

    class SealTerminalHistory internal constructor(
        val requestId: String,
        val requesterClientId: String,
        val senderClientId: String,
        private val requestFingerprint: ImportDigest,
        val issuedAt: Long,
        val expiresAt: Long,
        private val payloadSha256: ImportDigest,
        val state: ImportSealRequestState,
        val outcome: ImportSealRequestOutcome,
        val decisionAt: Long,
        val createdAt: Long,
        val updatedAt: Long,
        val protectedDisplay: ProtectedPayload,
        private val displayPlaintextDigest: ImportDigest,
        val displayTruncated: Boolean,
    ) : OperationalImportCommand {
        init {
            requireIdentifier(requestId)
            requireIdentifier(requesterClientId)
            requireIdentifier(senderClientId)
            require(requesterClientId == senderClientId) { "Seal requester and sender must match" }
            require(issuedAt > 0 && expiresAt > issuedAt) { "Seal request lifetime is invalid" }
            require(createdAt > 0 && updatedAt >= createdAt && decisionAt in createdAt..updatedAt) {
                "Seal terminal timestamps are invalid"
            }
            require(state.accepts(outcome)) { "Seal state and outcome do not match" }
        }

        internal fun requestFingerprintCopy(): ByteArray = requestFingerprint.copyBytes()
        internal fun payloadSha256Copy(): ByteArray = payloadSha256.copyBytes()
        internal fun displayPlaintextDigestCopy(): ByteArray = displayPlaintextDigest.copyBytes()

        override fun toString(): String =
            "SealTerminalHistory(state=$state, outcome=$outcome, displayTruncated=$displayTruncated)"
    }

    class SealEnrollment internal constructor(
        val state: ImportSealEnrollmentState,
        val recoveryReasonCode: String?,
        val protectedEnrollment: ProtectedPayload?,
    ) : OperationalImportCommand {
        init {
            requireCode(recoveryReasonCode)
            when (state) {
                ImportSealEnrollmentState.DISABLED -> require(
                    recoveryReasonCode == null && protectedEnrollment == null,
                ) { "disabled Seal enrollment cannot carry material or a recovery reason" }
                ImportSealEnrollmentState.ENROLLED -> require(
                    recoveryReasonCode == null && protectedEnrollment != null,
                ) { "enrolled Seal state requires one protected tuple" }
                ImportSealEnrollmentState.RECOVERY_REQUIRED -> require(
                    recoveryReasonCode != null && protectedEnrollment == null,
                ) { "recovery-required Seal state requires only a safe reason" }
            }
        }

        override fun toString(): String = "SealEnrollment(state=$state, hasProtected=${protectedEnrollment != null})"
    }
}

internal enum class ImportSealRequestState { SENT, CANCELLED, EXPIRED, FAILED }

internal enum class ImportSealRequestOutcome { APPROVED, REJECTED, CANCELLED, EXPIRED, FAILED }

private fun ImportSealRequestState.accepts(outcome: ImportSealRequestOutcome): Boolean = when (this) {
    ImportSealRequestState.SENT -> outcome in setOf(
        ImportSealRequestOutcome.APPROVED,
        ImportSealRequestOutcome.REJECTED,
    )
    ImportSealRequestState.CANCELLED -> outcome == ImportSealRequestOutcome.CANCELLED
    ImportSealRequestState.EXPIRED -> outcome == ImportSealRequestOutcome.EXPIRED
    ImportSealRequestState.FAILED -> outcome == ImportSealRequestOutcome.FAILED
}

internal enum class ImportSealEnrollmentState { DISABLED, ENROLLED, RECOVERY_REQUIRED }

/**
 * A deterministic, immutable source view. Implementations may map source rows lazily by ordinal so
 * a batch does not duplicate a complete legacy source in memory.
 */
internal interface OperationalImportSnapshot {
    val source: OperationalImportSource
    val sourceFingerprint: ImportDigest
    val logicalContentDigest: ImportDigest
    val commandCount: Long
    val skippedRowCount: Long
    val quarantinedRowCount: Long

    suspend fun commands(startOrdinal: Long, limit: Int): List<OperationalImportCommand>
}

/** Caller-supplied identity for one disposable rebuild attempt. Nothing here is an import journal. */
internal data class OperationalRebuildIdentity(
    val operationalGeneration: Long,
    val storageIncarnationId: String,
    val startedAt: Long,
) {
    init {
        require(operationalGeneration > 0) { "Operational generation must be positive" }
        require(storageIncarnationId.length in 1..128 &&
            storageIncarnationId.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }
        ) { "storage incarnation id is invalid" }
        require(startedAt > 0) { "rebuild start time must be positive" }
    }
}

/** Value-free evidence returned only to the global in-memory migrator. */
internal data class OperationalRebuildSummary(
    val importedRowCount: Long,
    val skippedRowCount: Long,
    val absentOptionalSourceCount: Int,
) {
    init {
        require(importedRowCount >= 0 && skippedRowCount >= 0 && absentOptionalSourceCount >= 0) {
            "rebuild summary counts must not be negative"
        }
    }
}

internal interface OperationalImportTarget {
    /** Atomically clears every old/partial row and establishes only [identity]'s maintenance marker. */
    suspend fun beginRebuild(identity: OperationalRebuildIdentity)

    /** Applies one bounded in-memory page after rechecking the exact maintenance marker. */
    suspend fun applyBatch(
        identity: OperationalRebuildIdentity,
        commands: List<OperationalImportCommand>,
    )

    /** Applies all values decoded from the one Preferences snapshot in one short transaction. */
    suspend fun applyPreferences(
        identity: OperationalRebuildIdentity,
        plan: OperationalPreferencesRebuildPlan,
        sealEnrollment: OperationalImportCommand.SealEnrollment,
    )

    /** Point-validates persisted target projections after all source commands have committed. */
    suspend fun verify(
        identity: OperationalRebuildIdentity,
        snapshot: OperationalImportSnapshot,
    ): ImportVerificationResult

    suspend fun verifyPreferences(
        identity: OperationalRebuildIdentity,
        plan: OperationalPreferencesRebuildPlan,
        sealEnrollment: OperationalImportCommand.SealEnrollment,
    ): ImportVerificationResult
}

internal sealed interface ImportVerificationResult {
    data object VERIFIED : ImportVerificationResult
    data class Failed(val errorCode: String) : ImportVerificationResult {
        init {
            requireCode(errorCode)
        }
    }
}

internal enum class ImportFailureDisposition {
    RETRYABLE,
    BLOCKED,
}

/** Payload-free failure crossing the source/target boundary. */
internal class OperationalImportFailure(
    val disposition: ImportFailureDisposition,
    val errorCode: String,
    cause: Throwable? = null,
) : IllegalStateException("operational import failed: $errorCode", cause) {
    init {
        requireCode(errorCode)
    }
}

private fun requireIdentifier(value: String) {
    require(value.isNotBlank() && value.length <= MAX_ID_CHARS) { "import identifier is invalid" }
    require(value.none(Char::isISOControl)) { "import identifier contains control characters" }
}

private fun requireCode(value: String?) {
    if (value == null) return
    require(value.isNotBlank() && value.length <= MAX_CODE_CHARS) { "import code is invalid" }
    require(value.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) {
        "import code contains unsupported characters"
    }
}

private const val MAX_ID_CHARS = 256
private const val MAX_CODE_CHARS = 128
private const val MAX_BATCH_SIZE = 1_024
