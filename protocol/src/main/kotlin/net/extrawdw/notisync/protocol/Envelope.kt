package net.extrawdw.notisync.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.ALWAYS
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.cbor.CborLabel

/** Append-only — keep CBOR ordinals stable. [ACTION] is always unicast to the notification's
 *  origin client (the only peer that can perform it); it never fans out. */
@Serializable
enum class MessageType { NOTIFICATION, DISMISSAL, DATA_SYNC, ACTION }

/** The data-encryption key for one envelope, HPKE-sealed to a single recipient's public keyset. */
@Serializable
data class PerRecipientKey(
    @CborLabel(0) val recipientId: ClientId,
    @CborLabel(1) @ByteString val sealedDek: ByteArray,
    /**
     * NS2: which of the recipient's HPKE key epochs this DEK was sealed to, so the recipient selects the
     * matching (possibly retained, pre-rotation) private keyset. 0 = the identity-era single HPKE key
     * (NS1-compatible). Bound into the HPKE context and [EnvelopeAuth] so a (claimed, sealed) epoch
     * mismatch fails AEAD.
     */
    @CborLabel(2) val recipientEpoch: Int = 0,
)

/**
 * The end-to-end encrypted unit the broker fans out. The body is sealed once with a random DEK
 * (AES-256-GCM over CBOR of the payload, nonce-prefixed); the DEK is then HPKE-sealed once per
 * recipient. The broker sees only routing metadata (recipient ids, message id) and opaque
 * ciphertext — never the DEK or the content.
 */
@Serializable
data class Envelope(
    @CborLabel(0) @EncodeDefault(ALWAYS) val v: Int = 1,
    @CborLabel(1) @EncodeDefault(ALWAYS) val suite: String = CipherSuite.CURRENT_ID,
    @CborLabel(2) val typ: MessageType,
    @CborLabel(3) val signerId: ClientId,
    /**
     * NS2: which key signed this envelope. 0 = [signerId]'s identity key (NS1-compatible; also used for
     * identity-anchored control bodies like a trust roster); ≥1 = the operational key of that
     * [ClientKeyEpoch]. Bound into [EnvelopeAuth] so it cannot be stripped or swapped.
     */
    @CborLabel(4) val signerEpoch: Int = 0,
    /** Unique per message — recipients dedupe on this. */
    @CborLabel(5) val messageId: String,
    /** Per-sender monotonic counter for ordering and replay detection. */
    @CborLabel(6) val seq: Long,
    @CborLabel(7) val createdAt: Long,
    @CborLabel(8) @ByteString val bodyCiphertext: ByteArray,
    @CborLabel(9) val recipients: List<PerRecipientKey>,
    /** ECDSA-P256 signature by [signerId] over [EnvelopeAuth] (source authenticity + replay binding). */
    @CborLabel(10) @ByteString val sig: ByteArray = ByteArray(0),
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
    @CborLabel(0) val v: Int,
    @CborLabel(1) val suite: String,
    @CborLabel(2) val typ: MessageType,
    @CborLabel(3) val signerId: ClientId,
    /** NS2: the signing key selector (0 = identity, ≥1 = operational epoch). */
    @CborLabel(4) val signerEpoch: Int,
    @CborLabel(5) val messageId: String,
    @CborLabel(6) val seq: Long,
    @CborLabel(7) val createdAt: Long,
    @CborLabel(8) @ByteString val bodyCiphertextSha256: ByteArray,
    @CborLabel(9) val recipientIds: List<ClientId>,
    /** NS2: per-recipient HPKE epochs, parallel to [recipientIds]. */
    @CborLabel(10) val recipientEpochs: List<Int>,
)
