package net.extrawdw.apps.notisync.messaging.inbound

import java.security.MessageDigest
import net.extrawdw.apps.notisync.data.relay.AuthenticatedRelayToken
import net.extrawdw.notisync.protocol.MessageType

/** Derives the relay conflict/dedup identity without inspecting or hashing decrypted feature bytes. */
internal fun interface InboundEnvelopeFingerprint {
    fun derive(envelope: DecodedInboundEnvelope): AuthenticatedRelayToken
}

/**
 * SHA-256 over the complete current envelope projection using an explicit, versioned encoding.
 *
 * V1 layout (all integers signed, big-endian; strings are UTF-8; variable fields are u32-length framed):
 *
 * `domain || formatVersion || envelopeVersion || suite || messageTypeToken || signerId || signerEpoch ||`
 * `messageId || sequence || createdAt || ciphertext || recipientCount ||`
 * `each(recipientId || sealedDek || recipientEpoch) || signature`.
 *
 * This canonical semantic representation deliberately includes the signature and exact ciphertext/sealed-key bytes,
 * not merely the smaller [net.extrawdw.notisync.protocol.EnvelopeAuth] signature projection. It does not include the
 * decrypted body. Re-encodings of the same known V1 fields therefore have one identity, while any authenticated-field,
 * ciphertext, recipient, or signature substitution changes the token. Future field semantics require a new format.
 */
internal object CanonicalInboundEnvelopeFingerprint : InboundEnvelopeFingerprint {
    const val FORMAT_VERSION = 1

    override fun derive(envelope: DecodedInboundEnvelope): AuthenticatedRelayToken {
        val writer = DigestWriter(MessageDigest.getInstance("SHA-256"))
        writer.raw(DOMAIN)
        writer.int(FORMAT_VERSION)
        writer.int(envelope.protocolVersion)
        writer.string(envelope.suite)
        writer.string(envelope.messageType.fingerprintToken())
        writer.string(envelope.signerId.value)
        writer.int(envelope.signerEpoch)
        writer.string(envelope.messageId)
        writer.long(envelope.signedSequence)
        writer.long(envelope.signedCreatedAt)
        envelope.copyBodyCiphertext().also { bytes ->
            try {
                writer.bytes(bytes)
            } finally {
                bytes.fill(0)
            }
        }
        val recipients = envelope.recipients
        writer.int(recipients.size)
        recipients.forEach { recipient ->
            writer.string(recipient.recipientId.value)
            recipient.copySealedDek().also { bytes ->
                try {
                    writer.bytes(bytes)
                } finally {
                    bytes.fill(0)
                }
            }
            writer.int(recipient.recipientEpoch)
        }
        envelope.copySignature().also { bytes ->
            try {
                writer.bytes(bytes)
            } finally {
                bytes.fill(0)
            }
        }
        return AuthenticatedRelayToken.of(writer.finish())
    }

    private class DigestWriter(private val digest: MessageDigest) {
        fun raw(value: ByteArray) {
            digest.update(value)
        }

        fun bytes(value: ByteArray) {
            int(value.size)
            digest.update(value)
        }

        fun string(value: String) {
            bytes(value.toByteArray(Charsets.UTF_8))
        }

        fun int(value: Int) {
            digest.update((value ushr 24).toByte())
            digest.update((value ushr 16).toByte())
            digest.update((value ushr 8).toByte())
            digest.update(value.toByte())
        }

        fun long(value: Long) {
            digest.update((value ushr 56).toByte())
            digest.update((value ushr 48).toByte())
            digest.update((value ushr 40).toByte())
            digest.update((value ushr 32).toByte())
            digest.update((value ushr 24).toByte())
            digest.update((value ushr 16).toByte())
            digest.update((value ushr 8).toByte())
            digest.update(value.toByte())
        }

        fun finish(): ByteArray = digest.digest()
    }

    private val DOMAIN = "net.extrawdw.notisync/inbound-envelope-fingerprint\u0000"
        .toByteArray(Charsets.US_ASCII)
}

private fun MessageType.fingerprintToken(): String = when (this) {
    MessageType.NOTIFICATION -> "notification"
    MessageType.DISMISSAL -> "dismissal"
    MessageType.DATA_SYNC -> "data_sync"
    MessageType.ACTION -> "action"
}
