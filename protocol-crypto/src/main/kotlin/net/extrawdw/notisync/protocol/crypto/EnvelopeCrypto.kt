package net.extrawdw.notisync.protocol.crypto

import net.extrawdw.notisync.protocol.CipherSuite
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.EnvelopeAuth
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.PerRecipientKey
import net.extrawdw.notisync.protocol.ProtocolCodec

/** A recipient's id paired with their published HPKE public keyset (from their client card). */
class RecipientKey(val clientId: ClientId, val hpkePublicKeyset: ByteArray)

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

    private fun dekContext(suite: String, recipientId: ClientId): ByteArray =
        "notisync:dek:$suite|${recipientId.value}".toByteArray(Charsets.UTF_8)

    /** The exact bytes signed/verified for an envelope (independent of the sig field itself). */
    fun authBytes(env: Envelope): ByteArray {
        val auth = EnvelopeAuth(
            v = env.v,
            suite = env.suite,
            typ = env.typ,
            signerId = env.signerId,
            messageId = env.messageId,
            seq = env.seq,
            createdAt = env.createdAt,
            bodyCiphertextSha256 = sha256(env.bodyCiphertext),
            recipientIds = env.recipientIds(),
            attachments = env.attachments,
        )
        return ProtocolCodec.encodeToCbor(auth)
    }

    /**
     * Seal [bodyPlaintext] (already CBOR of a CapturedNotification/DismissEvent) to [recipients] and
     * return a fully signed [Envelope].
     */
    fun seal(
        signer: IdentitySigner,
        typ: MessageType,
        bodyPlaintext: ByteArray,
        recipients: List<RecipientKey>,
        messageId: String,
        seq: Long,
        createdAt: Long,
        attachments: List<String> = emptyList(),
        suite: String = CipherSuite.CURRENT_ID,
    ): Envelope {
        val dek = BodyAead.generateDek()
        try {
            val bodyCiphertext = BodyAead.seal(dek, bodyPlaintext, bodyAad(suite))
            val perRecipient = recipients.map { r ->
                PerRecipientKey(r.clientId, Hpke.seal(dek, r.hpkePublicKeyset, dekContext(suite, r.clientId)))
            }
            val unsigned = Envelope(
                suite = suite,
                typ = typ,
                signerId = signer.clientId,
                messageId = messageId,
                seq = seq,
                createdAt = createdAt,
                bodyCiphertext = bodyCiphertext,
                recipients = perRecipient,
                attachments = attachments,
            )
            return unsigned.copy(sig = signer.sign(authBytes(unsigned)))
        } finally {
            dek.fill(0)
        }
    }

    /** Verify the source signature on an envelope given the sender's identity SPKI. */
    fun verify(env: Envelope, senderSpki: ByteArray): Boolean =
        IdentityVerifier.verifyBound(env.signerId, senderSpki, authBytes(env), env.sig)

    /**
     * Open an envelope addressed to this device. Returns the decrypted body bytes (CBOR), or throws
     * if this device is not a recipient or decryption fails.
     */
    fun open(env: Envelope, myClientId: ClientId, myPrivateHpkeKeyset: ByteArray): ByteArray {
        val mine = env.recipients.firstOrNull { it.recipientId == myClientId }
            ?: throw IllegalArgumentException("envelope ${env.messageId} has no key for $myClientId")
        val dek = Hpke.open(mine.sealedDek, myPrivateHpkeKeyset, dekContext(env.suite, myClientId))
        try {
            return BodyAead.open(dek, env.bodyCiphertext, bodyAad(env.suite))
        } finally {
            dek.fill(0)
        }
    }
}
