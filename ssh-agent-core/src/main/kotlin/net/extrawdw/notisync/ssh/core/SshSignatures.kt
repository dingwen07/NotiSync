package net.extrawdw.notisync.ssh.core

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

enum class SshSignatureMethod(val wireName: String, val jcaName: String) {
    ED25519("ssh-ed25519", "Ed25519"),
    RSA_SHA2_256("rsa-sha2-256", "SHA256withRSA"),
    RSA_SHA2_512("rsa-sha2-512", "SHA512withRSA"),
    ECDSA_NISTP256("ecdsa-sha2-nistp256", "SHA256withECDSA"),
    WEBAUTHN_SK_ECDSA_NISTP256(
        "webauthn-sk-ecdsa-sha2-nistp256@openssh.com",
        "SHA256withECDSA",
    ),
    RSA_SHA1_LEGACY("ssh-rsa", "SHA1withRSA"),
}

data class DecodedSshSignature(
    val method: SshSignatureMethod,
    val signature: ByteArray,
)

object SshSignatureCodec {
    fun decode(signatureBlob: ByteArray): DecodedSshSignature {
        val reader = SshWireReader(signatureBlob, SshPublicKeyCodec.MAXIMUM_PUBLIC_KEY_BLOB_SIZE)
        val name = reader.readUtf8(128)
        val method = SshSignatureMethod.entries.firstOrNull { it.wireName == name }
            ?: throw SshWireException("unsupported SSH signature algorithm $name")
        val signature = if (method == SshSignatureMethod.WEBAUTHN_SK_ECDSA_NISTP256) {
            // Unlike ordinary SSH signatures, OpenSSH's WebAuthn signature fields follow the
            // algorithm name directly; they are not enclosed in one additional SSH string.
            reader.readRemaining()
        } else {
            reader.readString(16 * 1024).also { reader.requireEnd() }
        }
        return DecodedSshSignature(method, signature)
    }

    fun encode(method: SshSignatureMethod, signature: ByteArray): ByteArray {
        if (method == SshSignatureMethod.WEBAUTHN_SK_ECDSA_NISTP256) {
            throw SshWireException("WebAuthn SSH signatures require the dedicated codec")
        }
        if (signature.isEmpty() || signature.size > 16 * 1024) {
            throw SshWireException("signature is outside the allowed bounds")
        }
        return SshWireWriter().writeUtf8(method.wireName).writeString(signature).toByteArray()
    }
}

data class WebAuthnSshSignature(
    /** RFC 5656 SSH encoding containing mpint r and mpint s. */
    val ecdsaSignature: ByteArray,
    val flags: Int,
    val counter: Long,
    val origin: String,
    /** Exact CollectedClientData bytes returned by the credential provider. */
    val clientDataJson: ByteArray,
    /** Raw authenticator extension bytes following the fixed 37-byte authenticator-data prefix. */
    val extensions: ByteArray,
)

object WebAuthnSshSignatureCodec {
    const val FLAG_USER_PRESENT = 0x01
    const val FLAG_USER_VERIFIED = 0x04
    const val FLAG_BACKUP_ELIGIBLE = 0x08
    const val FLAG_BACKUP_STATE = 0x10
    const val FLAG_ATTESTED_CREDENTIAL_DATA = 0x40
    const val FLAG_EXTENSION_DATA = 0x80

    fun encode(value: WebAuthnSshSignature): ByteArray {
        validate(value)
        return SshWireWriter(MAXIMUM_PAYLOAD_BYTES)
            .writeUtf8(SshSignatureMethod.WEBAUTHN_SK_ECDSA_NISTP256.wireName)
            .writeString(value.ecdsaSignature)
            .writeByte(value.flags)
            .writeUInt32(value.counter)
            .writeUtf8(value.origin)
            .writeString(value.clientDataJson)
            .writeString(value.extensions)
            .toByteArray()
    }

