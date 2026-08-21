package net.extrawdw.apps.notisync.sshagent

import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import net.extrawdw.notisync.protocol.SshAgentLimits
import net.extrawdw.notisync.protocol.SshKeyAlgorithm
import net.extrawdw.notisync.ssh.core.SshKeyType
import net.extrawdw.notisync.ssh.core.SshPublicKeyCodec
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.putty.PuttyKeyUtils
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import org.bouncycastle.util.io.pem.PemReader

data class ParsedSshPrivateKeyFile(
    val algorithm: SshKeyAlgorithm,
    val publicKeyBlob: ByteArray,
    val pkcs8PrivateKey: ByteArray,
)

/** Bounded OpenSSH, PPK v2-v3, and PKCS#8 parser with a signing consistency check. */
object SshPrivateKeyFileParser {
    fun parse(fileBytes: ByteArray, passphrase: CharArray?): ParsedSshPrivateKeyFile {
        require(fileBytes.isNotEmpty() && fileBytes.size <= SshAgentLimits.MAX_IMPORT_BYTES) {
            "SSH private-key file is outside the allowed size limit"
        }
        val encrypted = detectEncryption(fileBytes)
        require(!encrypted || (passphrase != null && passphrase.isNotEmpty())) {
            "A passphrase is required for this encrypted SSH private key"
        }
        val pairs = try {
            when {
                isPkcs8Pem(fileBytes) -> listOf(parsePkcs8(fileBytes, passphrase))
                isOpenSshPem(fileBytes) -> listOf(parseOpenSsh(fileBytes, passphrase.takeIf { encrypted }))
                else -> {
                    val password = passphrase?.concatToString().orEmpty()
                    ByteArrayInputStream(fileBytes).use { input ->
                        PuttyKeyUtils.DEFAULT_INSTANCE.loadKeyPairs(
                            null,
                            NamedResource.ofName("selected-private-key"),
                            FilePasswordProvider.of(password),
                            input,
                        ).toList()
                    }
                }
            }
        } catch (failure: Exception) {
            throw IllegalArgumentException("The SSH private-key file or passphrase is invalid", failure)
        }
        require(pairs.size == 1) { "SSH private-key files must contain exactly one key" }
        return validate(pairs.single())
    }

    fun isEncrypted(fileBytes: ByteArray): Boolean = detectEncryption(fileBytes)

    /** Validates unencrypted keys immediately; encrypted keys require a passphrase before a preview is available. */
    fun inspect(fileBytes: ByteArray): InspectedSshPrivateKeyFile {
        val encrypted = detectEncryption(fileBytes)
        val preview = if (encrypted) null else preview(fileBytes, null)
        return InspectedSshPrivateKeyFile(
            encrypted = encrypted,
            preview = preview,
            comment = preview?.comment ?: extractPrivateKeyComment(fileBytes),
        )
    }

    fun preview(fileBytes: ByteArray, passphrase: CharArray?): SshKeyPreview {
        val parsed = parse(fileBytes, passphrase)
        return try {
            SshImportPreviewParser.preview(parsed.algorithm, parsed.publicKeyBlob).copy(
                comment = extractPrivateKeyComment(fileBytes),
            )
        } finally {
            parsed.pkcs8PrivateKey.fill(0)
        }
    }

    /** Reads import display metadata only; cryptographic parsing and validation remain authoritative. */
    private fun extractPrivateKeyComment(fileBytes: ByteArray): String? = when {
        isOpenSshPem(fileBytes) -> extractUnencryptedOpenSshComment(fileBytes)
        fileBytes.toString(StandardCharsets.ISO_8859_1)
            .lineSequence()
            .firstOrNull()
            ?.startsWith("PuTTY-User-Key-File-") == true -> fileBytes.toString(StandardCharsets.UTF_8)
            .lineSequence()
            .firstOrNull { it.startsWith("Comment:") }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf(::isUsableImportComment)
        else -> null
    }

