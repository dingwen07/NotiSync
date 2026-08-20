package net.extrawdw.apps.notisync.sshagent

import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PKCS8Generator
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter

/** Encodes interoperable PKCS#8 PEM, optionally protected by PBES2/AES-256. */
object SshPrivateKeyExportCodec {
    fun encode(pkcs8PrivateKey: ByteArray, password: CharArray?): ByteArray {
        require(pkcs8PrivateKey.isNotEmpty()) { "The SSH private key is empty" }
        require(password == null || password.isNotEmpty()) { "An export password cannot be empty" }
        val generator = if (password == null) {
            PemObject("PRIVATE KEY", pkcs8PrivateKey)
        } else {
            val encryptor = JceOpenSSLPKCS8EncryptorBuilder(PKCS8Generator.AES_256_CBC)
                .setProvider(BouncyCastleProvider())
                .setRandom(SecureRandom())
                .setPRF(PKCS8Generator.PRF_HMACSHA256)
                .setIterationCount(PBKDF2_ITERATIONS)
                .setPassword(password)
                .build()
            PKCS8Generator(PrivateKeyInfo.getInstance(pkcs8PrivateKey), encryptor)
        }
        val output = ByteArrayOutputStream()
        PemWriter(OutputStreamWriter(output, StandardCharsets.US_ASCII)).use { writer ->
            writer.writeObject(generator)
        }
        return output.toByteArray()
    }

    private const val PBKDF2_ITERATIONS = 600_000
}