    fun decode(signatureBlob: ByteArray): WebAuthnSshSignature {
        val decoded = SshSignatureCodec.decode(signatureBlob)
        if (decoded.method != SshSignatureMethod.WEBAUTHN_SK_ECDSA_NISTP256) {
            throw SshWireException("SSH signature is not a WebAuthn ECDSA-SK signature")
        }
        val reader = SshWireReader(decoded.signature, MAXIMUM_PAYLOAD_BYTES)
        val value = WebAuthnSshSignature(
            ecdsaSignature = reader.readString(256),
            flags = reader.readByte(),
            counter = reader.readUInt32(),
            origin = reader.readUtf8(MAXIMUM_ORIGIN_UTF8_BYTES),
            clientDataJson = reader.readString(MAXIMUM_CLIENT_DATA_BYTES),
            extensions = reader.readString(MAXIMUM_EXTENSION_BYTES),
        )
        reader.requireEnd()
        validate(value)
        return value
    }

    internal fun expectedClientDataPrefix(data: ByteArray, origin: String): ByteArray {
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(data)
        return "{\"type\":\"webauthn.get\",\"challenge\":\"$challenge\",\"origin\":\"$origin\""
            .toByteArray(StandardCharsets.UTF_8)
    }

    private fun validate(value: WebAuthnSshSignature) {
        EcdsaSignatureTranscoder.sshToDer(value.ecdsaSignature)
        if (value.flags !in 0..255 || value.flags and FLAG_ATTESTED_CREDENTIAL_DATA != 0) {
            throw SshWireException("invalid WebAuthn assertion flags")
        }
        if ((value.flags and FLAG_BACKUP_STATE != 0) && (value.flags and FLAG_BACKUP_ELIGIBLE == 0)) {
            throw SshWireException("WebAuthn backup state requires backup eligibility")
        }
        val extensionFlag = value.flags and FLAG_EXTENSION_DATA != 0
        if (extensionFlag != value.extensions.isNotEmpty()) {
            throw SshWireException("WebAuthn extension flag does not match extension data")
        }
        if (value.origin.isBlank() || value.origin.encodeToByteArray().size > MAXIMUM_ORIGIN_UTF8_BYTES ||
            '"' in value.origin || value.origin.any { it <= '\u001f' || it == '\u007f' }
        ) {
            throw SshWireException("WebAuthn origin is outside the allowed bounds")
        }
        if (value.clientDataJson.isEmpty() || value.clientDataJson.size > MAXIMUM_CLIENT_DATA_BYTES) {
            throw SshWireException("WebAuthn client data is outside the allowed bounds")
        }
        if (value.extensions.size > MAXIMUM_EXTENSION_BYTES) {
            throw SshWireException("WebAuthn extension data is outside the allowed bounds")
        }
    }

    private const val MAXIMUM_PAYLOAD_BYTES = 16 * 1024
    private const val MAXIMUM_ORIGIN_UTF8_BYTES = 1024
    private const val MAXIMUM_CLIENT_DATA_BYTES = 12 * 1024
    private const val MAXIMUM_EXTENSION_BYTES = 2 * 1024
}

object EcdsaSignatureTranscoder {
    fun derToSsh(der: ByteArray): ByteArray {
        val reader = DerReader(der)
        val sequence = reader.readElement(0x30)
        reader.requireEnd()
        val integers = DerReader(sequence)
        val r = integers.readPositiveInteger()
        val s = integers.readPositiveInteger()
        integers.requireEnd()
        return SshWireWriter(256).writeMpInt(r).writeMpInt(s).toByteArray()
    }

    fun sshToDer(ssh: ByteArray): ByteArray {
        val reader = SshWireReader(ssh, 128)
        val r = reader.readMpInt(66)
        val s = reader.readMpInt(66)
        reader.requireEnd()
        if (r.signum() <= 0 || s.signum() <= 0 || r.bitLength() > 256 || s.bitLength() > 256) {
            throw SshWireException("ECDSA signature integers are outside P-256 bounds")
        }
        val contents = derInteger(r) + derInteger(s)
        return byteArrayOf(0x30) + derLength(contents.size) + contents
    }

    private fun derInteger(value: BigInteger): ByteArray {
        val encoded = value.toByteArray()
        return byteArrayOf(0x02) + derLength(encoded.size) + encoded
    }

