package net.extrawdw.apps.notisync.crypto

import android.content.Context
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import net.extrawdw.notisync.peer.transport.AuthTokenStore
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.IntegrityVerificationResponse
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.crypto.ClientIds
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.OperationalSigner
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val TAG = "DeviceCrypto"

/**
 * Android Keystore calls are synchronous Binder/secure-hardware round trips and can occasionally take
 * seconds. Failing fast on the main thread makes the threading contract executable: a future caller cannot
 * accidentally turn a slow key load, generation, signature, or cipher operation into an ANR.
 */
private fun requireKeystoreWorkerThread(operation: String) {
    check(Looper.myLooper() != Looper.getMainLooper()) {
        "$operation must run off the Android main thread"
    }
}

/** Backing level of the identity key, surfaced in advanced diagnostics. */
enum class KeyBacking { UNKNOWN, UNKNOWN_SECURE, SOFTWARE, TEE, STRONGBOX }

/**
 * SHA-256 fingerprints of public key material, colon-separated uppercase hex. [of] is the full digest (32
 * bytes); [short] truncates to the first [short]'s `bytes` for compact UI rows (16 bytes / 128 bits by
 * default). The pairing trust dialog and the diagnostics card share [short] so the same key reads identically
 * in both and a user can eyeball-match across screens — 128 bits is ample for a visual integrity check, since
 * the safety number (clientId) and the card/key-epoch signatures are the actual cryptographic anchors.
 */
object KeyFingerprint {
    /** Full SHA-256 fingerprint (32 bytes / 64 hex chars), colon-separated uppercase hex. */
    fun of(publicKey: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(publicKey))

    /** The first [bytes] bytes of the SHA-256 fingerprint — a compact form for UI display (default 8). */
    fun short(publicKey: ByteArray, bytes: Int = 8): String =
        hex(MessageDigest.getInstance("SHA-256").digest(publicKey).copyOf(bytes))

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
}

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

    override fun sign(data: ByteArray): ByteArray {
        requireKeystoreWorkerThread("Identity signing")
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }
    }

    companion object {
        internal const val KEY_ALIAS = "notisync.identity.v1"

        /**
         * Loads exactly one already-existing identity alias. This path never generates, replaces, or deletes an
         * entry and is therefore safe for retained-v51 activation and bootstrap-origin inspection.
         */
        fun loadExisting(alias: String = KEY_ALIAS): AndroidIdentitySigner? {
            requireKeystoreWorkerThread("Existing identity key load")
            require(alias.isNotBlank()) { "Identity key alias must not be blank" }
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val entry = keyStore.getEntry(alias, null) ?: return null
            check(entry is KeyStore.PrivateKeyEntry) { "Existing identity alias has an invalid key type" }
            val spki = entry.certificate.publicKey.encoded
            return AndroidIdentitySigner(
                spki,
                ClientIds.derive(spki),
                backingOf(entry.privateKey),
                entry.privateKey,
            )
        }

        fun loadOrCreate(): AndroidIdentitySigner {
            requireKeystoreWorkerThread("Identity key load/generation")
            loadExisting()?.let { return it }
            val backing = generate(KEY_ALIAS)
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
            val spki = entry.certificate.publicKey.encoded
            return AndroidIdentitySigner(spki, ClientIds.derive(spki), backing, entry.privateKey)
        }

        private fun backingOf(privateKey: PrivateKey): KeyBacking =
            runCatching {
                val info = KeyFactory.getInstance(privateKey.algorithm, ANDROID_KEYSTORE)
                    .getKeySpec(privateKey, KeyInfo::class.java)
                when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_UNKNOWN -> KeyBacking.UNKNOWN
                    KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> KeyBacking.UNKNOWN_SECURE
                    KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeyBacking.SOFTWARE
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyBacking.TEE
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyBacking.STRONGBOX
                    else -> KeyBacking.UNKNOWN
                }
            }.getOrElse {
                Log.w(TAG, "Unable to read identity key security level", it)
                KeyBacking.UNKNOWN
            }

        private fun generate(alias: String): KeyBacking {
            fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .apply { if (strongBox) setIsStrongBoxBacked(true) }
                .build()

            return try {
                backingOf(generateWith(spec(strongBox = true)))
            } catch (e: StrongBoxUnavailableException) {
                Log.w(TAG, "StrongBox identity key unavailable; falling back to TEE", e)
                backingOf(generateWith(spec(strongBox = false)))
            } catch (e: Exception) {
                Log.w(TAG, "StrongBox identity key generation failed; falling back to TEE", e)
                backingOf(generateWith(spec(strongBox = false)))
            }
        }

        private fun generateWith(spec: KeyGenParameterSpec): PrivateKey =
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).run {
                initialize(spec)
                generateKeyPair().private
            }
    }
}

