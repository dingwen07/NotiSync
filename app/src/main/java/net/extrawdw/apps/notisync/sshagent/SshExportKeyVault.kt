package net.extrawdw.apps.notisync.sshagent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import net.extrawdw.notisync.protocol.SshKeyAlgorithm
import net.extrawdw.notisync.protocol.SshStorageSecurityLevel
import net.extrawdw.notisync.protocol.SshUserVerificationPolicy

internal data class PreparedSshKeyUnwrap(
    val cipher: Cipher,
    internal val wrappedDek: ByteArray,
    internal val nonce: ByteArray,
    internal val ciphertext: ByteArray,
    internal val dataAad: ByteArray,
) {
    internal var consumed = false
}

internal data class ProtectedSshKeyMaterial(
    val envelope: ByteArray,
    val securityLevel: SshStorageSecurityLevel,
)

internal class PreparedSshKeyProtection internal constructor(
    val cipher: Cipher,
    internal val dek: ByteArray,
    internal val wrapNonce: ByteArray,
    internal val dataNonce: ByteArray,
    internal val dataCiphertext: ByteArray,
    internal val dataAad: ByteArray,
    internal val securityLevel: SshStorageSecurityLevel,
) {
    internal var consumed = false

    internal fun destroy() {
        if (consumed) return
        consumed = true
        dek.fill(0)
        wrapNonce.fill(0)
        dataNonce.fill(0)
        dataCiphertext.fill(0)
        dataAad.fill(0)
    }
}

internal sealed interface SshKeyProtectionResult {
    data class Complete(val material: ProtectedSshKeyMaterial) : SshKeyProtectionResult
    data class AuthenticationRequired(val prepared: PreparedSshKeyProtection) : SshKeyProtectionResult
}

internal fun shouldRequestStrongBoxAesWrapping(
    preferStrongBox: Boolean,
    strongBoxAvailable: Boolean,
    userVerificationPolicy: SshUserVerificationPolicy,
    exactOperationWorks: () -> Boolean,
): Boolean = preferStrongBox &&
    userVerificationPolicy == SshUserVerificationPolicy.NONE &&
    strongBoxAvailable &&
    exactOperationWorks()

internal data class SshAesWrappedKeyEnvelope(
    val wrapNonce: ByteArray,
    val wrappedDek: ByteArray,
    val dataNonce: ByteArray,
    val dataCiphertext: ByteArray,
)

/** Strict, bounded codec kept independent of Android Keystore so hostile database bytes are unit-testable. */
internal object SshAesWrappedKeyEnvelopeCodec {
    fun encode(
        wrapNonce: ByteArray,
        wrappedDek: ByteArray,
        dataNonce: ByteArray,
        dataCiphertext: ByteArray,
    ): ByteArray {
        require(wrapNonce.size == GCM_NONCE_BYTES && dataNonce.size == GCM_NONCE_BYTES)
        require(wrappedDek.isNotEmpty() && wrappedDek.size <= MAX_WRAPPED_DEK_BYTES)
        require(dataCiphertext.isNotEmpty() && dataCiphertext.size <= MAX_FIELD_BYTES)
        return ByteBuffer.allocate(
            4 + 1 + 4 + wrapNonce.size + 4 + wrappedDek.size + 4 + dataNonce.size + 4 + dataCiphertext.size,
        )
            .put(WRAP_MAGIC)
            .put(WRAP_VERSION)
            .putInt(wrapNonce.size).put(wrapNonce)
            .putInt(wrappedDek.size).put(wrappedDek)
            .putInt(dataNonce.size).put(dataNonce)
            .putInt(dataCiphertext.size).put(dataCiphertext)
            .array()
    }

