package net.extrawdw.apps.notisync.data.ssh

import java.security.Signature
import net.extrawdw.notisync.protocol.SshKeyAlgorithm
import net.extrawdw.notisync.ssh.core.SshKeyType
import net.extrawdw.notisync.ssh.core.SshPublicKeyCodec
import net.extrawdw.notisync.ssh.core.SshSignatureCodec
import net.extrawdw.notisync.ssh.core.SshSignatureMethod
import net.extrawdw.notisync.ssh.core.SshSignatureVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomSshProviderRepositoryCryptoTest {
    @Test
    fun generatedSoftwareSignaturesAreFramedAndVerifyForAllAlgorithms() {
        val data = "room ssh provider signature framing".encodeToByteArray()
        val cases = listOf(
            Triple(SshKeyAlgorithm.SSH_ED25519, SshKeyType.ED25519, SshSignatureMethod.ED25519),
            Triple(SshKeyAlgorithm.SSH_RSA, SshKeyType.RSA, SshSignatureMethod.RSA_SHA2_256),
            Triple(SshKeyAlgorithm.ECDSA_NISTP256, SshKeyType.ECDSA_NISTP256, SshSignatureMethod.ECDSA_NISTP256),
        )

        cases.forEach { (algorithm, keyType, method) ->
            val keyPair = generateSoftwareSshKeyPair(algorithm, 2_048)
            val publicBlob = SshPublicKeyCodec.encode(keyPair.public, keyType)
            val raw = Signature.getInstance(method.jcaName, BouncyCastleProvider()).run {
                initSign(keyPair.private)
                update(data)
                sign()
            }
            val signatureBlob = encodeSshSignatureForProvider(method, raw)

            assertEquals(method, SshSignatureCodec.decode(signatureBlob).method)
            assertTrue(SshSignatureVerifier.verify(publicBlob, data, signatureBlob, method))
            assertFalse(SshSignatureVerifier.verify(publicBlob, data + byteArrayOf(0), signatureBlob, method))
        }
    }
}