    private fun extractUnencryptedOpenSshComment(fileBytes: ByteArray): String? {
        val text = fileBytes.toString(StandardCharsets.ISO_8859_1)
        val encoded = text.substringAfter(OPENSSH_BEGIN).substringBefore(OPENSSH_END)
            .filterNot(Char::isWhitespace)
        val decoded = Base64.getDecoder().decode(encoded)
        return try {
            val outer = SshMetadataReader(decoded)
            outer.requireBytes(OPENSSH_MAGIC)
            if (outer.readString(MAX_CIPHER_NAME_BYTES) != "none") return null
            outer.skipString()
            outer.skipString()
            val keyCount = outer.readU32()
            require(keyCount == 1) { "OpenSSH private keys must contain exactly one key" }
            repeat(keyCount) { outer.skipString() }
            val privateBlock = outer.readNestedString()
            require(privateBlock.readU32() == privateBlock.readU32()) {
                "OpenSSH private-key check values do not match"
            }
            when (privateBlock.readString(MAX_KEY_TYPE_BYTES)) {
                "ssh-ed25519" -> repeat(2) { privateBlock.skipString() }
                "ecdsa-sha2-nistp256" -> repeat(3) { privateBlock.skipString() }
                "ssh-rsa" -> repeat(6) { privateBlock.skipString() }
                else -> return null
            }
            privateBlock.readString(SshAgentLimits.MAX_DISPLAY_NAME_UTF8_BYTES)
                .trim()
                .takeIf(::isUsableImportComment)
        } finally {
            decoded.fill(0)
        }
    }

    private fun isUsableImportComment(comment: String): Boolean =
        comment.isNotEmpty() && comment.encodeToByteArray().size <= SshAgentLimits.MAX_DISPLAY_NAME_UTF8_BYTES

    private fun detectEncryption(fileBytes: ByteArray): Boolean {
        require(fileBytes.isNotEmpty() && fileBytes.size <= SshAgentLimits.MAX_IMPORT_BYTES) {
            "SSH private-key file is outside the allowed size limit"
        }
        val text = fileBytes.toString(StandardCharsets.ISO_8859_1)
        if (text.lineSequence().firstOrNull()?.startsWith("PuTTY-User-Key-File-") == true) {
            val encryption = text.lineSequence()
                .firstOrNull { it.startsWith("Encryption:") }
                ?.substringAfter(':')
                ?.trim()
                ?: throw IllegalArgumentException("PuTTY private-key file has no encryption header")
            return encryption != "none"
        }
        if (OPENSSH_BEGIN in text) {
            val encoded = text.substringAfter(OPENSSH_BEGIN).substringBefore(OPENSSH_END)
                .filterNot(Char::isWhitespace)
            val decoded = try {
                Base64.getDecoder().decode(encoded)
            } catch (failure: IllegalArgumentException) {
                throw IllegalArgumentException("OpenSSH private-key encoding is invalid", failure)
            }
            try {
                require(decoded.size >= OPENSSH_MAGIC.size && OPENSSH_MAGIC.indices.all { decoded[it] == OPENSSH_MAGIC[it] }) {
                    "OpenSSH private-key header is invalid"
                }
                var offset = OPENSSH_MAGIC.size
                require(decoded.size >= offset + 4) { "OpenSSH private-key cipher header is truncated" }
                val length = ((decoded[offset].toInt() and 0xff) shl 24) or
                    ((decoded[offset + 1].toInt() and 0xff) shl 16) or
                    ((decoded[offset + 2].toInt() and 0xff) shl 8) or
                    (decoded[offset + 3].toInt() and 0xff)
                offset += 4
                require(length in 1..MAX_CIPHER_NAME_BYTES && offset + length <= decoded.size) {
                    "OpenSSH private-key cipher header is invalid"
                }
                return decoded.copyOfRange(offset, offset + length).toString(StandardCharsets.US_ASCII) != "none"
            } finally {
                decoded.fill(0)
            }
        }
        if (PKCS8_ENCRYPTED_BEGIN in text) return true
        if (PKCS8_BEGIN in text) return false
        throw IllegalArgumentException("Unsupported SSH private-key format")
    }

