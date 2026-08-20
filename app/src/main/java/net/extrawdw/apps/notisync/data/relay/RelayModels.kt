package net.extrawdw.apps.notisync.data.relay

import java.security.MessageDigest
import net.extrawdw.apps.notisync.data.activity.ActivityEventDraft
import net.extrawdw.notisync.protocol.RelayWire

/** Stable bounds shared by the storage-independent relay domain. */
object RelayLimits {
    /** Matches both the broker message_id column and authenticated-delivery contract. */
    const val MAX_MESSAGE_ID_CHARS = 64
    const val MAX_IDENTIFIER_CHARS = 256
    const val MAX_CODE_CHARS = 128
    const val AUTHENTICATED_TOKEN_BYTES = 32
    const val MAX_ENVELOPE_BYTES = RelayWire.MAX_BATCH_FRAME_BYTES
    const val MAX_BATCH_PAGE_ROWS = 128
    const val HANDLED_RETENTION_MILLIS = 72L * 60 * 60 * 1_000
}

/** App-local delivery metadata. These are protocol-independent application tokens. */
enum class RelayDeliveryMode(val token: String) {
    UNKNOWN("unknown"),
    WEBSOCKET("websocket"),
    FCM_INLINE("fcm_inline"),
    FCM_RELAY_FETCH("fcm_relay_fetch"),
    RELAY_DRAIN("relay_drain"),
}

/** External presentation, if any, that must complete after a validated finite-batch END and before ACK. */
enum class RelayBatchPresentation(val token: String) {
    NONE("none"),
    NOTIFICATION("notification"),
    DISMISSAL("dismissal"),
}

enum class RelayBatchRecordOutcome {
    INSERTED,
    EXACT,
    CONFLICT,
}

/**
 * Defensive metadata-only snapshot from the disposable drain scratch table.
 *
 * The authenticated token is the first envelope fingerprint observed for [messageId]. [conflict] is sticky for
 * the current drain, so no row with an ambiguous broker identity can become ACK-authorizing evidence.
 */
class RelayBatchItem(
    val messageId: String,
    authenticatedToken: AuthenticatedRelayToken,
    val conflict: Boolean,
    val presentation: RelayBatchPresentation,
) {
    private val tokenSnapshot = AuthenticatedRelayToken.of(authenticatedToken.copyBytes())

    init {
        requireRelayMessageId(messageId, "relay batch message id")
    }

    val authenticatedToken: AuthenticatedRelayToken
        get() = AuthenticatedRelayToken.of(tokenSnapshot.copyBytes())

    override fun equals(other: Any?): Boolean =
        other is RelayBatchItem &&
            messageId == other.messageId &&
            tokenSnapshot == other.tokenSnapshot &&
            conflict == other.conflict &&
            presentation == other.presentation

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + tokenSnapshot.hashCode()
        result = 31 * result + conflict.hashCode()
        result = 31 * result + presentation.hashCode()
        return result
    }

    override fun toString(): String =
        "RelayBatchItem(messageId=$messageId, fingerprint=<${tokenSnapshot.copyBytes().size} bytes>, " +
            "conflict=$conflict, presentation=$presentation)"
}

/**
 * Semantic result reported to the ACK caller after an owning commit.
 *
 * This value is deliberately not persisted in message_dedup. Feature authority or an immutable Core marker owns
 * the actual outcome; the relay ledger owns only exact authenticated replay evidence.
 */
enum class RelayHandledDisposition(val token: String) {
    APPLIED("applied"),
    DUPLICATE("duplicate"),
    SUPERSEDED("superseded"),
    TERMINAL_REJECTED("terminal_rejected"),
}

