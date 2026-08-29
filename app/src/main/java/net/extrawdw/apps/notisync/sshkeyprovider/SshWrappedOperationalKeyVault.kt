package net.extrawdw.apps.notisync.sshkeyprovider

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.extrawdw.notisync.protocol.SshKeyAlgorithm
import net.extrawdw.notisync.protocol.SshStorageSecurityLevel
import net.extrawdw.notisync.protocol.SshUserVerificationPolicy

internal class SshWrappedOperationalOperationException(
    val strongBox: Boolean,
    operation: String,
    cause: Exception,
) : Exception(
    "Android Keystore could not $operation the wrapped SSH operational key: " +
        "${cause.javaClass.simpleName}${cause.message?.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}",
    cause,
)

internal class PreparedWrappedOperationalProtection internal constructor(
    val cipher: Cipher,
    internal val plaintext: ByteArray,
    internal val aad: ByteArray,
    internal val nonce: ByteArray,
    internal val securityLevel: SshStorageSecurityLevel,
) : AutoCloseable {
    internal var consumed = false

    override fun close() {
        if (consumed) return
        consumed = true
        aad.fill(0)
        nonce.fill(0)
    }
}

internal class PreparedWrappedOperationalUnwrap internal constructor(
    val cipher: Cipher,
    internal val ciphertext: ByteArray,
    internal val aad: ByteArray,
    internal val securityLevel: SshStorageSecurityLevel,
) : AutoCloseable {
    internal var consumed = false

    override fun close() {
        if (consumed) return
        consumed = true
        ciphertext.fill(0)
        aad.fill(0)
    }
}

/** AES-backed operational provider used only when Android Keystore cannot import an Ed25519 private key. */
internal class SshWrappedOperationalKeyVault(private val strongBoxAvailable: Boolean) {
    fun shouldAttemptStrongBox(): Boolean =
        SshKeyStoragePolicy.shouldAttemptWrappedOperationalStrongBox(strongBoxAvailable)

    fun prepareProtect(
        alias: String,
        providerKeyId: String,
        privateKeyPkcs8: SensitiveBytes,
        algorithm: SshKeyAlgorithm,
        publicKeyHash: ByteArray,
        strongBox: Boolean,
        userVerificationPolicy: SshUserVerificationPolicy,
    ): PreparedWrappedOperationalProtection {
        check(!strongBox || strongBoxAvailable) { "StrongBox is not declared by this device" }
        val store = androidKeyStore()
        check(!store.containsAlias(alias)) { "SSH operational candidate alias already exists" }
        try {
            generate(alias, strongBox, userVerificationPolicy)
            store.load(null)
            val key = store.getKey(alias, null) as? SecretKey
                ?: error("Wrapped SSH operational candidate is unavailable")
            val securityLevel = inspect(key, strongBox, userVerificationPolicy)
            val cipher = SshKeystoreJca.cipher(AES_TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key)
            }
            return PreparedWrappedOperationalProtection(
                cipher = cipher,
                plaintext = privateKeyPkcs8.bytes,
                aad = SshKeyMaterialAad.wrappedOperational(providerKeyId, algorithm, publicKeyHash),
                nonce = requireNotNull(cipher.iv).copyOf(),
                securityLevel = securityLevel,
            )
        } catch (failure: Exception) {
            runCatching { store.deleteEntry(alias) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw SshOperationalCandidateException(strongBox, failure)
        }
    }

    fun completeProtect(
        prepared: PreparedWrappedOperationalProtection,
        authenticatedCipher: Cipher = prepared.cipher,
    ): ProtectedSshKeyMaterial {
        require(authenticatedCipher === prepared.cipher) { "SSH operational wrapping operation changed" }
        check(!prepared.consumed) { "SSH operational wrapping operation was already consumed" }
        prepared.consumed = true
        return try {
            authenticatedCipher.updateAAD(prepared.aad)
            val ciphertext = authenticatedCipher.doFinal(prepared.plaintext)
            check(MessageDigest.isEqual(prepared.nonce, requireNotNull(authenticatedCipher.iv))) {
                "Android Keystore changed the wrapped SSH operational nonce while finalizing"
            }
            ProtectedSshKeyMaterial(ciphertext, prepared.nonce.copyOf(), prepared.securityLevel)
        } catch (failure: Exception) {
            throw SshWrappedOperationalOperationException(
                prepared.securityLevel == SshStorageSecurityLevel.STRONGBOX,
                "encrypt",
                failure,
            )
        } finally {
            prepared.aad.fill(0)
            prepared.nonce.fill(0)
        }
    }