/**
 * NS2 delegated OPERATIONAL signer: a non-exportable EC P-256 key in the Android Keystore, delegated by
 * the identity key via a `ClientKeyEpoch`. Deliberately **TEE, not StrongBox** — it signs every envelope
 * and authenticated request, and StrongBox is slow + rate-limited; the StrongBox identity root stays cold.
 *
 * The Keystore has no "require TEE" flag, so we generate without requesting StrongBox (→ TEE on modern
 * devices) and surface the real [backing] (read from the key's security level) plus the hardware
 * [attestationCertChain] (when an attestation challenge is supplied) so the server can require
 * TRUSTED_ENVIRONMENT and reject a software-backed key. [clientId] is the device IDENTITY fingerprint
 * (stable across rotation), not this key's; [signerEpoch] is the epoch this key belongs to.
 *
 * Aliased per epoch (`notisync.operational.v1.epoch{N}`) so rotation can create the next key and destroy
 * the retired alias after its overlap window.
 */
class AndroidOperationalSigner private constructor(
    override val operationalPublicKeySpki: ByteArray,
    override val clientId: ClientId,
    override val signerEpoch: Int,
    val backing: KeyBacking,
    /** DER-encoded X.509 attestation chain for this key, or empty when generated without a challenge. */
    val attestationCertChain: List<ByteArray>,
    private val privateKey: PrivateKey,
) : OperationalSigner {

    override fun sign(data: ByteArray): ByteArray {
        requireKeystoreWorkerThread("Operational signing")
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }
    }

    companion object {
        private const val ALIAS_PREFIX = "notisync.operational.v1.epoch"

        fun aliasFor(epoch: Int): String = "$ALIAS_PREFIX$epoch"

        /** Strict retained-key load. Missing aliases return null; no generation or mutation is attempted. */
        fun loadExisting(
            clientId: ClientId,
            epoch: Int,
            alias: String = aliasFor(epoch),
        ): AndroidOperationalSigner? {
            requireKeystoreWorkerThread("Existing operational key load")
            require(epoch > 0) { "Operational key epoch must be positive" }
            require(alias.isNotBlank()) { "Operational key alias must not be blank" }
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val entry = keyStore.getEntry(alias, null) ?: return null
            check(entry is KeyStore.PrivateKeyEntry) { "Existing operational alias has an invalid key type" }
            return fromEntry(keyStore, alias, entry, clientId, epoch)
        }

        /**
         * Load (or generate) the operational key for [epoch], bound to the device [clientId]. Supply an
         * [attestationChallenge] (e.g. a Play Integrity nonce) on first generation to obtain a hardware
         * attestation chain proving the key lives in the TEE.
         */
        fun loadOrCreate(
            clientId: ClientId,
            epoch: Int,
            attestationChallenge: ByteArray? = null
        ): AndroidOperationalSigner {
            requireKeystoreWorkerThread("Operational key load/generation")
            val alias = aliasFor(epoch)
            loadExisting(clientId, epoch, alias)?.let { return it }
            generate(alias, attestationChallenge)
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val entry = keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
            return fromEntry(keyStore, alias, entry, clientId, epoch)
        }

        /** Destroy the operational key for [epoch] after its retirement window (rotation cleanup). */
        fun destroy(epoch: Int) {
            requireKeystoreWorkerThread("Operational key deletion")
            runCatching {
                KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                    .deleteEntry(aliasFor(epoch))
            }
        }

        /** Epochs that still have an operational key in the Keystore (highest is current). Used by the
         *  rotation GC to find retired keys whose [destroy] was skipped/failed (forward-secrecy backstop). */
        fun retainedEpochs(): List<Int> {
            requireKeystoreWorkerThread("Operational key enumeration")
            return runCatching {
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                ks.aliases().toList().mapNotNull { a ->
                    if (a.startsWith(ALIAS_PREFIX)) a.removePrefix(ALIAS_PREFIX).toIntOrNull() else null
                }.sorted()
            }.getOrDefault(emptyList())
        }

        private fun fromEntry(
            ks: KeyStore,
            alias: String,
            entry: KeyStore.PrivateKeyEntry,
            clientId: ClientId,
            epoch: Int,
        ): AndroidOperationalSigner {
            val spki = entry.certificate.publicKey.encoded
            val chain =
                (ks.getCertificateChain(alias) ?: arrayOf(entry.certificate)).map { it.encoded }
            return AndroidOperationalSigner(
                spki,
                clientId,
                epoch,
                operationalBackingOf(entry.privateKey),
                chain,
                entry.privateKey
            )
        }

        private fun generate(alias: String, attestationChallenge: ByteArray?) {
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                // Deliberately NOT StrongBox — TEE for hot-path throughput.
                .apply {
                    if (attestationChallenge != null) setAttestationChallenge(
                        attestationChallenge
                    )
                }
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).run {
                initialize(spec)
                generateKeyPair()
            }
        }

        private fun operationalBackingOf(privateKey: PrivateKey): KeyBacking =
            runCatching {
                val info = KeyFactory.getInstance(privateKey.algorithm, ANDROID_KEYSTORE)
                    .getKeySpec(privateKey, KeyInfo::class.java)
                when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_UNKNOWN -> KeyBacking.UNKNOWN
                    KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> KeyBacking.UNKNOWN_SECURE
                    KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeyBacking.SOFTWARE
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyBacking.TEE
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyBacking.STRONGBOX
                    else -> KeyBacking.UNKNOWN
                }
            }.getOrElse {
                Log.w(TAG, "Unable to read operational key security level", it)
                KeyBacking.UNKNOWN
            }
    }
}

