package net.extrawdw.notisync.protocol

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * Stable client identifier. Conceptually the fingerprint of a client's identity public key:
 * base32(SHA-256(X.509 SubjectPublicKeyInfo))[..20 bytes]. Derivation lives in :protocol-crypto;
 * this is the wire/storage type. Serializes transparently as its underlying string.
 */
@JvmInline
@Serializable
value class ClientId(val value: String) {
    override fun toString(): String = value

    /** Short, human-glanceable fragment for UI ("a3b2…f8c1"). */
    fun shortForm(): String =
        if (value.length <= 9) value else "${value.take(4)}…${value.takeLast(4)}"
}

/** Identifier of a trusted group (the implicit set of a user's paired devices). */
@JvmInline
@Serializable
value class GroupId(val value: String) {
    override fun toString(): String = value
}

/** RFC 4648 base32 (lowercase, no padding). Used for client-id derivation and safety numbers. */
object Base32 {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 0x1F
                sb.append(ALPHABET[index])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1F
            sb.append(ALPHABET[index])
        }
        return sb.toString()
    }

    /** Decode a lowercase, no-padding base32 string, or null if any character is outside the alphabet. */
    fun decode(text: String): ByteArray? {
        if (text.isEmpty()) return ByteArray(0)
        val out = ByteArray(text.length * 5 / 8)
        var buffer = 0
        var bitsLeft = 0
        var i = 0
        for (c in text) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out[i++] = (buffer shr (bitsLeft - 8)).toByte()
                bitsLeft -= 8
            }
        }
        return out
    }
}
