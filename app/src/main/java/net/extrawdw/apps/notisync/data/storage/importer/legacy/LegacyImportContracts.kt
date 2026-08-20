package net.extrawdw.apps.notisync.data.storage.importer.legacy

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import net.extrawdw.notisync.protocol.RunState

/**
 * The production legacy cutover contract.  These types deliberately describe the shipped source
 * databases, not Room rows.  Keeping them in this package prevents a future Room schema change from
 * accidentally becoming a compatibility promise for an obsolete SQLite layout.
 */
internal enum class LegacySourceId(
    val fileName: String,
    val userVersion: Int,
) {
    MESSAGE_LEDGER("message_ledger.db", 2),
    RUNS("runs.db", 2),
    OPENPGP_SIGNING("openpgp_signing.db", 3),
}

internal enum class LegacyFailureKind {
    SOURCE_MISSING,
    FILENAME_MISMATCH,
    UNSUPPORTED_VERSION,
    SCHEMA_MISMATCH,
    QUICK_CHECK_FAILED,
    MALFORMED_ROW,
    SOURCE_IO,
}

/** Typed, payload-free diagnostic for a source that cannot be safely imported. */
internal class LegacyImportException(
    val kind: LegacyFailureKind,
    val source: LegacySourceId,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause) {
    companion object {
        fun missing(source: LegacySourceId): LegacyImportException = LegacyImportException(
            kind = LegacyFailureKind.SOURCE_MISSING,
            source = source,
            message = "legacy source is absent: ${source.fileName}",
        )

        fun filename(source: LegacySourceId): LegacyImportException = LegacyImportException(
            kind = LegacyFailureKind.FILENAME_MISMATCH,
            source = source,
            message = "legacy source filename does not match the contract",
        )

        fun version(source: LegacySourceId, actual: Int): LegacyImportException = LegacyImportException(
            kind = LegacyFailureKind.UNSUPPORTED_VERSION,
            source = source,
            message = "legacy source user_version $actual is not supported",
        )

        fun schema(source: LegacySourceId, message: String): LegacyImportException = LegacyImportException(
            kind = LegacyFailureKind.SCHEMA_MISMATCH,
            source = source,
            message = "legacy source schema mismatch: $message",
        )

        fun quickCheck(source: LegacySourceId, result: String): LegacyImportException = LegacyImportException(
            kind = LegacyFailureKind.QUICK_CHECK_FAILED,
            source = source,
            message = "legacy source quick_check failed: $result",
        )

        fun malformed(source: LegacySourceId, table: String, rowNumber: Long, reason: String): LegacyImportException =
            LegacyImportException(
                kind = LegacyFailureKind.MALFORMED_ROW,
                source = source,
                // Do not include row values or encoded payloads in diagnostics.  The row number and
                // stable field reason are enough for recovery telemetry without leaking user data.
                message = "malformed legacy row in $table at ordinal $rowNumber: $reason",
            )

        fun io(source: LegacySourceId, cause: Throwable): LegacyImportException = LegacyImportException(
            kind = LegacyFailureKind.SOURCE_IO,
            source = source,
            message = "legacy source could not be read",
            cause = cause,
        )
    }
}

internal data class LegacySqliteSource(
    val id: LegacySourceId,
    val fileName: String,
    val userVersion: Int,
)

/** Source descriptor for legacy Preferences DataStore snapshots (not a SQLite file). */
internal data class LegacyPreferencesSource(
    val name: String,
    val contractVersion: Int,
)

/** SHA-256 values are copied defensively so callers cannot mutate one-attempt consistency evidence. */
internal class LegacySourceDigests(
    contentDigest: ByteArray,
    logicalFingerprint: ByteArray,
) {
    private val contentDigestValue: ByteArray = contentDigest.copyOf()
    private val logicalFingerprintValue: ByteArray = logicalFingerprint.copyOf()

    val contentDigest: ByteArray get() = contentDigestValue.copyOf()
    val logicalFingerprint: ByteArray get() = logicalFingerprintValue.copyOf()

    init {
        require(contentDigestValue.size == SHA256_BYTES) { "contentDigest must be SHA-256" }
        require(logicalFingerprintValue.size == SHA256_BYTES) { "logicalFingerprint must be SHA-256" }
    }

    fun copyOf(): LegacySourceDigests = LegacySourceDigests(contentDigestValue, logicalFingerprintValue)

    override fun equals(other: Any?): Boolean = other is LegacySourceDigests &&
        contentDigestValue.contentEquals(other.contentDigestValue) &&
        logicalFingerprintValue.contentEquals(other.logicalFingerprintValue)

    override fun hashCode(): Int = 31 * contentDigestValue.contentHashCode() + logicalFingerprintValue.contentHashCode()

    companion object {
        const val SHA256_BYTES = 32
    }
}

