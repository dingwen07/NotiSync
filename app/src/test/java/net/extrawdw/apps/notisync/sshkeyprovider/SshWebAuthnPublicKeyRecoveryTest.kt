package net.extrawdw.apps.notisync.sshkeyprovider

import java.math.BigInteger
import java.security.MessageDigest
import java.security.Signature
import net.extrawdw.notisync.ssh.core.SshPublicKeyCodec
import net.extrawdw.notisync.ssh.core.SshWireWriter
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.signers.StandardDSAEncoding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshWebAuthnPublicKeyRecoveryTest {
    @Test
    fun recoversAllFourCandidatesWhenBothPossibleXCoordinatesAreOnTheCurve() {
        val message = "Recovery candidates including x = r + n".encodeToByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(message)
        val n = SECNamedCurves.getByName("secp256r1").n
        // Small r makes the otherwise astronomically rare x = r + n case reachable.
        val (signature, candidates) = (1L..32L).map { r ->
            val der = StandardDSAEncoding.INSTANCE.encode(n, BigInteger.valueOf(r), BigInteger.ONE)
            der to SshWebAuthnPublicKeyRecovery.candidates(hash, der)
        }.first { (_, candidates) -> candidates.size == 4 }
        assertEquals(4, candidates.map { it.toList() }.toSet().size)
        candidates.forEach { point ->
            val publicKey = SshPublicKeyCodec.decode(
                SshWireWriter().writeUtf8("ecdsa-sha2-nistp256").writeUtf8("nistp256").writeString(point).toByteArray(),
            ).publicKey
            // Independent JCA verification checks the recovered points, hash interpretation and scalars.
            assertTrue(Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(message)
                verify(signature)
            })
        }
    }

    @Test
    fun rejectsOutOfRangeSignatureScalarsAndTrailingDer() {
        val n = SECNamedCurves.getByName("secp256r1").n
        val hash = ByteArray(32) { 1 }
        val outOfRange = StandardDSAEncoding.INSTANCE.encode(null, n, BigInteger.ONE)
        val negative = StandardDSAEncoding.INSTANCE.encode(null, BigInteger.ONE, BigInteger.ONE).copyOf().also { it[4] = 0xff.toByte() }
        val trailing = StandardDSAEncoding.INSTANCE.encode(n, BigInteger.ONE, BigInteger.ONE) + 0
        for (signature in listOf(outOfRange, negative, trailing)) {
            assertTrue(runCatching { SshWebAuthnPublicKeyRecovery.candidates(hash, signature) }.isFailure)
        }
    }
}