    private fun derLength(length: Int): ByteArray = when {
        length < 0x80 -> byteArrayOf(length.toByte())
        length <= 0xff -> byteArrayOf(0x81.toByte(), length.toByte())
        else -> byteArrayOf(0x82.toByte(), (length ushr 8).toByte(), length.toByte())
    }

    private class DerReader(private val bytes: ByteArray) {
        private var offset = 0

        fun readElement(expectedTag: Int): ByteArray {
            if (offset >= bytes.size || bytes[offset++].toInt() and 0xff != expectedTag) {
                throw SshWireException("unexpected DER tag")
            }
            val length = readLength()
            if (length > bytes.size - offset) throw SshWireException("truncated DER element")
            return bytes.copyOfRange(offset, offset + length).also { offset += length }
        }

        fun readPositiveInteger(): BigInteger {
            val encoded = readElement(0x02)
            if (encoded.isEmpty() || encoded[0].toInt() and 0x80 != 0) {
                throw SshWireException("DER integer must be positive")
            }
            if (encoded.size > 1 && encoded[0] == 0.toByte() && encoded[1].toInt() and 0x80 == 0) {
                throw SshWireException("non-canonical DER integer")
            }
            return BigInteger(1, encoded)
        }

        fun requireEnd() {
            if (offset != bytes.size) throw SshWireException("trailing DER data")
        }

        private fun readLength(): Int {
            if (offset >= bytes.size) throw SshWireException("missing DER length")
            val first = bytes[offset++].toInt() and 0xff
            if (first < 0x80) return first
            val count = first and 0x7f
            if (count == 0 || count > 2 || offset + count > bytes.size) {
                throw SshWireException("unsupported DER length")
            }
            var value = 0
            repeat(count) { value = (value shl 8) or (bytes[offset++].toInt() and 0xff) }
            if (value < 0x80) throw SshWireException("non-canonical DER length")
            return value
        }
    }
}

object SshSignatureVerifier {
    fun methodFor(keyType: SshKeyType, flags: Long, allowLegacyRsaSha1: Boolean = false): SshSignatureMethod =
        when (keyType) {
            SshKeyType.ED25519 -> {
                if (flags != 0L) throw SshWireException("Ed25519 does not accept SSH agent flags")
                SshSignatureMethod.ED25519
            }
            SshKeyType.ECDSA_NISTP256 -> {
                if (flags != 0L) throw SshWireException("ECDSA does not accept SSH agent flags")
                SshSignatureMethod.ECDSA_NISTP256
            }
            SshKeyType.WEBAUTHN_SK_ECDSA_NISTP256 -> {
                if (flags != 0L) throw SshWireException("WebAuthn ECDSA-SK does not accept SSH agent flags")
                SshSignatureMethod.WEBAUTHN_SK_ECDSA_NISTP256
            }
            SshKeyType.RSA -> when (flags) {
                AgentNumbers.SSH_AGENT_RSA_SHA2_256 -> SshSignatureMethod.RSA_SHA2_256
                AgentNumbers.SSH_AGENT_RSA_SHA2_512 -> SshSignatureMethod.RSA_SHA2_512
                0L -> if (allowLegacyRsaSha1) SshSignatureMethod.RSA_SHA1_LEGACY else {
                    throw SshWireException("legacy RSA/SHA-1 signatures are disabled")
                }
                else -> throw SshWireException("unsupported or conflicting RSA SSH agent flags")
            }
        }

