package net.extrawdw.notisync.ssh.core

import java.math.BigInteger
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECPrivateKeySpec
import java.security.spec.EdECPoint
import java.security.spec.EdECPublicKeySpec
import java.security.spec.NamedParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPrivateCrtKeySpec

data class AgentAddConstraints(
    val lifetimeSeconds: Long? = null,
    val confirm: Boolean = false,
)

data class ParsedAgentIdentity(
    val type: SshKeyType,
    val publicKeyBlob: ByteArray,
    val publicKey: PublicKey,
    val privateKey: PrivateKey,
    val comment: String,
    val constraints: AgentAddConstraints,
)

/** Parses and validates RFC add-identity payloads without retaining them. */
object AgentAddIdentityParser {
    private const val MAX_COMMENT_SIZE = 4 * 1024
    private const val MAX_LIFETIME_SECONDS = 7 * 24 * 60 * 60L

    fun parse(identityPayload: ByteArray, constrained: Boolean): ParsedAgentIdentity {
        if (identityPayload.isEmpty() || identityPayload.size > AgentMessageCodec.MAX_IDENTITY_PAYLOAD_SIZE) {
            throw SshWireException("agent identity payload is outside the allowed bounds")
        }
        val reader = SshWireReader(identityPayload, AgentMessageCodec.MAX_IDENTITY_PAYLOAD_SIZE)
        val algorithm = reader.readUtf8(128)
        val keyParts = when (algorithm) {
            SshKeyType.ED25519.wireName -> parseEd25519(reader)
            SshKeyType.RSA.wireName -> parseRsa(reader)
            SshKeyType.ECDSA_NISTP256.wireName -> parseEcdsa(reader)
            else -> throw SshWireException("unsupported SSH agent identity algorithm $algorithm")
        }
        val comment = reader.readUtf8(MAX_COMMENT_SIZE)
        if (comment.any(Char::isISOControl)) throw SshWireException("identity comment contains control characters")
        val constraints = if (constrained) parseConstraints(reader) else AgentAddConstraints().also { reader.requireEnd() }
        validatePair(keyParts.type, keyParts.publicKey, keyParts.privateKey)
        return ParsedAgentIdentity(
            keyParts.type,
            keyParts.publicKeyBlob,
            keyParts.publicKey,
            keyParts.privateKey,
            comment,
            constraints,
        )
    }

    private fun parseEd25519(reader: SshWireReader): KeyParts {
        val public = reader.readString(32)
        val privateAndPublic = reader.readString(64)
        if (public.size != 32 || privateAndPublic.size != 64) {
            throw SshWireException("Ed25519 agent identity has invalid component lengths")
        }
        if (!privateAndPublic.copyOfRange(32, 64).contentEquals(public)) {
            throw SshWireException("Ed25519 private/public components do not match")
        }
        val publicBlob = SshWireWriter().writeUtf8(SshKeyType.ED25519.wireName).writeString(public).toByteArray()
        val publicKey = SshPublicKeyCodec.decode(publicBlob).publicKey
        val pkcs8 = ED25519_PKCS8_PREFIX + privateAndPublic.copyOfRange(0, 32)
        val privateKey = softwareKeyFactory("Ed25519").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        return KeyParts(SshKeyType.ED25519, publicBlob, publicKey, privateKey)
    }

    private fun parseRsa(reader: SshWireReader): KeyParts {
        val modulus = reader.readMpInt(16 * 1024)
        val exponent = reader.readMpInt(16 * 1024)
        val privateExponent = reader.readMpInt(16 * 1024)
        val coefficient = reader.readMpInt(16 * 1024)
        val primeP = reader.readMpInt(16 * 1024)
        val primeQ = reader.readMpInt(16 * 1024)
        if (modulus.bitLength() !in 2048..16_384 || exponent < BigInteger.valueOf(3) || !exponent.testBit(0)) {
            throw SshWireException("RSA identity parameters are outside the supported bounds")
        }
        if (listOf(privateExponent, coefficient, primeP, primeQ).any { it.signum() <= 0 }) {
            throw SshWireException("RSA private parameters must be positive")
        }
        if (primeP.multiply(primeQ) != modulus || primeQ.modInverse(primeP) != coefficient) {
            throw SshWireException("RSA CRT parameters are inconsistent")
        }
        val publicBlob = SshWireWriter()
            .writeUtf8(SshKeyType.RSA.wireName)
            .writeMpInt(exponent)
            .writeMpInt(modulus)
            .toByteArray()
        val publicKey = SshPublicKeyCodec.decode(publicBlob).publicKey
        val privateKey = softwareKeyFactory("RSA").generatePrivate(
            RSAPrivateCrtKeySpec(
                modulus,
                exponent,
                privateExponent,
                primeP,
                primeQ,
                privateExponent.mod(primeP - BigInteger.ONE),
                privateExponent.mod(primeQ - BigInteger.ONE),
                coefficient,
            ),
        )
        return KeyParts(SshKeyType.RSA, publicBlob, publicKey, privateKey)
    }

