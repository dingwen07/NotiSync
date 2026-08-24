package net.extrawdw.notisync.ssh.core

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.KeySpec
import java.security.interfaces.ECPublicKey
import java.security.interfaces.EdECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.bouncycastle.jce.provider.BouncyCastleProvider

internal val SSH_BOUNCY_CASTLE_PROVIDER by lazy(LazyThreadSafetyMode.PUBLICATION) { BouncyCastleProvider() }

enum class SshKeyType(val wireName: String) {
    ED25519("ssh-ed25519"),
    RSA("ssh-rsa"),
    ECDSA_NISTP256("ecdsa-sha2-nistp256"),
    WEBAUTHN_SK_ECDSA_NISTP256("sk-ecdsa-sha2-nistp256@openssh.com"),
}

data class DecodedSshPublicKey(
    val type: SshKeyType,
    val wireName: String,
    val blob: ByteArray,
    val publicKey: PublicKey,
    /** OpenSSH security-key application / WebAuthn RP ID; absent for ordinary SSH keys. */
    val application: String? = null,
)

object SshPublicKeyCodec {
    const val MAXIMUM_PUBLIC_KEY_BLOB_SIZE = 16 * 1024
    private const val MINIMUM_RSA_BITS = 2048
    private const val MAXIMUM_RSA_BITS = 16_384

    fun decode(blob: ByteArray): DecodedSshPublicKey {
        if (blob.isEmpty() || blob.size > MAXIMUM_PUBLIC_KEY_BLOB_SIZE) {
            throw SshWireException("SSH public key blob is outside the allowed bounds")
        }
        val reader = SshWireReader(blob, MAXIMUM_PUBLIC_KEY_BLOB_SIZE)
        val algorithm = reader.readUtf8(128)
        val decoded = when (algorithm) {
            SshKeyType.ED25519.wireName -> decodeEd25519(reader).let { Triple(it.first, it.second, null) }
            SshKeyType.RSA.wireName -> decodeRsa(reader).let { Triple(it.first, it.second, null) }
            SshKeyType.ECDSA_NISTP256.wireName -> decodeEcdsaP256(reader).let { Triple(it.first, it.second, null) }
            SshKeyType.WEBAUTHN_SK_ECDSA_NISTP256.wireName -> decodeWebAuthnEcdsaP256(reader)
            else -> throw SshWireException("unsupported SSH public key algorithm $algorithm")
        }
        reader.requireEnd()
        return DecodedSshPublicKey(decoded.first, algorithm, blob.copyOf(), decoded.second, decoded.third)
    }

    fun encode(publicKey: PublicKey): ByteArray = when {
        publicKey is EdECPublicKey -> encodeEd25519(publicKey)
        isEd25519SubjectPublicKeyInfo(publicKey.encoded) -> encodeEd25519(publicKey.encoded)
        publicKey.algorithm.equals("Ed25519", ignoreCase = true) ||
            publicKey.algorithm.equals("EdDSA", ignoreCase = true) -> encodeEd25519(publicKey.encoded)
        publicKey is RSAPublicKey -> encodeRsa(publicKey)
        publicKey is ECPublicKey -> encodeEcdsaP256(publicKey)
        else -> throw SshWireException("unsupported public key type ${publicKey.algorithm}")
    }

    /**
     * Encodes a public key whose algorithm is already known from trusted key-generation/import context. This
     * overload refuses a provider object that represents a different curve instead of silently advertising the
     * key under that provider object's generic Java type.
     */
    fun encode(publicKey: PublicKey, expectedType: SshKeyType): ByteArray = when (expectedType) {
        SshKeyType.ED25519 -> when {
            publicKey is EdECPublicKey -> encodeEd25519(publicKey)
            isEd25519SubjectPublicKeyInfo(publicKey.encoded) -> encodeEd25519(publicKey.encoded)
            else -> throw SshWireException("provider did not return an Ed25519 public key")
        }
        SshKeyType.RSA -> encodeRsa(
            publicKey as? RSAPublicKey
                ?: throw SshWireException("provider did not return an RSA public key"),
        )
        SshKeyType.ECDSA_NISTP256 -> encodeEcdsaP256(
            publicKey as? ECPublicKey
                ?: throw SshWireException("provider did not return an ECDSA public key"),
        )
        SshKeyType.WEBAUTHN_SK_ECDSA_NISTP256 ->
            throw SshWireException("WebAuthn SSH public keys require an application / RP ID")
    }

