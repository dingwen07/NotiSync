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