    fun decode(encoded: ByteArray): SshAesWrappedKeyEnvelope {
        val buffer = ByteBuffer.wrap(encoded)
        require(buffer.remaining() >= 5 && ByteArray(4).also(buffer::get).contentEquals(WRAP_MAGIC)) {
            "invalid SSH wrapped-key envelope"
        }
        require(buffer.get() == WRAP_VERSION) { "unsupported SSH wrapped-key envelope" }
        fun field(maximum: Int): ByteArray {
            require(buffer.remaining() >= 4)
            val size = buffer.getInt()
            require(size in 1..maximum && size <= buffer.remaining())
            return ByteArray(size).also(buffer::get)
        }
        val wrapNonce = field(64)
        val wrappedDek = field(MAX_WRAPPED_DEK_BYTES)
        val dataNonce = field(64)
        val dataCiphertext = field(MAX_FIELD_BYTES)
        require(
            !buffer.hasRemaining() && wrapNonce.size == GCM_NONCE_BYTES && dataNonce.size == GCM_NONCE_BYTES,
        )
        return SshAesWrappedKeyEnvelope(wrapNonce, wrappedDek, dataNonce, dataCiphertext)
    }

    private const val GCM_NONCE_BYTES = 12
    private const val WRAP_VERSION: Byte = 1
    private const val MAX_WRAPPED_DEK_BYTES = 8 * 1024
    private const val MAX_FIELD_BYTES = 512 * 1024
    private val WRAP_MAGIC = byteArrayOf(0x4e, 0x53, 0x41, 0x57) // NSAW
}

