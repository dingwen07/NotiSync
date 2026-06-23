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
    /**
     * NS2: which of the recipient's HPKE key epochs this DEK was sealed to, so the recipient selects the
     * matching (possibly retained, pre-rotation) private keyset. 0 = the identity-era single HPKE key
     * (NS1-compatible). Bound into the HPKE context and [EnvelopeAuth] so a (claimed, sealed) epoch
     * mismatch fails AEAD.
     */
    val recipientEpoch: Int = 0,
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
    /**
     * NS2: which key signed this envelope. 0 = [signerId]'s identity key (NS1-compatible; also used for
     * identity-anchored control bodies like a trust roster); ≥1 = the operational key of that
     * [ClientKeyEpoch]. Bound into [EnvelopeAuth] so it cannot be stripped or swapped.
     */
    val signerEpoch: Int = 0,
    /** Unique per message — recipients dedupe on this. */
    val messageId: String,
    /** Per-sender monotonic counter for ordering and replay detection. */
    val seq: Long,
    val createdAt: Long,
    @ByteString val bodyCiphertext: ByteArray,
    val recipients: List<PerRecipientKey>,
    /** ECDSA-P256 signature by [signerId] over [EnvelopeAuth] (source authenticity + replay binding). */
    @ByteString val sig: ByteArray = ByteArray(0),
) {
    /** The recipient ids in claim order — bound into the signature. */
    fun recipientIds(): List<ClientId> = recipients.map { it.recipientId }

    /** The per-recipient HPKE epochs in claim order — bound into the signature (NS2). */
    fun recipientEpochs(): List<Int> = recipients.map { it.recipientEpoch }
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
    /** NS2: the signing key selector (0 = identity, ≥1 = operational epoch). */
    val signerEpoch: Int,
    val messageId: String,
    val seq: Long,
    val createdAt: Long,
    @ByteString val bodyCiphertextSha256: ByteArray,
    val recipientIds: List<ClientId>,
    /** NS2: per-recipient HPKE epochs, parallel to [recipientIds]. */
    val recipientEpochs: List<Int>,
)