internal data class LegacyDedupRow(
    val messageId: String,
    val handledAt: Long,
)

internal data class LegacyMirrorMessageRow(
    val sourceClient: String,
    val sourceKey: String,
    val messageId: String,
    val recordedAt: Long,
)

internal data class LegacyMirrorLifecycleRow(
    val sourceClient: String,
    val sourceKey: String,
    val postTime: Long?,
    val dismissedAt: Long?,
    val updatedAt: Long,
)

internal data class LegacyMessageMetaRow(
    val name: String,
    val longValue: Long,
)

/**
 * Bounded sink for the retained message-ledger projection. Pending relay and ACK rows never enter
 * this API: the reader validates and fingerprints only their non-payload metadata, then reports
 * aggregate skipped counts in [LegacyMessageLedgerStreamEvidence].
 */
internal interface LegacyMessageLedgerRowSink {
    fun acceptDedup(row: LegacyDedupRow)
    fun acceptMirrorMessage(row: LegacyMirrorMessageRow)
    fun acceptMirrorLifecycle(row: LegacyMirrorLifecycleRow)
    fun acceptMetadata(row: LegacyMessageMetaRow)

    companion object {
        val DISCARD: LegacyMessageLedgerRowSink = object : LegacyMessageLedgerRowSink {
            override fun acceptDedup(row: LegacyDedupRow) = Unit
            override fun acceptMirrorMessage(row: LegacyMirrorMessageRow) = Unit
            override fun acceptMirrorLifecycle(row: LegacyMirrorLifecycleRow) = Unit
            override fun acceptMetadata(row: LegacyMessageMetaRow) = Unit
        }
    }
}

/** Evidence returned after one WAL-consistent message-ledger snapshot was streamed. */
internal class LegacyMessageLedgerStreamEvidence(
    val source: LegacySqliteSource,
    val commandCount: Long,
    val skippedRelayInboxCount: Long,
    val skippedPendingAckCount: Long,
    val digests: LegacySourceDigests,
    retainedCommandDigest: ByteArray,
) {
    private val retainedCommandDigestValue = retainedCommandDigest.copyOf()
    val retainedCommandDigest: ByteArray get() = retainedCommandDigestValue.copyOf()

    init {
        require(source.id == LegacySourceId.MESSAGE_LEDGER) { "wrong source for message ledger stream" }
        require(commandCount >= 0 && skippedRelayInboxCount >= 0 && skippedPendingAckCount >= 0) {
            "message-ledger stream counts must be non-negative"
        }
        require(retainedCommandDigestValue.size == LegacySourceDigests.SHA256_BYTES) {
            "retained command digest must be SHA-256"
        }
    }
}

/** Canonical retained-row framing reused to authenticate the ephemeral staging copy. */
internal class LegacyMessageLedgerV2RetainedDigestAccumulator {
    private val digest = LegacyDigestAccumulator().apply {
        text("NotiSync/message_ledger/v2/retained-stage/v1")
    }

    fun appendDedup(row: LegacyDedupRow) {
        digest.text("dedup")
        digest.text(row.messageId)
        digest.long(row.handledAt)
    }

    fun appendMirrorMessage(row: LegacyMirrorMessageRow) {
        digest.text("mirror_msg")
        digest.text(row.sourceClient)
        digest.text(row.sourceKey)
        digest.text(row.messageId)
        digest.long(row.recordedAt)
    }

    fun appendMirrorLifecycle(row: LegacyMirrorLifecycleRow) {
        digest.text("mirror_lifecycle")
        digest.text(row.sourceClient)
        digest.text(row.sourceKey)
        digest.nullableLong(row.postTime)
        digest.nullableLong(row.dismissedAt)
        digest.long(row.updatedAt)
    }

    fun appendMetadata(row: LegacyMessageMetaRow) {
        digest.text("message_meta")
        digest.text(row.name)
        digest.long(row.longValue)
    }

    fun digest(): ByteArray = digest.digest()
}