/** Per-key/shared hardware AES-256-GCM wrapper around random AES-256-GCM data-encryption keys. */
internal class SshAesKeyWrapper(private val strongBoxAvailable: Boolean) {
    fun protect(
        alias: String,
        plaintext: ByteArray,
        aad: ByteArray,
        preferStrongBox: Boolean,
        userVerificationPolicy: SshUserVerificationPolicy,
    ): SshKeyProtectionResult {
        val key = loadOrCreate(alias, preferStrongBox, userVerificationPolicy)
        val securityLevel = inspect(key, userVerificationPolicy)
        val dek = ByteArray(DEK_BYTES).also(RANDOM::nextBytes)
        val dataAad = domain(DATA_AAD_DOMAIN, aad)
        var dataNonce: ByteArray? = null
        var dataCiphertext: ByteArray? = null
        try {
            val dataCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"))
                updateAAD(dataAad)
            }
            dataCiphertext = dataCipher.doFinal(plaintext)
            dataNonce = dataCipher.iv.copyOf()
            // KeyMint only authenticates the random DEK. The software data layer below already binds the full
            // SSH key identity through dataAad, so repeating that metadata as hardware GCM AAD is redundant and
            // exposes the envelope to device-specific StrongBox AAD bugs.
            val wrappingCipher = Cipher.getInstance(AES_TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key)
            }
            // Keep the authenticated Keystore operation untouched until BiometricPrompt returns it. Some
            // KeyMint providers reject even AAD input before the operation has received its auth token.
            val prepared = PreparedSshKeyProtection(
                wrappingCipher,
                dek,
                requireNotNull(wrappingCipher.iv).copyOf(),
                dataNonce,
                dataCiphertext,
                dataAad,
                securityLevel,
            )
            dataNonce = null
            dataCiphertext = null
            return if (userVerificationPolicy == SshUserVerificationPolicy.PER_USE) {
                SshKeyProtectionResult.AuthenticationRequired(prepared)
            } else {
                SshKeyProtectionResult.Complete(completeProtect(prepared, wrappingCipher))
            }
        } catch (failure: Exception) {
            dek.fill(0)
            dataNonce?.fill(0)
            dataCiphertext?.fill(0)
            dataAad.fill(0)
            throw failure
        }
    }

    fun completeProtect(
        prepared: PreparedSshKeyProtection,
        authenticatedCipher: Cipher = prepared.cipher,
    ): ProtectedSshKeyMaterial {
        require(authenticatedCipher === prepared.cipher) { "SSH key wrapping operation changed" }
        check(!prepared.consumed) { "SSH key wrapping operation was already consumed" }
        prepared.consumed = true
        return try {
            val wrappedDek = try {
                authenticatedCipher.doFinal(prepared.dek).also {
                    check(MessageDigest.isEqual(prepared.wrapNonce, requireNotNull(authenticatedCipher.iv))) {
                        "Android Keystore changed the SSH wrapping nonce while finalizing"
                    }
                }
            } catch (failure: Exception) {
                throw IllegalStateException("Android Keystore could not finish authenticated SSH key wrapping", failure)
            }
            try {
                ProtectedSshKeyMaterial(
                    SshAesWrappedKeyEnvelopeCodec.encode(
                        prepared.wrapNonce,
                        wrappedDek,
                        prepared.dataNonce,
                        prepared.dataCiphertext,
                    ),
                    prepared.securityLevel,
                )
            } finally {
                wrappedDek.fill(0)
            }
        } finally {
            prepared.dek.fill(0)
            prepared.wrapNonce.fill(0)
            prepared.dataNonce.fill(0)
            prepared.dataCiphertext.fill(0)
            prepared.dataAad.fill(0)
        }
    }

    fun cancelProtect(prepared: PreparedSshKeyProtection) = prepared.destroy()

    fun prepareUnwrap(alias: String, envelope: ByteArray, aad: ByteArray): PreparedSshKeyUnwrap {
        val parsed = SshAesWrappedKeyEnvelopeCodec.decode(envelope)
        val key = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            .getKey(alias, null) as? SecretKey
            ?: error("SSH key wrapping alias is unavailable")
        val dataAad = domain(DATA_AAD_DOMAIN, aad)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, parsed.wrapNonce))
        }
        return PreparedSshKeyUnwrap(
            cipher,
            parsed.wrappedDek,
            parsed.dataNonce,
            parsed.dataCiphertext,
            dataAad,
        )
    }

    fun completeUnwrap(prepared: PreparedSshKeyUnwrap, authenticatedCipher: Cipher = prepared.cipher): ByteArray {
        require(authenticatedCipher === prepared.cipher) { "SSH key unwrap operation changed" }
        check(!prepared.consumed) { "SSH key unwrap operation was already consumed" }
        prepared.consumed = true
        var dek: ByteArray? = null
        return try {
            dek = try {
                authenticatedCipher.doFinal(prepared.wrappedDek)
            } catch (failure: Exception) {
                throw IllegalStateException("Android Keystore could not finish authenticated SSH key unwrapping", failure)
            }
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(requireNotNull(dek), "AES"), GCMParameterSpec(128, prepared.nonce))
                updateAAD(prepared.dataAad)
                doFinal(prepared.ciphertext)
            }
        } finally {
            dek?.fill(0)
            prepared.wrappedDek.fill(0)
            prepared.nonce.fill(0)
            prepared.ciphertext.fill(0)
            prepared.dataAad.fill(0)
        }
    }

    fun delete(alias: String) {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias)
    }

    private fun loadOrCreate(
        alias: String,
        preferStrongBox: Boolean,
        userVerificationPolicy: SshUserVerificationPolicy,
    ): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(!store.containsAlias(alias)) {
            "SSH key store uses an incompatible wrapping key; reset it from Advanced diagnostics"
        }
        // A StrongBox feature declaration covers AES-256-GCM, but does not let us safely preflight the
        // per-operation biometric combination: a faithful probe would itself need two user-authenticated
        // operations (encrypt, then decrypt). At least one shipping KeyMint accepts both operations and their
        // auth tokens yet emits ciphertext that later fails with VERIFICATION_FAILED. Never persist private-key
        // material behind an unverified combination. PER_USE wrappers therefore use authenticated TEE; direct
        // StrongBox SSH signing keys and non-authenticated StrongBox wrappers remain eligible.
        val requestStrongBox = shouldRequestStrongBoxAesWrapping(
            preferStrongBox,
            strongBoxAvailable,
            userVerificationPolicy,
            ::strongBoxAesGcmWorks,
        )
        try {
            generate(alias, requestStrongBox, userVerificationPolicy)
            val generated = requireNotNull(store.getKey(alias, null) as? SecretKey)
            if (requestStrongBox) {
                check(inspect(generated, userVerificationPolicy) == SshStorageSecurityLevel.STRONGBOX) {
                    "Android Keystore did not honor the StrongBox SSH wrapping-key request"
                }
            }
        } catch (failure: Exception) {
            if (!requestStrongBox) throw failure
            store.deleteEntry(alias)
            generate(alias, false, userVerificationPolicy)
        }
        store.load(null)
        return store.getKey(alias, null) as? SecretKey
            ?: error("SSH key wrapping entry is unavailable")
    }

    private fun generate(
        alias: String,
        strongBox: Boolean,
        userVerificationPolicy: SshUserVerificationPolicy,
    ) {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .apply {
                if (strongBox) setIsStrongBoxBacked(true)
                if (userVerificationPolicy == SshUserVerificationPolicy.PER_USE) {
                    setUserAuthenticationRequired(true)
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
            }
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private fun inspect(
        key: SecretKey,
        userVerificationPolicy: SshUserVerificationPolicy,
    ): SshStorageSecurityLevel {
        check(key.encoded == null) { "SSH wrapping key is unexpectedly exportable" }
        val info = SecretKeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        check(info.purposes and KeyProperties.PURPOSE_DECRYPT != 0)
        when (userVerificationPolicy) {
            SshUserVerificationPolicy.NONE -> check(!info.isUserAuthenticationRequired)
            SshUserVerificationPolicy.PER_USE -> {
                check(info.isUserAuthenticationRequired)
                check(info.userAuthenticationValidityDurationSeconds == 0)
                check(info.userAuthenticationType and KeyProperties.AUTH_BIOMETRIC_STRONG != 0)
            }
        }
        return info.securityLevel.toSshSecurityLevel()
    }

    /**
     * StrongBox presence does not prove that its AES-256-GCM implementation can complete an operation. Probe a
     * disposable non-authenticated key once per process before binding durable wrapped material to it. This
     * deliberately has no app-side timeout: a healthy StrongBox operation can take well over ten seconds on
     * some hardware, and latency alone must never be treated as a capability failure.
     */
    private fun strongBoxAesGcmWorks(): Boolean {
        if (strongBoxAesGcmUsable == true) return true
        return synchronized(STRONGBOX_PROBE_LOCK) {
            if (strongBoxAesGcmUsable == true) true
            else probeStrongBoxAesGcm().also { if (it) strongBoxAesGcmUsable = true }
        }
    }

    private fun probeStrongBoxAesGcm(): Boolean {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val probePlaintext = ByteArray(DEK_BYTES).also(RANDOM::nextBytes)
        var ciphertext: ByteArray? = null
        var nonce: ByteArray? = null
        var recovered: ByteArray? = null
        return try {
            store.deleteEntry(STRONGBOX_PROBE_ALIAS)
            generate(
                STRONGBOX_PROBE_ALIAS,
                strongBox = true,
                userVerificationPolicy = SshUserVerificationPolicy.NONE,
            )
            store.load(null)
            val key = store.getKey(STRONGBOX_PROBE_ALIAS, null) as? SecretKey
                ?: error("StrongBox AES-GCM probe key is unavailable")
            check(inspect(key, SshUserVerificationPolicy.NONE) == SshStorageSecurityLevel.STRONGBOX)
            ciphertext = Cipher.getInstance(AES_TRANSFORMATION).run {
                init(Cipher.ENCRYPT_MODE, key)
                nonce = requireNotNull(iv).copyOf()
                doFinal(probePlaintext).also {
                    check(MessageDigest.isEqual(requireNotNull(nonce), requireNotNull(iv)))
                }
            }
            recovered = Cipher.getInstance(AES_TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, requireNotNull(nonce)))
                doFinal(requireNotNull(ciphertext))
            }
            MessageDigest.isEqual(probePlaintext, requireNotNull(recovered))
        } catch (_: Exception) {
            false
        } finally {
            probePlaintext.fill(0)
            ciphertext?.fill(0)
            nonce?.fill(0)
            recovered?.fill(0)
            runCatching { store.deleteEntry(STRONGBOX_PROBE_ALIAS) }
        }
    }

    private fun domain(label: String, aad: ByteArray): ByteArray =
        label.encodeToByteArray() + byteArrayOf(0) + aad

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val DEK_BYTES = 32
        private const val GCM_NONCE_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val STRONGBOX_PROBE_ALIAS = "notisync_ssh_aes_gcm_probe_v1"
        private const val DATA_AAD_DOMAIN = "notisync:ssh:key-data:v2"
        private val RANDOM = SecureRandom()
        private val STRONGBOX_PROBE_LOCK = Any()

        @Volatile
        private var strongBoxAesGcmUsable: Boolean? = null
    }
}