    fun encodeWebAuthnEcdsaP256(publicKey: PublicKey, application: String): ByteArray {
        val ecPublicKey = publicKey as? ECPublicKey
            ?: throw SshWireException("WebAuthn SSH keys require an ECDSA public key")
        validateApplication(application)
        return encodeEcdsaP256Fields(
            publicKey = ecPublicKey,
            wireName = SshKeyType.WEBAUTHN_SK_ECDSA_NISTP256.wireName,
        ).writeUtf8(application).toByteArray()
    }

    private fun decodeEd25519(reader: SshWireReader): Pair<SshKeyType, PublicKey> {
        val encoded = reader.readString(32)
        if (encoded.size != 32) throw SshWireException("Ed25519 public key must be 32 bytes")
        val key = generateSoftwarePublicKey("Ed25519", X509EncodedKeySpec(ED25519_SPKI_PREFIX + encoded))
        return SshKeyType.ED25519 to key
    }

    private fun decodeRsa(reader: SshWireReader): Pair<SshKeyType, PublicKey> {
        val exponent = reader.readMpInt(MAXIMUM_PUBLIC_KEY_BLOB_SIZE)
        val modulus = reader.readMpInt(MAXIMUM_PUBLIC_KEY_BLOB_SIZE)
        if (exponent < BigInteger.valueOf(3) || !exponent.testBit(0)) {
            throw SshWireException("invalid RSA public exponent")
        }
        if (modulus.bitLength() !in MINIMUM_RSA_BITS..MAXIMUM_RSA_BITS) {
            throw SshWireException("RSA modulus is outside $MINIMUM_RSA_BITS..$MAXIMUM_RSA_BITS bits")
        }
        val key = generateSoftwarePublicKey("RSA", RSAPublicKeySpec(modulus, exponent))
        return SshKeyType.RSA to key
    }

    private fun decodeEcdsaP256(reader: SshWireReader): Pair<SshKeyType, PublicKey> {
        return SshKeyType.ECDSA_NISTP256 to decodeEcdsaP256Fields(reader)
    }

    private fun decodeWebAuthnEcdsaP256(reader: SshWireReader): Triple<SshKeyType, PublicKey, String> {
        val publicKey = decodeEcdsaP256Fields(reader)
        val application = reader.readUtf8(MAXIMUM_APPLICATION_UTF8_BYTES)
        validateApplication(application)
        return Triple(SshKeyType.WEBAUTHN_SK_ECDSA_NISTP256, publicKey, application)
    }

    private fun decodeEcdsaP256Fields(reader: SshWireReader): PublicKey {
        val curve = reader.readUtf8(32)
        if (curve != "nistp256") throw SshWireException("unsupported ECDSA curve $curve")
        val encodedPoint = reader.readString(65)
        if (encodedPoint.size != 65 || encodedPoint[0] != 4.toByte()) {
            throw SshWireException("P-256 public point must be an uncompressed 65-byte point")
        }
        val x = BigInteger(1, encodedPoint.copyOfRange(1, 33))
        val y = BigInteger(1, encodedPoint.copyOfRange(33, 65))
        val parameters = p256Parameters()
        val point = ECPoint(x, y)
        if (!isPointOnCurve(point, parameters)) throw SshWireException("P-256 public point is not on the curve")
        return generateSoftwarePublicKey("EC", ECPublicKeySpec(point, parameters))
    }

    private fun encodeEd25519(publicKey: EdECPublicKey): ByteArray {
        val point = publicKey.point
        val encoded = toFixedUnsigned(point.y, 32).reversedArray()
        if (point.isXOdd) encoded[31] = (encoded[31].toInt() or 0x80).toByte()
        return encodeEd25519Raw(encoded)
    }

    /**
     * Android Keystore and some providers expose Ed25519 keys as provider-specific [PublicKey] classes rather
     * than [EdECPublicKey]. RFC 8410 fixes the canonical SubjectPublicKeyInfo prefix, so decode the portable
     * X.509 representation instead of depending on a provider-specific interface.
     */
    private fun encodeEd25519(subjectPublicKeyInfo: ByteArray?): ByteArray {
        if (subjectPublicKeyInfo == null || subjectPublicKeyInfo.size != ED25519_SPKI_PREFIX.size + 32 ||
            !subjectPublicKeyInfo.copyOfRange(0, ED25519_SPKI_PREFIX.size).contentEquals(ED25519_SPKI_PREFIX)
        ) {
            throw SshWireException("invalid Ed25519 public key encoding")
        }
        return encodeEd25519Raw(subjectPublicKeyInfo.copyOfRange(ED25519_SPKI_PREFIX.size, subjectPublicKeyInfo.size))
    }