    fun prepareUnwrap(
        alias: String,
        providerKeyId: String,
        ciphertext: ByteArray,
        nonce: ByteArray,
        algorithm: SshKeyAlgorithm,
        publicKeyHash: ByteArray,
        securityLevel: SshStorageSecurityLevel,
        userVerificationPolicy: SshUserVerificationPolicy,
    ): PreparedWrappedOperationalUnwrap {
        require(nonce.size == GCM_NONCE_BYTES) { "invalid wrapped SSH operational nonce" }
        require(ciphertext.isNotEmpty() && ciphertext.size <= MAX_CIPHERTEXT_BYTES) {
            "invalid wrapped SSH operational ciphertext"
        }
        val strongBox = securityLevel == SshStorageSecurityLevel.STRONGBOX
        val cipher = try {
            val key = androidKeyStore().getKey(alias, null) as? SecretKey
                ?: error("Wrapped SSH operational key is unavailable")
            check(inspect(key, strongBox, userVerificationPolicy) == securityLevel) {
                "Wrapped SSH operational security level changed"
            }
            SshKeystoreJca.cipher(AES_TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            }
        } catch (failure: Exception) {
            throw SshOperationalCandidateException(strongBox, failure)
        }
        return PreparedWrappedOperationalUnwrap(
            cipher,
            ciphertext.copyOf(),
            SshKeyMaterialAad.wrappedOperational(providerKeyId, algorithm, publicKeyHash),
            securityLevel,
        )
    }

    fun completeUnwrap(
        prepared: PreparedWrappedOperationalUnwrap,
        authenticatedCipher: Cipher = prepared.cipher,
    ): SensitiveBytes {
        require(authenticatedCipher === prepared.cipher) { "SSH operational unwrapping operation changed" }
        check(!prepared.consumed) { "SSH operational unwrapping operation was already consumed" }
        prepared.consumed = true
        return try {
            authenticatedCipher.updateAAD(prepared.aad)
            SensitiveBytes.takeOwnership(authenticatedCipher.doFinal(prepared.ciphertext))
        } catch (failure: Exception) {
            throw SshWrappedOperationalOperationException(
                prepared.securityLevel == SshStorageSecurityLevel.STRONGBOX,
                "decrypt",
                failure,
            )
        } finally {
            prepared.ciphertext.fill(0)
            prepared.aad.fill(0)
        }
    }

    fun delete(alias: String) {
        androidKeyStore().deleteEntry(alias)
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
                if (userVerificationPolicy == SshUserVerificationPolicy.PER_USE) {
                    setUserAuthenticationRequired(true)
                    setUserAuthenticationParameters(0, SshAuthenticationPolicy.SIGNING_KEY_AUTHENTICATORS)
                }
                if (strongBox) setIsStrongBoxBacked(true)
            }
            .build()
        SshKeystoreJca.keyGenerator(KeyProperties.KEY_ALGORITHM_AES).run {
            init(spec)
            generateKey()
        }
    }

    private fun inspect(
        key: SecretKey,
        expectedStrongBox: Boolean,
        userVerificationPolicy: SshUserVerificationPolicy,
    ): SshStorageSecurityLevel {
        check(key.encoded == null) { "Wrapped SSH operational AES key is unexpectedly exportable" }
        val info = SshKeystoreJca.secretKeyFactory(KeyProperties.KEY_ALGORITHM_AES)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        check(info.keySize == 256)
        check(info.purposes and KeyProperties.PURPOSE_ENCRYPT != 0)
        check(info.purposes and KeyProperties.PURPOSE_DECRYPT != 0)
        check(info.blockModes.toSet() == setOf(KeyProperties.BLOCK_MODE_GCM))
        check(info.encryptionPaddings.toSet() == setOf(KeyProperties.ENCRYPTION_PADDING_NONE))
        when (userVerificationPolicy) {
            SshUserVerificationPolicy.NONE -> check(!info.isUserAuthenticationRequired)
            SshUserVerificationPolicy.PER_USE -> {
                check(info.isUserAuthenticationRequired)
                check(info.userAuthenticationValidityDurationSeconds == 0)
                check(info.userAuthenticationType and KeyProperties.AUTH_BIOMETRIC_STRONG != 0)
                check(info.isUserAuthenticationRequirementEnforcedBySecureHardware) {
                    "Android Keystore did not enforce wrapped SSH operational authentication in secure hardware"
                }
            }
        }
        val level = when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> SshStorageSecurityLevel.STRONGBOX
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> SshStorageSecurityLevel.TRUSTED_ENVIRONMENT
            else -> throw SshHardwareBackedKeystoreUnavailableException("Wrapped SSH operational key")
        }
        val expected = if (expectedStrongBox) {
            SshStorageSecurityLevel.STRONGBOX
        } else {
            SshStorageSecurityLevel.TRUSTED_ENVIRONMENT
        }
        check(level == expected) { "Android Keystore did not honor the wrapped SSH operational backend" }
        return level
    }

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance(SshKeystoreJca.ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val MAX_CIPHERTEXT_BYTES = 512 * 1024
    }
}
