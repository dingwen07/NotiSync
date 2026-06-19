package net.extrawdw.notisync.protocol.crypto

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Symmetric AEAD for the notification body, keyed by a per-message DEK. AES-256-GCM via JCA
 * (available identically on Android and the JVM). The 12-byte random nonce is prefixed to the
 * ciphertext: output = nonce(12) || ciphertext+tag.
 */
object BodyAead {
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    fun generateDek(): ByteArray = ByteArray(KEY_BYTES).also { secureRandom.nextBytes(it) }

    fun seal(dek: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
        }
        val ct = cipher.doFinal(plaintext)
        return nonce + ct
    }

    fun open(dek: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray {
        require(sealed.size > NONCE_BYTES) { "ciphertext too short" }
        val nonce = sealed.copyOfRange(0, NONCE_BYTES)
        val ct = sealed.copyOfRange(NONCE_BYTES, sealed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
        }
        return cipher.doFinal(ct)
    }
}
