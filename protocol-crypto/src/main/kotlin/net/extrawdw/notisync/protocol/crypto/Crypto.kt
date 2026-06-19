package net.extrawdw.notisync.protocol.crypto

import com.google.crypto.tink.hybrid.HybridConfig
import net.extrawdw.notisync.protocol.Base32
import net.extrawdw.notisync.protocol.ClientId
import java.security.MessageDigest
import java.security.SecureRandom

/** One-time, idempotent registration of the Tink primitives NotiSync uses. */
internal object CryptoInit {
    @Volatile private var registered = false

    fun ensure() {
        if (registered) return
        synchronized(this) {
            if (!registered) {
                HybridConfig.register()
                registered = true
            }
        }
    }
}

internal val secureRandom: SecureRandom = SecureRandom()

internal fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

internal fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append("0123456789abcdef"[v ushr 4])
        sb.append("0123456789abcdef"[v and 0x0F])
    }
    return sb.toString()
}

/**
 * Client-id derivation. The id is the base32 of the first 20 bytes of SHA-256 over the X.509
 * SubjectPublicKeyInfo of the identity public key — reproducible by anyone holding the public key.
 */
object ClientIds {
    fun derive(identityPublicKeySpki: ByteArray): ClientId {
        val digest = sha256(identityPublicKeySpki)
        return ClientId(Base32.encode(digest.copyOf(20)))
    }
}

/** Content address (hex SHA-256) of a plaintext asset, computed before encryption for dedup. */
object AssetHash {
    fun of(plaintext: ByteArray): String = sha256(plaintext).toHex()

    /**
     * Constant-time check that [plaintext] hashes to [expectedHex]. The receiver runs this after
     * decrypting a private asset to catch a wrong key, a corrupt blob, a malicious server
     * substitution, or an id collision — the integrity gate before rendering.
     */
    fun matches(plaintext: ByteArray, expectedHex: String): Boolean =
        MessageDigest.isEqual(
            of(plaintext).toByteArray(Charsets.US_ASCII),
            expectedHex.toByteArray(Charsets.US_ASCII),
        )
}