    fun verify(
        publicKeyBlob: ByteArray,
        data: ByteArray,
        signatureBlob: ByteArray,
        expectedMethod: SshSignatureMethod,
        allowLegacyRsaSha1: Boolean = false,
    ): Boolean {
        if (expectedMethod == SshSignatureMethod.RSA_SHA1_LEGACY && !allowLegacyRsaSha1) return false
        val key = runCatching { SshPublicKeyCodec.decode(publicKeyBlob) }.getOrNull() ?: return false
        if (!methodMatchesKey(expectedMethod, key.type)) return false
        val signature = runCatching { SshSignatureCodec.decode(signatureBlob) }.getOrNull() ?: return false
        if (signature.method != expectedMethod) return false
        if (expectedMethod == SshSignatureMethod.WEBAUTHN_SK_ECDSA_NISTP256) {
            return verifyWebAuthn(key, data, signatureBlob)
        }
        val jcaSignature = if (expectedMethod == SshSignatureMethod.ECDSA_NISTP256) {
            runCatching { EcdsaSignatureTranscoder.sshToDer(signature.signature) }.getOrNull() ?: return false
        } else {
            signature.signature
        }
        if (expectedMethod == SshSignatureMethod.ED25519 && jcaSignature.size != 64) return false
        fun verifyWith(signatureInstance: Signature): Boolean = signatureInstance.run {
            initVerify(key.publicKey)
            update(data)
            verify(jcaSignature)
        }
        val platform = runCatching {
            verifyWith(Signature.getInstance(expectedMethod.jcaName))
        }
        platform.getOrNull()?.let { return it }
        return runCatching {
            verifyWith(Signature.getInstance(expectedMethod.jcaName, SSH_BOUNCY_CASTLE_PROVIDER))
        }.getOrDefault(false)
    }

    private fun verifyWebAuthn(
        key: DecodedSshPublicKey,
        data: ByteArray,
        signatureBlob: ByteArray,
    ): Boolean {
        if (key.type != SshKeyType.WEBAUTHN_SK_ECDSA_NISTP256) return false
        val application = key.application ?: return false
        val assertion = runCatching { WebAuthnSshSignatureCodec.decode(signatureBlob) }.getOrNull() ?: return false
        if (assertion.flags and WebAuthnSshSignatureCodec.FLAG_USER_PRESENT == 0 ||
            assertion.flags and WebAuthnSshSignatureCodec.FLAG_USER_VERIFIED == 0
        ) return false
        val expectedPrefix = WebAuthnSshSignatureCodec.expectedClientDataPrefix(data, assertion.origin)
        if (assertion.clientDataJson.size < expectedPrefix.size ||
            !assertion.clientDataJson.copyOfRange(0, expectedPrefix.size).contentEquals(expectedPrefix)
        ) return false
        val rpIdHash = MessageDigest.getInstance("SHA-256").digest(application.toByteArray(StandardCharsets.UTF_8))
        val authenticatorData = SshWireWriter(32 + 1 + 4 + assertion.extensions.size)
            .writeRaw(rpIdHash)
            .writeByte(assertion.flags)
            .writeUInt32(assertion.counter)
            .writeRaw(assertion.extensions)
            .toByteArray()
        val signedData = authenticatorData + MessageDigest.getInstance("SHA-256").digest(assertion.clientDataJson)
        val der = runCatching { EcdsaSignatureTranscoder.sshToDer(assertion.ecdsaSignature) }.getOrNull() ?: return false
        return runCatching {
            Signature.getInstance(SshSignatureMethod.WEBAUTHN_SK_ECDSA_NISTP256.jcaName).run {
                initVerify(key.publicKey)
                update(signedData)
                verify(der)
            }
        }.recoverCatching {
            Signature.getInstance(
                SshSignatureMethod.WEBAUTHN_SK_ECDSA_NISTP256.jcaName,
                SSH_BOUNCY_CASTLE_PROVIDER,
            ).run {
                initVerify(key.publicKey)
                update(signedData)
                verify(der)
            }
        }.getOrDefault(false)
    }

    internal fun methodMatchesKey(method: SshSignatureMethod, keyType: SshKeyType): Boolean = when (keyType) {
        SshKeyType.ED25519 -> method == SshSignatureMethod.ED25519
        SshKeyType.ECDSA_NISTP256 -> method == SshSignatureMethod.ECDSA_NISTP256
        SshKeyType.WEBAUTHN_SK_ECDSA_NISTP256 ->
            method == SshSignatureMethod.WEBAUTHN_SK_ECDSA_NISTP256
        SshKeyType.RSA -> method in setOf(
            SshSignatureMethod.RSA_SHA2_256,
            SshSignatureMethod.RSA_SHA2_512,
            SshSignatureMethod.RSA_SHA1_LEGACY,
        )
    }
}