/** Cold shared export backup wrapper; private unwrap always requires a strong biometric. */
internal class SshExportKeyVault(strongBoxAvailable: Boolean) {
    private val wrapper = SshAesKeyWrapper(strongBoxAvailable)

    fun protect(
        privateKeyPkcs8: ByteArray,
        providerKeyId: String,
        algorithm: SshKeyAlgorithm,
        publicKeyHash: ByteArray,
    ): SshKeyProtectionResult = wrapper.protect(
        EXPORT_ALIAS,
        privateKeyPkcs8,
        exportAad(providerKeyId, algorithm, publicKeyHash),
        preferStrongBox = true,
        userVerificationPolicy = SshUserVerificationPolicy.PER_USE,
    )

    fun prepareUnwrap(
        envelope: ByteArray,
        providerKeyId: String,
        algorithm: SshKeyAlgorithm,
        publicKeyHash: ByteArray,
    ): PreparedSshKeyUnwrap = wrapper.prepareUnwrap(
        EXPORT_ALIAS,
        envelope,
        exportAad(providerKeyId, algorithm, publicKeyHash),
    )

    fun completeUnwrap(prepared: PreparedSshKeyUnwrap, authenticatedCipher: Cipher): ByteArray =
        wrapper.completeUnwrap(prepared, authenticatedCipher)