    private fun isEd25519SubjectPublicKeyInfo(encoded: ByteArray?): Boolean =
        encoded != null && encoded.size == ED25519_SPKI_PREFIX.size + 32 &&
            encoded.copyOfRange(0, ED25519_SPKI_PREFIX.size).contentEquals(ED25519_SPKI_PREFIX)

    private fun encodeEd25519Raw(encoded: ByteArray): ByteArray {
        require(encoded.size == 32)
        return SshWireWriter().writeUtf8(SshKeyType.ED25519.wireName).writeString(encoded).toByteArray()
    }

    private fun encodeRsa(publicKey: RSAPublicKey): ByteArray {
        if (publicKey.modulus.bitLength() !in MINIMUM_RSA_BITS..MAXIMUM_RSA_BITS) {
            throw SshWireException("RSA modulus is outside the supported size range")
        }
        return SshWireWriter()
            .writeUtf8(SshKeyType.RSA.wireName)
            .writeMpInt(publicKey.publicExponent)
            .writeMpInt(publicKey.modulus)
            .toByteArray()
    }

    private fun encodeEcdsaP256(publicKey: ECPublicKey): ByteArray {
        return encodeEcdsaP256Fields(publicKey, SshKeyType.ECDSA_NISTP256.wireName).toByteArray()
    }

    private fun encodeEcdsaP256Fields(publicKey: ECPublicKey, wireName: String): SshWireWriter {
        if (publicKey.params.curve.field.fieldSize != 256 || !isPointOnCurve(publicKey.w, p256Parameters())) {
            throw SshWireException("only ECDSA P-256 public keys are supported")
        }
        val encodedPoint = byteArrayOf(4) +
            toFixedUnsigned(publicKey.w.affineX, 32) +
            toFixedUnsigned(publicKey.w.affineY, 32)
        return SshWireWriter()
            .writeUtf8(wireName)
            .writeUtf8("nistp256")
            .writeString(encodedPoint)
    }

    private fun validateApplication(application: String) {
        if (application.isBlank() || application.encodeToByteArray().size > MAXIMUM_APPLICATION_UTF8_BYTES ||
            application.any { it <= '\u001f' || it == '\u007f' }
        ) {
            throw SshWireException("SSH security-key application is outside the allowed bounds")
        }
    }

    private fun p256Parameters() = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1"))
        getParameterSpec(java.security.spec.ECParameterSpec::class.java)
    }

    private fun isPointOnCurve(point: ECPoint, parameters: java.security.spec.ECParameterSpec): Boolean {
        val field = parameters.curve.field as? java.security.spec.ECFieldFp ?: return false
        val prime = field.p
        if (point.affineX.signum() < 0 || point.affineY.signum() < 0 ||
            point.affineX >= prime || point.affineY >= prime
        ) return false
        val left = point.affineY.modPow(BigInteger.TWO, prime)
        val right = point.affineX.modPow(BigInteger.valueOf(3), prime)
            .add(parameters.curve.a.multiply(point.affineX))
            .add(parameters.curve.b)
            .mod(prime)
        return left == right
    }

    private fun toFixedUnsigned(value: BigInteger, length: Int): ByteArray {
        val encoded = value.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        if (encoded.size > length) throw SshWireException("integer does not fit $length bytes")
        return ByteArray(length - encoded.size) + encoded
    }

    /** SSH wire blobs always reconstruct software public keys; OEM provider ordering must not affect decoding. */
    private fun generateSoftwarePublicKey(algorithm: String, spec: KeySpec): PublicKey =
        KeyFactory.getInstance(algorithm, SSH_BOUNCY_CASTLE_PROVIDER).generatePublic(spec)

    private val ED25519_SPKI_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )

    private const val MAXIMUM_APPLICATION_UTF8_BYTES = 1024
}

object SshFingerprint {
    fun sha256(publicKeyBlob: ByteArray): String {
        SshPublicKeyCodec.decode(publicKeyBlob)
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBlob)
        return "SHA256:${Base64.getEncoder().withoutPadding().encodeToString(digest)}"
    }
}
