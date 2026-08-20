package net.extrawdw.apps.notisync.seal

import java.security.MessageDigest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.GitCommitPayloadParser
import net.extrawdw.notisync.protocol.OpenPgpObjectKind
import net.extrawdw.notisync.protocol.OpenPgpRejectReason
import net.extrawdw.notisync.protocol.OpenPgpSignSync

enum class OpenPgpRequestState {
    PENDING_REVIEW,
    USER_APPROVED,
    PROVIDER_INTERACTION,
    /** An approved response whose exact bytes are durably protected in Room. */
    SIGNED_PENDING_SEND,
    /** A reconstructable negative response being sent on the current process attempt. */
    REJECTED_PENDING_SEND,
    SENT,
    CANCELLED,
    EXPIRED,
    FAILED,
}

/** User-visible outcome retained after the transport state advances to [OpenPgpRequestState.SENT]. */
enum class OpenPgpRequestResult { APPROVED, REJECTED, CANCELED, EXPIRED, FAILED }

/**
 * Bounded rendering snapshot kept for the decision ledger. The byte-exact commit payload is never
 * retained after a terminal transition; the Room adapter protects the snapshot before it is stored.
 */
@Serializable
data class GitCommitDisplaySnapshot(
    val treeId: String,
    val parentIds: List<String>,
    val author: String,
    val committer: String,
    val message: String,
    val extraHeaders: List<GitCommitDisplayHeader>,
    val payloadBytes: Int,
    val truncated: Boolean = false,
)

@Serializable
data class GitCommitDisplayHeader(val name: String, val value: String)

/** Reconstructed domain view of one Room Seal request. */
data class StoredOpenPgpRequest(
    val request: OpenPgpSignSync,
    val senderClientId: ClientId,
    val state: OpenPgpRequestState,
    val updatedAt: Long,
    val commit: GitCommitDisplaySnapshot? = null,
    val result: OpenPgpRequestResult? = null,
)

enum class OpenPgpAcceptResult { STORED, DUPLICATE, CONFLICT, RATE_LIMITED }

/**
 * Room-backed Seal request boundary. Methods are suspend because every state transition is a Room
 * transaction and protected payload work is performed off the main thread.
 */
interface OpenPgpSignRepository {
    val requests: StateFlow<List<StoredOpenPgpRequest>>

    suspend fun accept(
        request: OpenPgpSignSync,
        senderClientId: ClientId,
        now: Long,
    ): OpenPgpAcceptResult

    suspend fun find(requestId: String): StoredOpenPgpRequest?

    suspend fun approve(requestId: String, now: Long): Boolean

    suspend fun markProviderInteraction(requestId: String, now: Long): Boolean

    suspend fun storeResult(requestId: String, signatureArmor: String, now: Long): Boolean

    suspend fun storeReject(requestId: String, reason: OpenPgpRejectReason, now: Long): Boolean

    suspend fun cancel(requestId: String, senderClientId: ClientId, now: Long): Boolean

    suspend fun markExpired(requestId: String, now: Long): Boolean

    suspend fun expireDue(now: Long): List<String>

    /** Prepare exact approved custody for sending, or reconstruct a negative response from its row. */
    suspend fun prepareResponse(requestId: String, now: Long): PreparedOpenPgpResponse?

    /** Completes and deletes approved response custody after broker acceptance. */
    suspend fun completeResponse(prepared: PreparedOpenPgpResponse, sentAt: Long): Boolean
}

@ConsistentCopyVisibility
data class PreparedOpenPgpResponse internal constructor(
    val requestId: String,
    val encodedBody: ByteArray,
    val durableCustody: Boolean,
)

/** Protocol request fingerprint used by Room duplicate/conflict checks. */
internal fun OpenPgpSignSync.sealRequestFingerprint(senderClientId: ClientId): ByteArray =
    SealFingerprintAccumulator().apply {
        text("NotiSync/seal/retained-request-context/v1")
        text("request")
        int(1)
        text(requestId)
        text(requesterClientId.value)
        text(senderClientId.value)
        long(issuedAt)
        long(expiresAt)
        text(primaryKeyId)
        bytes(payloadSha256)
        text(objectKind.name)
        text(workingDirectory)
    }.digest()

/** Small private equivalent of the importer framing; the runtime does not depend on importer code. */
private class SealFingerprintAccumulator {
    private val bytes = java.io.ByteArrayOutputStream()
    private val output = java.io.DataOutputStream(bytes)