    private fun isPkcs8Pem(fileBytes: ByteArray): Boolean {
        val text = fileBytes.toString(StandardCharsets.ISO_8859_1)
        return PKCS8_BEGIN in text || PKCS8_ENCRYPTED_BEGIN in text
    }

    private fun isOpenSshPem(fileBytes: ByteArray): Boolean =
        OPENSSH_BEGIN in fileBytes.toString(StandardCharsets.ISO_8859_1)

    private fun parseOpenSsh(fileBytes: ByteArray, passphrase: CharArray?): KeyPair {
        val pemObject = ByteArrayInputStream(fileBytes).use { input ->
            PemReader(InputStreamReader(input, StandardCharsets.US_ASCII)).use { pem ->
                val parsed = requireNotNull(pem.readPemObject()) { "OpenSSH PEM is empty" }
                require(parsed.type == OPENSSH_PEM_TYPE) { "Unexpected OpenSSH PEM type" }
                require(pem.readPemObject() == null) { "OpenSSH PEM must contain exactly one object" }
                parsed
            }
        }
        val passphraseBytes = passphrase?.let(::utf8Bytes)
        val privateParameter = try {
            OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(pemObject.content, passphraseBytes)
        } finally {
            passphraseBytes?.fill(0)
            pemObject.content.fill(0)
        }
        return keyPair(privateParameter)
    }

    private fun parsePkcs8(fileBytes: ByteArray, passphrase: CharArray?): KeyPair {
        val privateKeyInfo = ByteArrayInputStream(fileBytes).use { input ->
            PEMParser(InputStreamReader(input, StandardCharsets.US_ASCII)).use { pem ->
                val parsed = requireNotNull(pem.readObject()) { "PKCS#8 PEM is empty" }
                require(pem.readObject() == null) { "PKCS#8 PEM must contain exactly one object" }
                when (parsed) {
                    is PrivateKeyInfo -> parsed
                    is PKCS8EncryptedPrivateKeyInfo -> {
                        val password = requireNotNull(passphrase) { "A passphrase is required" }
                        val decryptor = JceOpenSSLPKCS8DecryptorProviderBuilder()
                            .setProvider(BOUNCY_CASTLE)
                            .build(password)
                        parsed.decryptPrivateKeyInfo(decryptor)
                    }
                    else -> error("Unsupported PKCS#8 PEM object")
                }
            }
        }
        return keyPair(PrivateKeyFactory.createKey(privateKeyInfo), privateKeyInfo)
    }

    private fun keyPair(
        privateParameter: AsymmetricKeyParameter,
        privateKeyInfo: PrivateKeyInfo = PrivateKeyInfoFactory.createPrivateKeyInfo(privateParameter),
    ): KeyPair {
        val publicParameter = when (privateParameter) {
            is RSAPrivateCrtKeyParameters -> RSAKeyParameters(
                false,
                privateParameter.modulus,
                privateParameter.publicExponent,
            )
            is ECPrivateKeyParameters -> ECPublicKeyParameters(
                privateParameter.parameters.g.multiply(privateParameter.d),
                privateParameter.parameters,
            )
            is Ed25519PrivateKeyParameters -> privateParameter.generatePublicKey()
            else -> error("Unsupported PKCS#8 SSH key algorithm")
        }
        val converter = JcaPEMKeyConverter().setProvider(BOUNCY_CASTLE)
        return KeyPair(
            converter.getPublicKey(SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(publicParameter)),
            converter.getPrivateKey(privateKeyInfo),
        )
    }

