package net.extrawdw.apps.notisync.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.crypto.ClientIds
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

private const val ANDROID_KEYSTORE = "AndroidKeyStore"

/** Backing level of the identity key, surfaced in advanced diagnostics. */
enum class KeyBacking { STRONGBOX, TEE, SOFTWARE_FALLBACK }

/**
 * Hardware-backed identity signer: a non-exportable EC P-256 key in the Android Keystore, preferring
 * StrongBox and falling back to the TEE. Signs with SHA256withECDSA (DER), matching what the broker
 * and peers verify. The private key never leaves secure hardware.
 */
class AndroidIdentitySigner private constructor(
    override val publicKeySpki: ByteArray,
    override val clientId: ClientId,
    val backing: KeyBacking,
    private val privateKey: PrivateKey,
) : IdentitySigner {

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }

    companion object {
        private const val ALIAS = "notisync.identity.v1"

        fun loadOrCreate(): AndroidIdentitySigner {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let { entry ->
                val spki = entry.certificate.publicKey.encoded
                return AndroidIdentitySigner(spki, ClientIds.derive(spki), backingOf(ks), entry.privateKey)
            }
            val backing = generate()
            val entry = ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
            val spki = entry.certificate.publicKey.encoded
            return AndroidIdentitySigner(spki, ClientIds.derive(spki), backing, entry.privateKey)
        }

        private fun backingOf(ks: KeyStore): KeyBacking =
            // We cannot easily re-read the security level post-hoc on all OEMs; default to TEE here.
            // Fresh generation reports the true level (see generate()).
            KeyBacking.TEE

        private fun generate(): KeyBacking {
            fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .apply { if (strongBox) setIsStrongBoxBacked(true) }
                .build()

            return try {
                generateWith(spec(strongBox = true)); KeyBacking.STRONGBOX
            } catch (_: StrongBoxUnavailableException) {
                generateWith(spec(strongBox = false)); KeyBacking.TEE
            } catch (_: Exception) {
                generateWith(spec(strongBox = false)); KeyBacking.TEE
            }
        }

        private fun generateWith(spec: KeyGenParameterSpec) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).run {
                initialize(spec)
                generateKeyPair()
            }
        }
    }
}

/**
 * Wraps small secrets (the HPKE private keyset) with a non-exportable AES-256-GCM key held in the
 * Android Keystore, so plaintext key material is never persisted to disk.
 */
class KeyVault {
    private val key by lazy {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? javax.crypto.SecretKey) ?: run {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
            }.generateKey()
            ks.getKey(ALIAS, null) as javax.crypto.SecretKey
        }
    }

    fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    fun unwrap(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt()
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val ct = blob.copyOfRange(1 + ivLen, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(ct)
    }

    companion object {
        private const val ALIAS = "notisync.kek.v1"
    }
}

/** The HPKE keypair used for per-recipient payload-key sealing. Private keyset is wrapped on disk. */
class HpkeKeyManager(context: Context, private val vault: KeyVault) {
    private val publicFile = File(context.filesDir, "hpke_public.bin")
    private val privateFile = File(context.filesDir, "hpke_private.wrapped")

    lateinit var publicKeyset: ByteArray
        private set
    lateinit var privateKeyset: ByteArray
        private set

    fun loadOrCreate() {
        if (publicFile.exists() && privateFile.exists()) {
            publicKeyset = publicFile.readBytes()
            privateKeyset = vault.unwrap(privateFile.readBytes())
        } else {
            val pair = Hpke.generateKeyPair()
            publicKeyset = pair.publicKeyset
            privateKeyset = pair.privateKeyset
            publicFile.writeBytes(publicKeyset)
            privateFile.writeBytes(vault.wrap(privateKeyset))
        }
    }
}
