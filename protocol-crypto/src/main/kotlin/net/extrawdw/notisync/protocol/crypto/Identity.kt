package net.extrawdw.notisync.protocol.crypto

import net.extrawdw.notisync.protocol.ClientId
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * A device's identity signer (ECDSA-P256-SHA256). On Android the real implementation is backed by
 * a non-exportable Android Keystore key; this interface keeps the protocol layer agnostic of where
 * the private key lives. Signatures are DER-encoded, matching what the Keystore produces.
 */
interface IdentitySigner {
    /** X.509 SubjectPublicKeyInfo of the identity public key (EC P-256). */
    val publicKeySpki: ByteArray
    val clientId: ClientId
    fun sign(data: ByteArray): ByteArray
}

/**
 * A device's OPERATIONAL signer (NS2, ECDSA-P256-SHA256): an EC P-256 key delegated by the identity
 * key via a [net.extrawdw.notisync.protocol.ClientKeyEpoch], living in cheaper TEE keystore. It does
 * the hot-path work — signing every envelope and authenticated request — so the StrongBox identity
 * root stays cold. [clientId] is the *identity* fingerprint (stable across rotation), NOT the operational
 * key's fingerprint; [signerEpoch] (≥1) is the epoch whose key-epoch published this key.
 */
interface OperationalSigner {
    /** X.509 SubjectPublicKeyInfo of the operational public key (EC P-256). */
    val operationalPublicKeySpki: ByteArray
    val clientId: ClientId
    val signerEpoch: Int
    fun sign(data: ByteArray): ByteArray
}

/** Verifies ECDSA-P256-SHA256 signatures with plain JCA. Used by the broker and by peers. */
object IdentityVerifier {
    fun verify(identityPublicKeySpki: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        try {
            val pub = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(identityPublicKeySpki))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(pub)
                update(data)
                verify(signature)
            }
        } catch (_: Exception) {
            false
        }

    /** Confirms the signer id is the fingerprint of the public key AND the signature is valid. */
    fun verifyBound(
        expectedSignerId: ClientId,
        identityPublicKeySpki: ByteArray,
        data: ByteArray,
        signature: ByteArray,
    ): Boolean =
        ClientIds.derive(identityPublicKeySpki) == expectedSignerId &&
            verify(identityPublicKeySpki, data, signature)
}

/**
 * Software EC P-256 identity signer for tests and as a fallback where Android Keystore is
 * unavailable. The production Android path uses a hardware-backed, non-exportable Keystore key.
 */
class SoftwareIdentitySigner private constructor(
    override val publicKeySpki: ByteArray,
    private val privateKey: PrivateKey,
) : IdentitySigner {

    override val clientId: ClientId = ClientIds.derive(publicKeySpki)

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }

    companion object {
        fun generate(): SoftwareIdentitySigner {
            val kpg = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }
            val kp = kpg.generateKeyPair()
            return SoftwareIdentitySigner(kp.public.encoded, kp.private)
        }
    }
}

/**
 * Software EC P-256 operational signer for tests and JVM fallback. [clientId] is supplied (it is the
 * device's identity fingerprint, NOT this key's), as is the [signerEpoch]. The Android path uses a
 * TEE-backed, non-exportable Keystore key.
 */
class SoftwareOperationalSigner private constructor(
    override val operationalPublicKeySpki: ByteArray,
    override val clientId: ClientId,
    override val signerEpoch: Int,
    private val privateKey: PrivateKey,
) : OperationalSigner {

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }

    companion object {
        fun generate(clientId: ClientId, signerEpoch: Int): SoftwareOperationalSigner {
            val kpg = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }
            val kp = kpg.generateKeyPair()
            return SoftwareOperationalSigner(kp.public.encoded, clientId, signerEpoch, kp.private)
        }
    }
}
