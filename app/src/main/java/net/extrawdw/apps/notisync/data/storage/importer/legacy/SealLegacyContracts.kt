package net.extrawdw.apps.notisync.data.storage.importer.legacy

import kotlinx.serialization.Serializable

/** Terminal state tokens shipped by OpenPgpSignStore v3. */
internal enum class LegacySealRequestState {
    SENT,
    CANCELLED,
    EXPIRED,
    FAILED,
}

/** Pending states are recognized for diagnostics only and are never decoded or imported. */
internal enum class LegacySealSkippedState {
    PENDING_REVIEW,
    USER_APPROVED,
    PROVIDER_INTERACTION,
    SIGNED_PENDING_SEND,
    REJECTED_PENDING_SEND,
}

internal enum class LegacySealHistoryOutcome {
    APPROVED,
    REJECTED,
    CANCELED,
    EXPIRED,
    FAILED,
}

/**
 * v51's display serializer predates the current `truncated` marker.  It intentionally lives in
 * the legacy package so the Room/operational display model never becomes the source decoder.
 */
@Serializable
internal data class LegacyGitCommitDisplaySnapshotV51(
    val treeId: String,
    val parentIds: List<String>,
    val author: String,
    val committer: String,
    val message: String,
    val extraHeaders: List<LegacyGitCommitDisplayHeaderV51>,
    val payloadBytes: Int,
)

@Serializable
internal data class LegacyGitCommitDisplayHeaderV51(
    val name: String,
    val value: String,
)

/** Bounded display projection emitted by the one-time legacy mapper, never a raw commit payload. */
internal data class LegacySealCommitDisplaySnapshot(
    val treeId: String,
    val parentIds: List<String>,
    val author: String,
    val committer: String,
    val message: String,
    val extraHeaders: List<LegacySealCommitDisplayHeader>,
    val payloadBytes: Int,
    val truncated: Boolean,
)

internal data class LegacySealCommitDisplayHeader(
    val name: String,
    val value: String,
)

/** Validated terminal-history source row with no raw payload, encoded response, or signature bytes. */
internal class LegacySealHistoryRow(
    val requestId: String,
    val requesterClientId: String,
    val senderClientId: String,
    val primaryKeyId: String,
    val issuedAt: Long,
    val expiresAt: Long,
    payloadSha256: ByteArray,
    val objectKind: String,
    val state: LegacySealRequestState,
    val updatedAt: Long,
    val commit: LegacySealCommitDisplaySnapshot?,
    val outcome: LegacySealHistoryOutcome,
    val workingDirectory: String?,
) {
    val payloadSha256: ByteArray = payloadSha256.copyOf()

    init {
        require(payloadSha256.size == 32) { "Seal payload digest must be SHA-256" }
    }

    fun payloadDigestCopy(): ByteArray = payloadSha256.copyOf()
}

internal data class LegacySealSnapshot(
    val source: LegacySqliteSource,
    val terminalRows: List<LegacySealHistoryRow>,
    val skippedActivePendingCount: Long,
    val skippedResponsePendingCount: Long,
    val malformedDisplayCount: Long,
    val digests: LegacySourceDigests,
) {
    init {
        require(source.id == LegacySourceId.OPENPGP_SIGNING) { "wrong source for Seal snapshot" }
        require(
            skippedActivePendingCount >= 0 && skippedResponsePendingCount >= 0 && malformedDisplayCount >= 0,
        ) { "Seal import counts must be non-negative" }
    }
}

internal enum class LegacySealEnrollmentStatus {
    DISABLED,
    READY,
    RECOVERY_REQUIRED,
}

internal enum class LegacySealEnrollmentFailure {
    PARTIAL_TUPLE,
    ENABLED_MISSING_MATERIAL,
    INVALID_PROVIDER,
    INVALID_PROVIDER_REFERENCE,
    INVALID_PRIMARY_KEY_ID,
    INVALID_DISPLAY_IDENTITY,
    INVALID_ENROLLED_AT,
    UNSUPPORTED_KEY_TYPE,
}

/**
 * Exact v51 Preferences DataStore identity and owned keys; unrelated app preferences are ignored.
 * v51 stores provider metadata only, with no local Seal key alias/file contract to inspect.
 */
internal object LegacySealEnrollmentSourceContract {
    const val DATASTORE_NAME = "notisync"
    const val DATASTORE_FILE_NAME = "notisync.preferences_pb"
    const val ENABLED_KEY = "openpgp_sign_enabled"
    const val PROVIDER_KEY = "openpgp_sign_provider"
    const val PROVIDER_REFERENCE_KEY = "openpgp_sign_provider_reference"
    const val PRIMARY_KEY_ID_KEY = "openpgp_sign_primary_key_id"
    const val DISPLAY_IDENTITY_KEY = "openpgp_sign_display_identity"
    const val ENROLLED_AT_KEY = "openpgp_sign_enrolled_at"

    val keyNames: List<String> = listOf(
        ENABLED_KEY,
        PROVIDER_KEY,
        PROVIDER_REFERENCE_KEY,
        PRIMARY_KEY_ID_KEY,
        DISPLAY_IDENTITY_KEY,
        ENROLLED_AT_KEY,
    )
}

/** Enrollment values are retained only for the eventual protected Room import port. */
internal class LegacySealEnrollment(
    val providerId: String,
    val providerKeyReference: String,
    val primaryKeyId: String,
    val displayIdentity: String,
    val enrolledAt: Long,
) {
    init {
        require(providerId.isBoundedIdentifier()) { "providerId is invalid" }
        require(providerKeyReference.isBoundedIdentifier()) { "providerKeyReference is invalid" }
        require(primaryKeyId.matches(Regex("[0-9A-F]{16}"))) { "primaryKeyId is invalid" }
        require(displayIdentity.isBoundedDisplayIdentity()) { "displayIdentity is invalid" }
        require(enrolledAt > 0) { "enrolledAt must be positive" }
    }

    private fun String.isBoundedIdentifier(): Boolean =
        isNotBlank() && length <= MAX_IDENTIFIER_CHARS && none(Char::isISOControl) && '\u0000' !in this

    private fun String.isBoundedDisplayIdentity(): Boolean =
        isNotBlank() && length <= MAX_DISPLAY_IDENTITY_CHARS &&
            none(Char::isISOControl) && '\u0000' !in this

    private companion object {
        const val MAX_IDENTIFIER_CHARS = 256
        const val MAX_DISPLAY_IDENTITY_CHARS = 1_024
    }
}

/**
 * A typed DataStore snapshot. `presentKeyCount` is intentionally aggregate-only; diagnostics never
 * expose key names or enrollment values. Enrollment values are deliberately never fingerprinted.
 */
internal data class LegacySealEnrollmentSnapshot(
    val status: LegacySealEnrollmentStatus,
    val enrollment: LegacySealEnrollment?,
    val failure: LegacySealEnrollmentFailure?,
    val presentKeyCount: Int,
) {
    init {
        require(presentKeyCount >= 0) { "present key count must be non-negative" }
        require((status == LegacySealEnrollmentStatus.READY) == (enrollment != null)) {
            "ready status must agree with enrollment material"
        }
        require((status == LegacySealEnrollmentStatus.RECOVERY_REQUIRED) == (failure != null)) {
            "recovery status must carry a typed failure"
        }
        require(status != LegacySealEnrollmentStatus.DISABLED || enrollment == null) {
            "disabled enrollment must not carry material"
        }
    }
}
