package net.extrawdw.notisync.protocol.crypto

import net.extrawdw.notisync.protocol.CipherSuite
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.EnvelopeAuth
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.PerRecipientKey
import net.extrawdw.notisync.protocol.ProtocolCodec

/**
 * A recipient's id paired with their published HPKE public keyset. [recipientEpoch] is the epoch of the
 * keyset (0 = the NS1 identity-era single key); it is recorded on the sealed key and bound into the HPKE
 * context so the recipient selects the matching (possibly retained) private keyset.
 */
class RecipientKey(val clientId: ClientId, val hpkePublicKeyset: ByteArray, val recipientEpoch: Int = 0)

/**
 * The two-layer E2E scheme for the notification pipeline:
 *  1. seal the CBOR body once with a random DEK (AES-256-GCM),
 *  2. HPKE-seal that DEK once per recipient.
 * The broker fans out by recipient id, cryptographically blind to the DEK and the content.
 *
 * Source authenticity + replay binding come from an ECDSA-P256 signature over [EnvelopeAuth]
 * (which hashes the ciphertext rather than carrying it, keeping the signed bytes small).
 */
object EnvelopeCrypto {

    private fun bodyAad(suite: String): ByteArray = "notisync:body:$suite".toByteArray(Charsets.UTF_8)

    /**
     * HPKE context for a recipient's sealed DEK. The [recipientEpoch] is always bound, so a DEK sealed
     * under epoch M cannot be opened as epoch N (cross-epoch confusion).
     */
    private fun dekContext(suite: String, recipientId: ClientId, recipientEpoch: Int): ByteArray =
        "notisync:dek:$suite|${recipientId.value}|$recipientEpoch".toByteArray(Charsets.UTF_8)

    /** The exact bytes signed/verified for an envelope (independent of the sig field itself). */
    fun authBytes(env: Envelope): ByteArray {
        val auth = EnvelopeAuth(
            v = env.v,
            suite = env.suite,
            typ = env.typ,
            signerId = env.signerId,
            signerEpoch = env.signerEpoch,
            messageId = env.messageId,
            seq = env.seq,
            createdAt = env.createdAt,
            bodyCiphertextSha256 = sha256(env.bodyCiphertext),
            recipientIds = env.recipientIds(),
            recipientEpochs = env.recipientEpochs(),
        )
        return ProtocolCodec.encodeToCbor(auth)
    }

    /**
     * Seal [bodyPlaintext] (already CBOR of a CapturedNotification/DismissEvent) to [recipients] with
     * the device IDENTITY key (signerEpoch 0), returning a fully signed [Envelope]. Used for NS1 traffic
     * and NS2 identity-anchored control bodies (e.g. a trust roster).
     */
    fun seal(
        signer: IdentitySigner,
        typ: MessageType,
        bodyPlaintext: ByteArray,
        recipients: List<RecipientKey>,
        messageId: String,
        seq: Long,
        createdAt: Long,
        suite: String = CipherSuite.CURRENT_ID,
    ): Envelope = sealInternal(signer.clientId, 0, signer::sign, typ, bodyPlaintext, recipients, messageId, seq, createdAt, suite)

    /**
     * Seal with a delegated OPERATIONAL key (signerEpoch ≥ 1) — the NS2 hot path for
     * notifications/dismissals/asset sync. [signer]'s [OperationalSigner.clientId] is the device
     * identity fingerprint; its key was authorized by that client's [net.extrawdw.notisync.protocol.ClientKeyEpoch].
     */
    fun seal(
        signer: OperationalSigner,
        typ: MessageType,
        bodyPlaintext: ByteArray,
        recipients: List<RecipientKey>,
        messageId: String,
        seq: Long,
        createdAt: Long,
        suite: String = CipherSuite.CURRENT_ID,
    ): Envelope = sealInternal(signer.clientId, signer.signerEpoch, signer::sign, typ, bodyPlaintext, recipients, messageId, seq, createdAt, suite)

    private fun sealInternal(
        signerId: ClientId,
        signerEpoch: Int,
        sign: (ByteArray) -> ByteArray,
        typ: MessageType,
        bodyPlaintext: ByteArray,
        recipients: List<RecipientKey>,
        messageId: String,
        seq: Long,
        createdAt: Long,
        suite: String,
    ): Envelope {
        val dek = BodyAead.generateDek()
        try {
            val bodyCiphertext = BodyAead.seal(dek, bodyPlaintext, bodyAad(suite))
            // Seal the DEK per recipient defensively: a single recipient whose published HPKE key can't be
            // sealed to — an unreadable/corrupt key, or a wire format this build predates — must NOT abort the
            // whole fan-out. Drop that one and still deliver to everyone else (authBytes covers only the sealed
            // recipients, so the signature stays consistent). Throw only when NONE seal, so the caller — which
            // can log/skip — never ships an envelope no one can open. Caller compares the returned recipient
            // count to the requested set to surface partial drops.
            val perRecipient = recipients.mapNotNull { r ->
                runCatching {
                    PerRecipientKey(
                        recipientId = r.clientId,
                        sealedDek = Hpke.seal(dek, r.hpkePublicKeyset, dekContext(suite, r.clientId, r.recipientEpoch)),
                        recipientEpoch = r.recipientEpoch,
                    )
                }.getOrNull()
            }
            require(perRecipient.isNotEmpty() || recipients.isEmpty()) {
                "all ${recipients.size} recipient(s) failed to seal for message $messageId"
            }
            val unsigned = Envelope(
                suite = suite,
                typ = typ,
                signerId = signerId,
                signerEpoch = signerEpoch,
                messageId = messageId,
                seq = seq,
                createdAt = createdAt,
                bodyCiphertext = bodyCiphertext,
                recipients = perRecipient,
            )
            return unsigned.copy(sig = sign(authBytes(unsigned)))
        } finally {
            dek.fill(0)
        }
    }

    /**
     * Verify the source signature on an envelope. For [Envelope.signerEpoch] 0 the [signerSpki] is the
     * sender's IDENTITY key and the fingerprint binding (`clientId == fingerprint(spki)`) is enforced.
     * For epoch ≥ 1 the [signerSpki] is the OPERATIONAL key resolved from a verified
     * [net.extrawdw.notisync.protocol.ClientKeyEpoch] (see [KeyEpochs.verify]); the clientId binding came
     * from that key-epoch, so this only checks the signature.
     */
    fun verify(env: Envelope, signerSpki: ByteArray): Boolean =
        if (env.signerEpoch == 0) IdentityVerifier.verifyBound(env.signerId, signerSpki, authBytes(env), env.sig)
        else IdentityVerifier.verify(signerSpki, authBytes(env), env.sig)

    /**
     * Open an envelope addressed to this device. Returns the decrypted body bytes (CBOR), or throws
     * if this device is not a recipient or decryption fails. [myPrivateHpkeKeyset] must be the keyset for
     * the recipient epoch the sender sealed to (see [PerRecipientKey.recipientEpoch]); a caller holding a
     * ring of retained keysets selects it by that epoch.
     */
    fun open(env: Envelope, myClientId: ClientId, myPrivateHpkeKeyset: ByteArray): ByteArray {
        val mine = env.recipients.firstOrNull { it.recipientId == myClientId }
            ?: throw IllegalArgumentException("envelope ${env.messageId} has no key for $myClientId")
        val dek = Hpke.open(mine.sealedDek, myPrivateHpkeKeyset, dekContext(env.suite, myClientId, mine.recipientEpoch))
        try {
            return BodyAead.open(dek, env.bodyCiphertext, bodyAad(env.suite))
        } finally {
            dek.fill(0)
        }
    }
}
