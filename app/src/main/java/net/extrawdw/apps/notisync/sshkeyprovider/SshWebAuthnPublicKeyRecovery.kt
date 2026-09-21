package net.extrawdw.apps.notisync.sshkeyprovider

import java.math.BigInteger
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.signers.StandardDSAEncoding
import org.bouncycastle.math.ec.ECAlgorithms

/** SEC 1 v2, section 4.1.6, restricted to the ES256 curve used by NotiSync. */
internal object SshWebAuthnPublicKeyRecovery {
    // These are public computations; use BC's lightweight API without changing JCA providers.
    fun candidates(messageHash: ByteArray, derSignature: ByteArray): List<ByteArray> {
        require(messageHash.size == 32) { "ES256 recovery requires a SHA-256 digest" }
        val parameters = SECNamedCurves.getByName("secp256r1")
        val n = parameters.n
        val (r, s) = StandardDSAEncoding.INSTANCE.decode(n, derSignature)
        require(r.signum() > 0 && s.signum() > 0) { "invalid ES256 signature scalars" }
        val inverseR = r.modInverse(n)
        val generatorFactor = BigInteger(1, messageHash).negate().multiply(inverseR).mod(n)
        val pointFactor = s.multiply(inverseR).mod(n)
        val result = mutableListOf<ByteArray>()
        // P-256 has cofactor 1. Include the rare x = r + n case as well as x = r.
        for (j in 0..1) {
            val x = r.add(n.multiply(BigInteger.valueOf(j.toLong())))
            if (x >= parameters.curve.field.characteristic) continue
            val xBytes = x.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
            for (prefix in 2..3) {
                val point = try {
                    parameters.curve.decodePoint(byteArrayOf(prefix.toByte()) + xBytes)
                } catch (_: IllegalArgumentException) {
                    continue // This x coordinate does not lie on the curve.
                }
                if (!point.multiply(n).isInfinity) continue
                val publicPoint = ECAlgorithms.sumOfTwoMultiplies(
                    parameters.g, generatorFactor, point, pointFactor,
                ).normalize()
                if (publicPoint.isInfinity || !publicPoint.isValid) continue
                val encoded = publicPoint.getEncoded(false)
                if (result.none { it.contentEquals(encoded) }) result += encoded
            }
        }
        return result
    }
}
