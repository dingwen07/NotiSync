package net.extrawdw.apps.notisync.sshkeyprovider

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Provider
import java.security.Security
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKeyFactory

/** Central JCA routing for opaque Android Keystore keys. */
internal object SshKeystoreJca {
    fun signature(algorithm: String): Signature = operationProvider("Signature", algorithm)
        ?.let { Signature.getInstance(algorithm, it) }
        ?: Signature.getInstance(algorithm)

    fun cipher(transformation: String): Cipher = operationProvider("Cipher", transformation)
        ?.let { Cipher.getInstance(transformation, it) }
        ?: Cipher.getInstance(transformation)

    fun keyPairGenerator(algorithm: String): KeyPairGenerator = keystoreProvider("KeyPairGenerator", algorithm)
        ?.let { KeyPairGenerator.getInstance(algorithm, it) }
        ?: throw java.security.NoSuchAlgorithmException(
            "$algorithm KeyPairGenerator is not offered by an Android Keystore provider",
        )

    fun keyGenerator(algorithm: String): KeyGenerator = keystoreProvider("KeyGenerator", algorithm)
        ?.let { KeyGenerator.getInstance(algorithm, it) }
        ?: throw java.security.NoSuchAlgorithmException(
            "$algorithm KeyGenerator is not offered by an Android Keystore provider",
        )

    fun keyFactory(algorithm: String): KeyFactory = keystoreProvider("KeyFactory", algorithm)
        ?.let { KeyFactory.getInstance(algorithm, it) }
        ?: throw java.security.NoSuchAlgorithmException(
            "$algorithm KeyFactory is not offered by an Android Keystore provider",
        )

    fun secretKeyFactory(algorithm: String): SecretKeyFactory = keystoreProvider("SecretKeyFactory", algorithm)
        ?.let { SecretKeyFactory.getInstance(algorithm, it) }
        ?: throw java.security.NoSuchAlgorithmException(
            "$algorithm SecretKeyFactory is not offered by an Android Keystore provider",
        )

    private fun operationProvider(type: String, algorithm: String): Provider? =
        listOf("AndroidKeyStoreBCWorkaround", ANDROID_KEYSTORE)
            .asSequence()
            .mapNotNull(Security::getProvider)
            .firstOrNull { it.getService(type, algorithm) != null }

    private fun keystoreProvider(type: String, algorithm: String): Provider? =
        listOf(ANDROID_KEYSTORE, "AndroidKeyStoreBCWorkaround")
            .asSequence()
            .mapNotNull(Security::getProvider)
            .firstOrNull { it.getService(type, algorithm) != null }

    const val ANDROID_KEYSTORE = "AndroidKeyStore"
}
