package net.extrawdw.notisync.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString

@Serializable
enum class MessageType { NOTIFICATION, DISMISSAL, DATA_SYNC }

/** The data-encryption key for one envelope, HPKE-sealed to a single recipient's public keyset. */
@Serializable
data class PerRecipientKey(
    val recipientId: ClientId,
    @ByteString val sealedDek: ByteArray,
)

/**
 * The end-to-end encrypted unit the broker fans out. The body is sealed once with a random DEK
 * (AES-256-GCM over CBOR of the payload, nonce-prefixed); the DEK is then HPKE-sealed once per
 * recipient. The broker sees only routing metadata (recipient ids, message id) and opaque
 * ciphertext — never the DEK or the content.
 */
@Serializable
data class Envelope(
    val v: Int = 1,
    val suite: String = CipherSuite.CURRENT_ID,
    val typ: MessageType,
    val signerId: ClientId,
    /** Unique per message — recipients dedupe on this. */
    val messageId: String,
    /** Per-sender monotonic counter for ordering and replay detection. */
    val seq: Long,
    val createdAt: Long,
    @ByteString val bodyCiphertext: ByteArray,
    val recipients: List<PerRecipientKey>,
    /** Plaintext SHA-256 hashes of attachments referenced by the body (fetched as encrypted blobs). */
    val attachments: List<String> = emptyList(),
    /** ECDSA-P256 signature by [signerId] over [EnvelopeAuth] (source authenticity + replay binding). */
    @ByteString val sig: ByteArray = ByteArray(0),
) {
    /** The recipient ids in claim order — bound into the signature. */
    fun recipientIds(): List<ClientId> = recipients.map { it.recipientId }
}

/**
 * The stable, signed view of an [Envelope]. The sender signs the CBOR encoding of this; recipients
 * reconstruct it from envelope fields and the SHA-256 of the received ciphertext, then verify.
 * Keeping the signed bytes small (a hash, not the ciphertext) avoids signing large payloads.
 */
@Serializable
data class EnvelopeAuth(
    val v: Int,
    val suite: String,
    val typ: MessageType,
    val signerId: ClientId,
    val messageId: String,
    val seq: Long,
    val createdAt: Long,
    @ByteString val bodyCiphertextSha256: ByteArray,
    val recipientIds: List<ClientId>,
    val attachments: List<String>,
)