/**
 * Wraps small secrets (the HPKE private keyset) with a non-exportable AES-256-GCM key held in the
 * Android Keystore, so plaintext key material is never persisted to disk.
 */
class KeyVault private constructor(
    private val alias: String,
    existingKey: SecretKey?,
) {
    constructor() : this(KEY_ALIAS, null)

    private val key by lazy {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        existingKey ?: (ks.getKey(alias, null) as? SecretKey) ?: run {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
            }.generateKey()
            ks.getKey(alias, null) as SecretKey
        }
    }

    val backing: KeyBacking
        get() = secretKeyBackingOf(key)

    fun wrap(plain: ByteArray): ByteArray {
        requireKeystoreWorkerThread("Vault encryption")
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    fun unwrap(blob: ByteArray): ByteArray {
        requireKeystoreWorkerThread("Vault decryption")
        val ivLen = blob[0].toInt()
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val ct = blob.copyOfRange(1 + ivLen, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(ct)
    }

    companion object {
        internal const val KEY_ALIAS = "notisync.kek.v1"

        /** Strict retained-key load used by v51 activation. It never generates or changes an alias. */
        fun loadExisting(alias: String = KEY_ALIAS): KeyVault? {
            requireKeystoreWorkerThread("Existing wrapping key load")
            require(alias.isNotBlank()) { "Wrapping key alias must not be blank" }
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val key = keyStore.getKey(alias, null) ?: return null
            check(key is SecretKey) { "Existing wrapping alias has an invalid key type" }
            return KeyVault(alias, key)
        }
    }
}

private fun secretKeyBackingOf(secretKey: SecretKey): KeyBacking =
    runCatching {
        val info = SecretKeyFactory.getInstance(secretKey.algorithm, ANDROID_KEYSTORE)
            .getKeySpec(secretKey, KeyInfo::class.java) as KeyInfo
        when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_UNKNOWN -> KeyBacking.UNKNOWN
            KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> KeyBacking.UNKNOWN_SECURE
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeyBacking.SOFTWARE
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyBacking.TEE
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyBacking.STRONGBOX
            else -> KeyBacking.UNKNOWN
        }
    }.getOrElse {
        Log.w(TAG, "Unable to read wrapping key security level", it)
        KeyBacking.UNKNOWN
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

/**
 * NS2 epoch-indexed HPKE keysets: one keypair per [epoch], the private keyset wrapped at rest by
 * [KeyVault]. The receiver retains a RING of prior epochs' private keysets across a grace window (≥ the
 * relay TTL) so an in-flight envelope sealed to a now-rotated key still opens; [EnvelopeCrypto.open]
 * selects the keyset by the sender's `recipientEpoch`. Rotation mints the next epoch and [prune] removes
 * retired keysets after their grace window.
 */
interface EpochHpkeKeyring {
    fun loadOrCreate(epoch: Int): ByteArray
    fun publicKeyset(epoch: Int): ByteArray?
    fun privateKeyset(epoch: Int): ByteArray?
    fun retainedEpochs(): List<Int>
    fun prune(keep: Set<Int>)
}

class EpochHpkeKeyManager(context: Context, private val vault: KeyVault) : EpochHpkeKeyring {
    private val dir = context.filesDir
    private fun publicFile(epoch: Int) = File(dir, "hpke_public.epoch$epoch.bin")
    private fun privateFile(epoch: Int) = File(dir, "hpke_private.epoch$epoch.wrapped")

    /** Load the public keyset for [epoch], generating + persisting a fresh keypair if absent. */
    override fun loadOrCreate(epoch: Int): ByteArray {
        publicKeyset(epoch)?.let { return it }
        val pair = Hpke.generateKeyPair()
        privateFile(epoch).writeBytes(vault.wrap(pair.privateKeyset))
        publicFile(epoch).writeBytes(pair.publicKeyset)
        return pair.publicKeyset
    }

    override fun publicKeyset(epoch: Int): ByteArray? = publicFile(epoch).takeIf { it.exists() }?.readBytes()

    /** The unwrapped private keyset for [epoch], or null if not retained — used by [EnvelopeCrypto.open]. */
    override fun privateKeyset(epoch: Int): ByteArray? =
        privateFile(epoch).takeIf { it.exists() }?.let { vault.unwrap(it.readBytes()) }

    /** Epochs whose keysets are still retained (highest is current). */
    override fun retainedEpochs(): List<Int> =
        dir.listFiles { f -> f.name.startsWith("hpke_private.epoch") }
            .orEmpty()
            .mapNotNull {
                it.name.removePrefix("hpke_private.epoch").removeSuffix(".wrapped").toIntOrNull()
            }
            .sorted()

    /** Destroy any retained keyset whose epoch is not in [keep] (rotation cleanup after the grace window). */
    override fun prune(keep: Set<Int>) {
        for (epoch in retainedEpochs()) if (epoch !in keep) {
            privateFile(epoch).delete()
            publicFile(epoch).delete()
        }
    }
}

/**
 * Persists the broker auth token (the cached proof of a passing attestation) wrapped with [KeyVault], so the
 * JWT never sits in plaintext on disk and survives process death — a force-stop/relaunch can reuse a
 * still-valid token instead of re-attesting. Self-healing: an unreadable blob simply reads as absent and
 * the next request re-attests.
 */
class KeyVaultAuthTokenStore(context: Context, private val vault: KeyVault) : AuthTokenStore {
    private val file = File(context.filesDir, "auth_token.wrapped")

    @Synchronized
    override fun load(): IntegrityVerificationResponse? {
        if (!file.exists()) return null
        return runCatching {
            ProtocolCodec.decodeFromJson<IntegrityVerificationResponse>(
                vault.unwrap(file.readBytes()).toString(Charsets.UTF_8),
            )
        }.getOrNull()
    }

    @Synchronized
    override fun save(token: IntegrityVerificationResponse?) {
        if (token == null) {
            file.delete()
            return
        }
        runCatching {
            file.writeBytes(
                vault.wrap(
                    ProtocolCodec.encodeToJson(token).toByteArray(Charsets.UTF_8)
                )
            )
        }
    }
}