    fun completeProtect(prepared: PreparedSshKeyProtection, authenticatedCipher: Cipher): ProtectedSshKeyMaterial =
        wrapper.completeProtect(prepared, authenticatedCipher)

    fun cancelProtect(prepared: PreparedSshKeyProtection) = wrapper.cancelProtect(prepared)

    private fun exportAad(providerKeyId: String, algorithm: SshKeyAlgorithm, publicKeyHash: ByteArray): ByteArray =
        "notisync:ssh-export:v1:$providerKeyId:${algorithm.name}:${publicKeyHash.toHex()}".encodeToByteArray()

    private companion object {
        const val EXPORT_ALIAS = "notisync_ssh_export_wrapping_v1"
    }
}

internal data class SshKeyMaterialBundle(
    val exportEnvelope: ByteArray? = null,
    val operationalEnvelope: ByteArray? = null,
) {
    fun encode(): ByteArray {
        require((exportEnvelope != null) xor (operationalEnvelope != null)) {
            "SSH key material must contain exactly one private-key representation"
        }
        val export = exportEnvelope ?: EMPTY
        val operational = operationalEnvelope ?: EMPTY
        return ByteBuffer.allocate(4 + 1 + 4 + export.size + 4 + operational.size)
            .put(BUNDLE_MAGIC)
            .put(BUNDLE_VERSION)
            .putInt(export.size).put(export)
            .putInt(operational.size).put(operational)
            .array()
    }

    companion object {
        private const val BUNDLE_VERSION: Byte = 1
        private val BUNDLE_MAGIC = byteArrayOf(0x4e, 0x53, 0x4b, 0x42) // NSKB
        private val EMPTY = ByteArray(0)

        fun decode(encoded: ByteArray): SshKeyMaterialBundle {
            val buffer = ByteBuffer.wrap(encoded)
            require(buffer.remaining() >= 5 && ByteArray(4).also(buffer::get).contentEquals(BUNDLE_MAGIC)) {
                "invalid SSH key-material bundle"
            }
            require(buffer.get() == BUNDLE_VERSION) { "unsupported SSH key-material bundle" }
            fun field(): ByteArray? {
                require(buffer.remaining() >= 4)
                val size = buffer.getInt()
                require(size in 0..MAX_FIELD_BYTES && size <= buffer.remaining())
                return ByteArray(size).also(buffer::get).takeIf(ByteArray::isNotEmpty)
            }
            val export = field()
            val operational = field()
            require(!buffer.hasRemaining() && ((export != null) xor (operational != null)))
            return SshKeyMaterialBundle(export, operational)
        }

        private const val MAX_FIELD_BYTES = 1024 * 1024
    }
}

private fun Int.toSshSecurityLevel(): SshStorageSecurityLevel = when (this) {
    KeyProperties.SECURITY_LEVEL_STRONGBOX -> SshStorageSecurityLevel.STRONGBOX
    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> SshStorageSecurityLevel.TRUSTED_ENVIRONMENT
    KeyProperties.SECURITY_LEVEL_SOFTWARE -> SshStorageSecurityLevel.SOFTWARE
    else -> SshStorageSecurityLevel.UNKNOWN
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