    private fun parseEcdsa(reader: SshWireReader): KeyParts {
        val curve = reader.readUtf8(32)
        if (curve != "nistp256") throw SshWireException("only ECDSA nistp256 identities are supported")
        val point = reader.readString(65)
        val scalar = reader.readMpInt(64)
        val publicBlob = SshWireWriter()
            .writeUtf8(SshKeyType.ECDSA_NISTP256.wireName)
            .writeUtf8(curve)
            .writeString(point)
            .toByteArray()
        val publicKey = SshPublicKeyCodec.decode(publicBlob).publicKey
        val parameters = (publicKey as java.security.interfaces.ECPublicKey).params
        if (scalar.signum() <= 0 || scalar >= parameters.order) {
            throw SshWireException("ECDSA private scalar is outside the curve order")
        }
        val privateKey = softwareKeyFactory("EC").generatePrivate(ECPrivateKeySpec(scalar, parameters))
        return KeyParts(SshKeyType.ECDSA_NISTP256, publicBlob, publicKey, privateKey)
    }

    private fun parseConstraints(reader: SshWireReader): AgentAddConstraints {
        var lifetime: Long? = null
        var confirm = false
        while (reader.remaining > 0) {
            when (val type = reader.readByte()) {
                AgentNumbers.SSH_AGENT_CONSTRAIN_LIFETIME -> {
                    if (lifetime != null) throw SshWireException("duplicate lifetime constraint")
                    lifetime = reader.readUInt32()
                    if (lifetime !in 1..MAX_LIFETIME_SECONDS) {
                        throw SshWireException("identity lifetime constraint is outside the allowed bounds")
                    }
                }
                AgentNumbers.SSH_AGENT_CONSTRAIN_CONFIRM -> {
                    if (confirm) throw SshWireException("duplicate confirm constraint")
                    confirm = true
                }
                AgentNumbers.SSH_AGENT_CONSTRAIN_EXTENSION ->
                    throw SshWireException("agent constraint extensions are not supported")
                else -> throw SshWireException("unknown SSH agent constraint $type")
            }
        }
        return AgentAddConstraints(lifetime, confirm)
    }

    private fun validatePair(type: SshKeyType, publicKey: PublicKey, privateKey: PrivateKey) {
        val algorithm = when (type) {
            SshKeyType.ED25519 -> "Ed25519"
            SshKeyType.RSA -> "SHA256withRSA"
            SshKeyType.ECDSA_NISTP256 -> "SHA256withECDSA"
            SshKeyType.WEBAUTHN_SK_ECDSA_NISTP256 ->
                throw SshWireException("WebAuthn SSH credentials cannot be imported through ssh-add")
        }
        val challenge = ByteArray(32) { index -> (index * 13 + 7).toByte() }
        val signature = softwareSignature(algorithm).run {
            initSign(privateKey)
            update(challenge)
            sign()
        }
        val verified = softwareSignature(algorithm).run {
            initVerify(publicKey)
            update(challenge)
            verify(signature)
        }
        if (!verified) throw SshWireException("SSH private/public key components do not match")
    }

    /** Agent identity payloads contain ordinary software key material, never Android Keystore handles. */
    private fun softwareKeyFactory(algorithm: String): KeyFactory =
        KeyFactory.getInstance(algorithm, SSH_BOUNCY_CASTLE_PROVIDER)

    private fun softwareSignature(algorithm: String): Signature =
        Signature.getInstance(algorithm, SSH_BOUNCY_CASTLE_PROVIDER)

    private data class KeyParts(
        val type: SshKeyType,
        val publicKeyBlob: ByteArray,
        val publicKey: PublicKey,
        val privateKey: PrivateKey,
    )

    private val ED25519_PKCS8_PREFIX = byteArrayOf(
        0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06,
        0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20,
    )
}