    private fun utf8Bytes(chars: CharArray): ByteArray {
        val encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(chars))
        return try {
            ByteArray(encoded.remaining()).also(encoded::get)
        } finally {
            if (encoded.hasArray()) encoded.array().fill(0)
        }
    }

    private fun validate(pair: KeyPair): ParsedSshPrivateKeyFile {
        val publicBlob = SshPublicKeyCodec.encode(pair.public)
        val keyType = SshPublicKeyCodec.decode(publicBlob).type
        if (keyType == SshKeyType.RSA) {
            require((pair.public as RSAPublicKey).modulus.bitLength() >= MIN_RSA_BITS) {
                "RSA SSH keys smaller than $MIN_RSA_BITS bits are not supported"
            }
        }
        val jcaName = when (keyType) {
            SshKeyType.ED25519 -> "Ed25519"
            SshKeyType.RSA -> "SHA256withRSA"
            SshKeyType.ECDSA_NISTP256 -> "SHA256withECDSA"
        }
        val challenge = byteArrayOf(0x4e, 0x6f, 0x74, 0x69, 0x53, 0x79, 0x6e, 0x63)
        val signature = Signature.getInstance(jcaName, BOUNCY_CASTLE).run {
            initSign(pair.private)
            update(challenge)
            sign()
        }
        require(
            Signature.getInstance(jcaName, BOUNCY_CASTLE).run {
                initVerify(pair.public)
                update(challenge)
                verify(signature)
            },
        ) { "SSH public and private key material do not match" }
        val privateBytes = requireNotNull(pair.private.encoded) { "SSH private key cannot be stored" }
        return ParsedSshPrivateKeyFile(
            algorithm = when (keyType) {
                SshKeyType.ED25519 -> SshKeyAlgorithm.SSH_ED25519
                SshKeyType.RSA -> SshKeyAlgorithm.SSH_RSA
                SshKeyType.ECDSA_NISTP256 -> SshKeyAlgorithm.ECDSA_NISTP256
            },
            publicKeyBlob = publicBlob,
            pkcs8PrivateKey = privateBytes,
        )
    }

    private const val MIN_RSA_BITS = 2_048
    private const val MAX_CIPHER_NAME_BYTES = 64
    private const val MAX_KEY_TYPE_BYTES = 64
    private const val OPENSSH_BEGIN = "-----BEGIN OPENSSH PRIVATE KEY-----"
    private const val OPENSSH_END = "-----END OPENSSH PRIVATE KEY-----"
    private const val OPENSSH_PEM_TYPE = "OPENSSH PRIVATE KEY"
    private const val PKCS8_BEGIN = "-----BEGIN PRIVATE KEY-----"
    private const val PKCS8_ENCRYPTED_BEGIN = "-----BEGIN ENCRYPTED PRIVATE KEY-----"
    private val OPENSSH_MAGIC = "openssh-key-v1\u0000".encodeToByteArray()
    private val BOUNCY_CASTLE = BouncyCastleProvider()

    private class SshMetadataReader(
        private val bytes: ByteArray,
        private var offset: Int = 0,
        private val end: Int = bytes.size,
    ) {
        fun requireBytes(expected: ByteArray) {
            require(end - offset >= expected.size && expected.indices.all { bytes[offset + it] == expected[it] }) {
                "OpenSSH private-key header is invalid"
            }
            offset += expected.size
        }

        fun readU32(): Int {
            require(end - offset >= 4) { "OpenSSH private-key data is truncated" }
            val value = ((bytes[offset].toInt() and 0xff) shl 24) or
                ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                (bytes[offset + 3].toInt() and 0xff)
            offset += 4
            return value
        }

        fun readString(maxBytes: Int): String {
            val length = readU32()
            require(length >= 0 && length <= maxBytes && length <= end - offset) {
                "OpenSSH private-key string is invalid"
            }
            return String(bytes, offset, length, StandardCharsets.UTF_8).also { offset += length }
        }

        fun skipString() {
            val length = readU32()
            require(length >= 0 && length <= end - offset) { "OpenSSH private-key string is truncated" }
            offset += length
        }

        fun readNestedString(): SshMetadataReader {
            val length = readU32()
            require(length >= 0 && length <= end - offset) { "OpenSSH private-key block is truncated" }
            return SshMetadataReader(bytes, offset, offset + length).also { offset += length }
        }
    }
}
