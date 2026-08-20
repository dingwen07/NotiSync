package net.extrawdw.apps.notisync.data.corecommand

import java.security.MessageDigest
import net.extrawdw.apps.notisync.data.activity.ActivityDeliveryMode
import net.extrawdw.apps.notisync.data.relay.AuthenticatedRelayToken
import net.extrawdw.apps.notisync.data.relay.RelayFinalizeRequest
import net.extrawdw.notisync.peer.foundation.FoundationTrustCommand

internal object CoreCommandLimits {
    const val SHA256_BYTES = 32
    const val MAX_CANONICAL_BYTES = 4 * 1024 * 1024
    const val MAX_IDENTIFIER_CHARS = 256
    const val MAX_INCARNATION_CHARS = 128
    const val MAX_MARKER_PRUNE_BATCH = 64
}

/** Closed command family supported by the current signed trust-snapshot reducer. */
internal enum class CoreCommandKind(val token: String) {
    DATA_SYNC_PROFILE("data_sync.profile"),
    DATA_SYNC_TRUST("data_sync.trust"),
    DATA_SYNC_CARD("data_sync.card"),
    ;

    companion object {
        fun fromToken(token: String): CoreCommandKind = entries.firstOrNull { it.token == token }
            ?: throw IllegalArgumentException("Unsupported Core command type")
    }
}

internal data class OperationalStorageContinuity(
    val generation: Long,
    val storageIncarnationId: String,
) {
    init {
        require(generation > 0) { "Operational generation must be positive" }
        requireStorageIncarnationId(storageIncarnationId)
    }
}

/**
 * Exact authenticated delivery passed directly from the inbound router.
 *
 * The broker is the retry journal, so this object contains no local retry clock or lifecycle state. Canonical bytes
 * are owned defensively and their SHA-256 identity is derived here rather than trusted from a caller. PROFILE,
 * TRUST, and CARD have no independently signed leaf request ID, so the later router adapter must use the
 * authenticated envelope [messageId] for both [commandId] and [authenticatedRequestId]. [decodedCommand] must be
 * the defensive value produced from that same router decode; this boundary never decodes the canonical bytes again.
 */
internal class AuthenticatedCoreCommandDelivery(
    val messageId: String,
    val commandId: String,
    val authenticatedRequestId: String,
    val commandType: CoreCommandKind,
    /** Authentication-derived envelope signer identity; never inferred from the DATA_SYNC body. */
    val senderId: String,
    /** Authentication-derived trust classification captured with the verified sender. */
    val senderOwnDevice: Boolean,
    /** Authenticated envelope signer epoch: zero is identity, positive is operational. */
    val signerEpoch: Int,
    /** Authenticated envelope creation time used as stable reducer chronology across broker redelivery. */
    val signedCreatedAt: Long,
    /** Receiver-local arrival path. This value is diagnostic metadata and is not sender authenticated. */
    val deliveryMode: ActivityDeliveryMode,
    val decodedCommand: FoundationTrustCommand,
    canonicalCommand: ByteArray,
    val authenticatedToken: AuthenticatedRelayToken,
    val continuity: OperationalStorageContinuity,
) {
    private val storedCanonicalCommand = canonicalCommand.copyOf()
    private val storedCommandDigest = MessageDigest.getInstance("SHA-256").digest(storedCanonicalCommand)

    fun canonicalCommandCopy(): ByteArray = storedCanonicalCommand.copyOf()
    fun commandDigestCopy(): ByteArray = storedCommandDigest.copyOf()

    init {
        requireCompactIdentifier(messageId, "Core delivery message id")
        requireCompactIdentifier(commandId, "Core command id")
        requireCompactIdentifier(authenticatedRequestId, "authenticated Core request id")
        require(commandId == messageId && authenticatedRequestId == messageId) {
            "PROFILE/TRUST/CARD command and request IDs must equal the authenticated message ID"
        }
        requireCompactIdentifier(senderId, "authenticated Core sender id")
        require(signerEpoch >= 0) { "authenticated Core signer epoch must not be negative" }
        require(signedCreatedAt > 0) { "authenticated Core signed creation time must be positive" }
        require(storedCanonicalCommand.size in 1..CoreCommandLimits.MAX_CANONICAL_BYTES) {
            "Canonical Core command exceeds the reviewed bound"
        }
        require(commandType.matches(decodedCommand.kind)) {
            "decoded Foundation command kind diverges from the authenticated Core command type"
        }
    }

    override fun toString(): String =
        "AuthenticatedCoreCommandDelivery(messageId=$messageId, commandId=$commandId, " +
            "type=${commandType.token}, senderId=$senderId, signerEpoch=$signerEpoch, " +
            "generation=${continuity.generation}, " +
            "canonical=<${storedCanonicalCommand.size} bytes>, digest=<${storedCommandDigest.size} bytes>)"
}

/** Result of the one Operational receipt transaction; only the first two outcomes are ACK-ready. */
internal enum class CoreCommandReceiptFinalizeOutcome {
    APPLIED,
    ALREADY_FINALIZED,
    LEGACY_RETAINED_NO_ACK,
    CONFLICT,
    STORAGE_CONTINUITY_MISMATCH,
}

/**
 * Storage-independent Operational receipt boundary. Implementations atomically recheck continuity and persist exact
 * modern handled evidence plus the optional privacy-reviewed Activity projection. They never enqueue a durable ACK.
 */
internal interface CoreCommandReceiptFinalizer {
    suspend fun finalize(
        continuity: OperationalStorageContinuity,
        request: RelayFinalizeRequest,
    ): CoreCommandReceiptFinalizeOutcome
}

internal fun requireCompactIdentifier(value: String, name: String) {
    require(value.isNotBlank()) { "$name must not be blank" }
    require(value.length <= CoreCommandLimits.MAX_IDENTIFIER_CHARS) { "$name is too long" }
    require(value.none(Char::isISOControl) && value.none(Char::isWhitespace)) {
        "$name must be a compact identifier"
    }
}

internal fun requireStorageIncarnationId(value: String) {
    require(value.isNotBlank()) { "Operational storage incarnation ID must not be blank" }
    require(value.length <= CoreCommandLimits.MAX_INCARNATION_CHARS) {
        "Operational storage incarnation ID is too long"
    }
    require(value.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) {
        "Operational storage incarnation ID contains unsupported characters"
    }
}