internal data class LegacyRunRow(
    val hostClientId: String,
    val runId: String,
    val revision: Long,
    val presentedRevision: Long,
    val active: Boolean,
    val updatedAt: Long,
    val endedAt: Long?,
    val receivedAt: Long,
    /** Exact source CBOR is retained for the target snapshot; callers receive a defensive copy. */
    val payload: ByteArray,
    val state: RunState,
) {
    init {
        require(hostClientId.isNotBlank() && runId.isNotBlank()) { "Run key must not be blank" }
        require(revision > 0) { "Run revision must be positive" }
        require(presentedRevision == -1L || presentedRevision in 0..revision) {
            "Run presented revision is outside the source projection bounds"
        }
        require(updatedAt >= 0 && receivedAt >= 0) { "Run timestamps must be non-negative" }
        require(payload.isNotEmpty()) { "Run payload must not be empty" }
    }

    fun copyPayload(): ByteArray = payload.copyOf()
}

/** Evidence returned after a complete pinned Runs snapshot has streamed through a bounded sink. */
internal data class LegacyRunsStreamEvidence(
    val source: LegacySqliteSource,
    val rowCount: Long,
    val totalPayloadBytes: Long,
    val digests: LegacySourceDigests,
) {
    init {
        require(source.id == LegacySourceId.RUNS) { "wrong source for Runs stream evidence" }
        require(rowCount >= 0 && totalPayloadBytes >= 0) { "Runs stream counts must not be negative" }
    }
}

/** Simple source-level counters for the import journal; no payload or source row is exposed. */
internal data class LegacyImportCounts(
    val imported: Long,
    val skipped: Long,
    val quarantined: Long = 0,
) {
    init {
        require(imported >= 0 && skipped >= 0 && quarantined >= 0) { "import counts must be non-negative" }
    }
}

/**
 * Canonical binary digest framing shared by all source readers.  Row order is fixed by each reader's
 * ORDER BY clause; field framing prevents concatenation ambiguities (for example, ["ab", "c"] vs
 * ["a", "bc"]).
 */
internal class LegacyDigestAccumulator {
    private val bytes = ByteArrayOutputStream()
    private val output = DataOutputStream(bytes)

    fun text(value: String?) {
        if (value == null) {
            output.writeByte(0)
        } else {
            output.writeByte(1)
            bytes(value.encodeToByteArray())
        }
    }

    fun long(value: Long) = output.writeLong(value)

    fun nullableLong(value: Long?) {
        if (value == null) {
            output.writeByte(0)
        } else {
            output.writeByte(1)
            output.writeLong(value)
        }
    }

    fun int(value: Int) = output.writeInt(value)

    fun boolean(value: Boolean) = output.writeByte(if (value) 1 else 0)

    fun bytes(value: ByteArray?) {
        if (value == null) {
            output.writeInt(-1)
        } else {
            output.writeInt(value.size)
            output.write(value)
        }
    }

    fun digest(): ByteArray {
        output.flush()
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
    }
}

internal fun legacyLogicalFingerprint(
    source: LegacySqliteSource,
    contentDigest: ByteArray,
): ByteArray {
    val accumulator = LegacyDigestAccumulator()
    accumulator.text("NotiSync/legacy-logical-fingerprint/v1")
    accumulator.text(source.id.name)
    accumulator.text(source.fileName)
    accumulator.int(source.userVersion)
    accumulator.bytes(contentDigest)
    return accumulator.digest()
}

/**
 * Message-ledger fingerprint additionally binds the retained staging projection. This lets a
 * restarted importer prove that a discardable stage still contains the exact source commands even
 * though intentionally skipped relay/ACK metadata is represented only by source evidence/counts.
 */
internal fun legacyMessageLedgerLogicalFingerprint(
    source: LegacySqliteSource,
    contentDigest: ByteArray,
    retainedCommandDigest: ByteArray,
): ByteArray {
    require(source.id == LegacySourceId.MESSAGE_LEDGER) { "wrong source for message-ledger fingerprint" }
    val accumulator = LegacyDigestAccumulator()
    accumulator.text("NotiSync/message-ledger-logical-fingerprint/v2")
    accumulator.text(source.id.name)
    accumulator.text(source.fileName)
    accumulator.int(source.userVersion)
    accumulator.bytes(contentDigest)
    accumulator.bytes(retainedCommandDigest)
    return accumulator.digest()
}

internal fun legacyLogicalFingerprint(
    source: LegacyPreferencesSource,
    contentDigest: ByteArray,
): ByteArray {
    val accumulator = LegacyDigestAccumulator()
    accumulator.text("NotiSync/legacy-preferences-logical-fingerprint/v1")
    accumulator.text(source.name)
    accumulator.int(source.contractVersion)
    accumulator.bytes(contentDigest)
    return accumulator.digest()
}