    fun text(value: String?) {
        if (value == null) output.writeByte(0) else {
            output.writeByte(1)
            bytes(value.encodeToByteArray())
        }
    }

    fun long(value: Long) = output.writeLong(value)
    fun int(value: Int) = output.writeInt(value)

    fun bytes(value: ByteArray?) {
        if (value == null) output.writeInt(-1) else {
            output.writeInt(value.size)
            output.write(value)
        }
    }

    fun digest(): ByteArray {
        output.flush()
        // LegacySealSourceAdapters wraps the accumulator digest in ImportDigest.sha256 before
        // writing Room. Keep the double-SHA-256 framing so a post-migration replay is a duplicate.
        val inner = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
        return try {
            MessageDigest.getInstance("SHA-256").digest(inner)
        } finally {
            inner.fill(0)
        }
    }
}

/** Parses a request into the bounded history projection used by both the importer and Room runtime. */
internal fun ByteArray.toDisplaySnapshot(): GitCommitDisplaySnapshot? = runCatching {
    val parsed = GitCommitPayloadParser.parse(this)
    GitCommitDisplaySnapshot(
        treeId = parsed.treeId,
        parentIds = parsed.parentIds,
        author = parsed.author,
        committer = parsed.committer,
        message = parsed.message,
        extraHeaders = parsed.headers
            .filterNot { it.name in setOf("tree", "parent", "author", "committer") }
            .map { GitCommitDisplayHeader(it.name, it.value) },
        payloadBytes = size,
    ).boundedForHistory()
}.getOrNull()

internal fun GitCommitDisplaySnapshot.boundedForHistory(): GitCommitDisplaySnapshot {
    val boundedParents = parentIds.take(MAX_HISTORY_PARENTS)
    val boundedAuthor = author.take(MAX_HISTORY_IDENTITY_CHARS)
    val boundedCommitter = committer.take(MAX_HISTORY_IDENTITY_CHARS)
    val boundedMessage = message.take(MAX_HISTORY_MESSAGE_CHARS)
    val boundedHeaders = extraHeaders.take(MAX_HISTORY_HEADERS).map {
        GitCommitDisplayHeader(
            name = it.name.take(MAX_HISTORY_HEADER_NAME_CHARS),
            value = it.value.take(MAX_HISTORY_HEADER_VALUE_CHARS),
        )
    }
    return copy(
        parentIds = boundedParents,
        author = boundedAuthor,
        committer = boundedCommitter,
        message = boundedMessage,
        extraHeaders = boundedHeaders,
        truncated = truncated || boundedParents != parentIds || boundedAuthor != author ||
            boundedCommitter != committer || boundedMessage != message || boundedHeaders != extraHeaders,
    )
}

internal fun resultFor(reason: OpenPgpRejectReason): OpenPgpRequestResult = when (reason) {
    OpenPgpRejectReason.USER_REJECTED -> OpenPgpRequestResult.REJECTED
    OpenPgpRejectReason.EXPIRED -> OpenPgpRequestResult.EXPIRED
    OpenPgpRejectReason.PROVIDER_CANCELLED -> OpenPgpRequestResult.CANCELED
    OpenPgpRejectReason.PROVIDER_UNAVAILABLE,
    OpenPgpRejectReason.UNSUPPORTED_KEY,
    OpenPgpRejectReason.PROVIDER_FAILURE -> OpenPgpRequestResult.FAILED
}

internal fun OpenPgpRequestResult.toRejectReason(): OpenPgpRejectReason = when (this) {
    OpenPgpRequestResult.REJECTED -> OpenPgpRejectReason.USER_REJECTED
    OpenPgpRequestResult.CANCELED -> OpenPgpRejectReason.PROVIDER_CANCELLED
    OpenPgpRequestResult.EXPIRED -> OpenPgpRejectReason.EXPIRED
    OpenPgpRequestResult.FAILED,
    OpenPgpRequestResult.APPROVED -> OpenPgpRejectReason.PROVIDER_FAILURE
}

private const val MAX_HISTORY_PARENTS = 64
private const val MAX_HISTORY_IDENTITY_CHARS = 1_024
private const val MAX_HISTORY_MESSAGE_CHARS = 16 * 1_024
private const val MAX_HISTORY_HEADERS = 64
private const val MAX_HISTORY_HEADER_NAME_CHARS = 128
private const val MAX_HISTORY_HEADER_VALUE_CHARS = 2 * 1_024