/** Opaque SHA-256 identity of the exact authenticated envelope, never a plaintext-body hash. */
class AuthenticatedRelayToken private constructor(private val bytes: ByteArray) {
    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is AuthenticatedRelayToken && MessageDigest.isEqual(bytes, other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "AuthenticatedRelayToken(${bytes.size} bytes)"

    companion object {
        fun of(bytes: ByteArray): AuthenticatedRelayToken {
            require(bytes.size == RelayLimits.AUTHENTICATED_TOKEN_BYTES) {
                "authenticated relay token must be exactly ${RelayLimits.AUTHENTICATED_TOKEN_BYTES} bytes"
            }
            return AuthenticatedRelayToken(bytes.copyOf())
        }
    }
}

/** Bounded stable reason/error code; never provider text, payload data, or an exception message. */
class RelayStableCode private constructor(val token: String) {
    override fun equals(other: Any?): Boolean = other is RelayStableCode && token == other.token

    override fun hashCode(): Int = token.hashCode()

    override fun toString(): String = token

    companion object {
        fun of(token: String): RelayStableCode {
            requireRelayCode(token, "relay stable code")
            return RelayStableCode(token)
        }
    }
}

/** Exact continuity captured by the ready runtime and rechecked inside every Operational receipt transaction. */
data class RelayOperationalContinuity(
    val generation: Long,
    val storageIncarnationId: String,
) {
    init {
        require(generation > 0) { "Operational generation must be positive" }
        requireStorageIncarnationId(storageIncarnationId)
    }
}

/** Continuity-fenced, read-only resolution of existing handled evidence. */
sealed interface RelayHandledResolution {
    data object ExactAuthenticated : RelayHandledResolution
    data object LegacyRetainedNoAck : RelayHandledResolution

    data object Missing : RelayHandledResolution
    data object Conflict : RelayHandledResolution
    data object StorageContinuityMismatch : RelayHandledResolution
}

/** Result of one continuity-fenced generic receipt transaction. */
enum class RelayFinalizeOutcome {
    APPLIED,
    ALREADY_FINALIZED,
    LEGACY_RETAINED_NO_ACK,
    CONFLICT,
    STORAGE_CONTINUITY_MISMATCH,
}

/**
 * Exact modern handled evidence plus an optional deterministic Activity projection.
 *
 * This generic transaction is for catalog-level terminal/no-feature receipts and the Core handoff adapter. A
 * successful Operational feature must record this same evidence in its owning mutation transaction instead.
 */
data class RelayFinalizeRequest(
    val messageId: String,
    val authenticatedToken: AuthenticatedRelayToken,
    val handledAt: Long,
    val activity: ActivityEventDraft? = null,
) {
    init {
        requireRelayMessageId(messageId, "handled message id")
        requireRelayTimestamp(handledAt, "handled time")
    }
}

internal fun requireRelayIdentifier(value: String, name: String) {
    require(value.isNotBlank()) { "$name must not be blank" }
    require(value.length <= RelayLimits.MAX_IDENTIFIER_CHARS) { "$name is too long" }
    require(value.none(Char::isISOControl)) { "$name contains control characters" }
}

internal fun requireRelayMessageId(value: String, name: String) {
    requireRelayIdentifier(value, name)
    require(value.length <= RelayLimits.MAX_MESSAGE_ID_CHARS) { "$name is too long" }
}

internal fun requireRelayTimestamp(value: Long, name: String) {
    require(value > 0) { "$name must be positive" }
}

internal fun requireRelayCode(value: String, name: String) {
    require(STABLE_RELAY_CODE.matches(value)) { "$name must be a stable bounded token" }
}

private fun requireStorageIncarnationId(value: String) {
    require(value.isNotBlank()) { "Operational storage incarnation ID must not be blank" }
    require(value.length <= 128) { "Operational storage incarnation ID is too long" }
    require(value.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) {
        "Operational storage incarnation ID contains unsupported characters"
    }
}

private val STABLE_RELAY_CODE = Regex("[a-z][a-z0-9_.-]{0,${RelayLimits.MAX_CODE_CHARS - 1}}")
