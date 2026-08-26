package net.extrawdw.apps.notisync.sshagent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.extrawdw.notisync.protocol.SshExportCopyBackendPolicy
import net.extrawdw.notisync.protocol.SshKeyAlgorithm
import net.extrawdw.notisync.protocol.SshStorageSecurityLevel

internal data class ProtectedSshKeyMaterial(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val securityLevel: SshStorageSecurityLevel,
)

internal class SshExportCandidateException(
    val strongBox: Boolean,
    cause: Exception,
) : Exception(
    "Android Keystore could not create the requested SSH export-copy candidate: " + cause.exportFailureSummary(),
    cause,
)

internal class SshExportOperationException(
    val strongBox: Boolean,
    operation: String,
    cause: Exception,
) : Exception("Android Keystore could not $operation the SSH export copy: ${cause.exportFailureSummary()}", cause)

internal class PreparedSshKeyProtection internal constructor(
    val cipher: Cipher,
    /** Borrowed from the provisioning owner so authentication does not keep a second PKCS#8 copy alive. */
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

internal class PreparedSshKeyUnwrap internal constructor(
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

/**
 * Stores only the optional export copy. Operational signing keys never pass through this class.
 *
 * Each call creates or opens one exact StrongBox or TEE candidate. There is deliberately no capability probe,
 * cached master key, or internal fallback. The provisioning state machine must validate the complete persisted
 * encrypt/decrypt/sign round trip before committing a candidate, and may then delete a failed StrongBox candidate
 * and create a fresh TEE candidate.
 */
internal class SshExportKeyVault(private val strongBoxAvailable: Boolean) {
    fun shouldAttemptStrongBox(policy: SshExportCopyBackendPolicy): Boolean =
        SshKeyStoragePolicy.shouldAttemptExportStrongBox(strongBoxAvailable, policy)

    fun alias(providerKeyId: String, strongBox: Boolean): String =
        EXPORT_ALIAS_PREFIX + providerKeyId + if (strongBox) STRONGBOX_SUFFIX else TEE_SUFFIX

    fun prepareProtect(
        providerKeyId: String,
        privateKeyPkcs8: SensitiveBytes,
        algorithm: SshKeyAlgorithm,
        publicKeyHash: ByteArray,
        strongBox: Boolean,
    ): PreparedSshKeyProtection {
        check(!strongBox || strongBoxAvailable) { "StrongBox is not declared by this device" }
        val alias = alias(providerKeyId, strongBox)
        val store = androidKeyStore()
        check(!store.containsAlias(alias)) { "SSH export-copy candidate alias already exists" }
        try {
            generate(alias, strongBox)
            store.load(null)
            val key = store.getKey(alias, null) as? SecretKey
                ?: error("SSH export-copy candidate is unavailable")
            val securityLevel = inspect(key, strongBox)
            val cipher = SshKeystoreJca.cipher(AES_TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key)
            }
            return PreparedSshKeyProtection(
                cipher = cipher,
                plaintext = privateKeyPkcs8.bytes,
                aad = SshKeyMaterialAad.exportCopy(providerKeyId, algorithm, publicKeyHash),
                nonce = requireNotNull(cipher.iv).copyOf(),
                securityLevel = securityLevel,
            )
        } catch (failure: Exception) {
            runCatching { store.deleteEntry(alias) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw SshExportCandidateException(strongBox, failure)
        }
    }

    fun completeProtect(
        prepared: PreparedSshKeyProtection,
        authenticatedCipher: Cipher = prepared.cipher,
    ): ProtectedSshKeyMaterial {
        require(authenticatedCipher === prepared.cipher) { "SSH export-copy encryption operation changed" }
        check(!prepared.consumed) { "SSH export-copy encryption operation was already consumed" }
        prepared.consumed = true
        return try {
            authenticatedCipher.updateAAD(prepared.aad)
            val ciphertext = authenticatedCipher.doFinal(prepared.plaintext)
            check(MessageDigest.isEqual(prepared.nonce, requireNotNull(authenticatedCipher.iv))) {
                "Android Keystore changed the SSH export-copy nonce while finalizing"
            }
            ProtectedSshKeyMaterial(
                ciphertext = ciphertext,
                nonce = prepared.nonce.copyOf(),
                securityLevel = prepared.securityLevel,
            )
        } catch (failure: Exception) {
            throw SshExportOperationException(
                strongBox = prepared.securityLevel == SshStorageSecurityLevel.STRONGBOX,
                operation = "encrypt",
                cause = failure,
            )
        } finally {
            prepared.aad.fill(0)
            prepared.nonce.fill(0)
        }
    }

    fun prepareUnwrap(
        providerKeyId: String,
        ciphertext: ByteArray,
        nonce: ByteArray,
        algorithm: SshKeyAlgorithm,
        publicKeyHash: ByteArray,
        securityLevel: SshStorageSecurityLevel,
    ): PreparedSshKeyUnwrap {
        require(nonce.size == GCM_NONCE_BYTES) { "invalid SSH export-copy nonce" }
        require(ciphertext.isNotEmpty() && ciphertext.size <= MAX_CIPHERTEXT_BYTES) {
            "invalid SSH export-copy ciphertext"
        }
        val strongBox = when (securityLevel) {
            SshStorageSecurityLevel.STRONGBOX -> true
            SshStorageSecurityLevel.TRUSTED_ENVIRONMENT -> false
            SshStorageSecurityLevel.CREDENTIAL_PROVIDER,
            SshStorageSecurityLevel.KEYCHAIN,
            -> error("Non-Android SSH keys have no Android export copy")
        }
        val cipher = try {
            val key = androidKeyStore().getKey(alias(providerKeyId, strongBox), null) as? SecretKey
                ?: error("SSH export-copy key is unavailable")
            check(inspect(key, strongBox) == securityLevel) { "SSH export-copy security level changed" }
            SshKeystoreJca.cipher(AES_TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            }
        } catch (failure: Exception) {
            throw SshExportCandidateException(strongBox, failure)
        }
        return PreparedSshKeyUnwrap(
            cipher = cipher,
            ciphertext = ciphertext.copyOf(),
            aad = SshKeyMaterialAad.exportCopy(providerKeyId, algorithm, publicKeyHash),
            securityLevel = securityLevel,
        )
    }

    fun completeUnwrap(
        prepared: PreparedSshKeyUnwrap,
        authenticatedCipher: Cipher = prepared.cipher,
    ): SensitiveBytes {
        require(authenticatedCipher === prepared.cipher) { "SSH export-copy decryption operation changed" }
        check(!prepared.consumed) { "SSH export-copy decryption operation was already consumed" }
        prepared.consumed = true
        return try {
            authenticatedCipher.updateAAD(prepared.aad)
            SensitiveBytes.takeOwnership(authenticatedCipher.doFinal(prepared.ciphertext))
        } catch (failure: Exception) {
            throw SshExportOperationException(
                strongBox = prepared.securityLevel == SshStorageSecurityLevel.STRONGBOX,
                operation = "decrypt",
                cause = failure,
            )
        } finally {
            prepared.ciphertext.fill(0)
            prepared.aad.fill(0)
        }
    }

    fun deleteCandidate(providerKeyId: String, strongBox: Boolean) {
        androidKeyStore().deleteEntry(alias(providerKeyId, strongBox))
    }

    fun deleteAll(providerKeyId: String) {
        val store = androidKeyStore()
        store.deleteEntry(alias(providerKeyId, true))
        store.deleteEntry(alias(providerKeyId, false))
    }

    private fun generate(alias: String, strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(
                0,
                SshAuthenticationPolicy.EXPORT_KEY_AUTHENTICATORS,
            )
            .setInvalidatedByBiometricEnrollment(false)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        SshKeystoreJca.keyGenerator(KeyProperties.KEY_ALGORITHM_AES).run {
            init(spec)
            generateKey()
        }
    }

    private fun inspect(key: SecretKey, expectedStrongBox: Boolean): SshStorageSecurityLevel {
        check(key.encoded == null) { "SSH export-copy key is unexpectedly exportable" }
        val info = SshKeystoreJca.secretKeyFactory(KeyProperties.KEY_ALGORITHM_AES)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        check(info.keySize == 256) { "Android Keystore created an unexpected SSH export-copy key size" }
        check(info.purposes and KeyProperties.PURPOSE_ENCRYPT != 0)
        check(info.purposes and KeyProperties.PURPOSE_DECRYPT != 0)
        check(info.blockModes.toSet() == setOf(KeyProperties.BLOCK_MODE_GCM))
        check(info.encryptionPaddings.toSet() == setOf(KeyProperties.ENCRYPTION_PADDING_NONE))
        check(info.isUserAuthenticationRequired)
        check(info.userAuthenticationValidityDurationSeconds == 0)
        check(info.userAuthenticationType and KeyProperties.AUTH_BIOMETRIC_STRONG != 0)
        check(info.userAuthenticationType and KeyProperties.AUTH_DEVICE_CREDENTIAL != 0)
        check(info.isUserAuthenticationRequirementEnforcedBySecureHardware) {
            "Android Keystore did not enforce SSH export authentication in secure hardware"
        }
        val level = info.securityLevel.toSshSecurityLevel()
        val expected = if (expectedStrongBox) {
            SshStorageSecurityLevel.STRONGBOX
        } else {
            SshStorageSecurityLevel.TRUSTED_ENVIRONMENT
        }
        check(level == expected) { "Android Keystore did not honor the requested SSH export-copy backend" }
        return level
    }

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance(SshKeystoreJca.ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val EXPORT_ALIAS_PREFIX = "notisync_ssh_export_copy_"
        const val STRONGBOX_SUFFIX = "_sb"
        const val TEE_SUFFIX = "_tee"
        const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val MAX_CIPHERTEXT_BYTES = 512 * 1024
    }
}

/** Binary purpose separation for encrypted SSH private-key records. */
internal object SshKeyMaterialAad {
    fun wrappedOperational(providerKeyId: String, algorithm: SshKeyAlgorithm, publicKeyHash: ByteArray): ByteArray =
        encode(WRAPPED_OPERATIONAL, providerKeyId, algorithm, publicKeyHash)

    fun exportCopy(providerKeyId: String, algorithm: SshKeyAlgorithm, publicKeyHash: ByteArray): ByteArray =
        encode(EXPORT_COPY, providerKeyId, algorithm, publicKeyHash)

    private fun encode(
        purpose: Byte,
        providerKeyId: String,
        algorithm: SshKeyAlgorithm,
        publicKeyHash: ByteArray,
    ): ByteArray {
        val keyId = providerKeyId.encodeToByteArray()
        val algorithmName = algorithm.name.encodeToByteArray()
        require(keyId.isNotEmpty() && keyId.size <= 512)
        require(algorithmName.isNotEmpty() && algorithmName.size <= 64)
        require(publicKeyHash.size == 32)
        return ByteBuffer.allocate(1 + 4 + keyId.size + 4 + algorithmName.size + 4 + publicKeyHash.size)
            .put(purpose)
            .putInt(keyId.size).put(keyId)
            .putInt(algorithmName.size).put(algorithmName)
            .putInt(publicKeyHash.size).put(publicKeyHash)
            .array()
    }

    private const val WRAPPED_OPERATIONAL: Byte = 1
    private const val EXPORT_COPY: Byte = 2
}

private fun Int.toSshSecurityLevel(): SshStorageSecurityLevel = when (this) {
    KeyProperties.SECURITY_LEVEL_STRONGBOX -> SshStorageSecurityLevel.STRONGBOX
    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> SshStorageSecurityLevel.TRUSTED_ENVIRONMENT
    else -> throw SshHardwareBackedKeystoreUnavailableException("SSH export-copy key")
}

private fun Exception.exportFailureSummary(): String =
    "${javaClass.simpleName}${message?.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
