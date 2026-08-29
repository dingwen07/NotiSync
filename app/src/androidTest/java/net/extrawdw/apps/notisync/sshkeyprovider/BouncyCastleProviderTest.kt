package net.extrawdw.apps.notisync.sshkeyprovider

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import net.extrawdw.notisync.protocol.SshKeyAlgorithm
import net.extrawdw.notisync.ssh.core.SshPublicKeyCodec
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Run against a minified target APK to cover Bouncy Castle's reflective JCA registration. */
class BouncyCastleProviderTest {
    @Test
    fun sshAlgorithmsRemainUsableAfterReleaseShrinking() {
        val provider = BouncyCastleProvider()
        val pairs = listOf(
            "Ed25519" to KeyPairGenerator.getInstance("Ed25519", provider).generateKeyPair(),
            "RSA" to KeyPairGenerator.getInstance("RSA", provider).run {
                initialize(2_048)
                generateKeyPair()
            },
            "EC" to KeyPairGenerator.getInstance("EC", provider).run {
                initialize(ECGenParameterSpec("secp256r1"))
                generateKeyPair()
            },
        )

        pairs.forEach { (algorithm, pair) ->
            val publicBlob = SshPublicKeyCodec.encode(pair.public)
            assertArrayEquals(publicBlob, SshPublicKeyCodec.decode(publicBlob).blob)

            val signatureAlgorithm = when (algorithm) {
                "Ed25519" -> "Ed25519"
                "RSA" -> "SHA256withRSA"
                else -> "SHA256withECDSA"
            }
            val challenge = "NotiSync release provider test".encodeToByteArray()
            val signature = Signature.getInstance(signatureAlgorithm, provider).run {
                initSign(pair.private)
                update(challenge)
                sign()
            }
            assertTrue(Signature.getInstance(signatureAlgorithm, provider).run {
                initVerify(pair.public)
                update(challenge)
                verify(signature)
            })
        }
    }

    @Test
    fun encryptedPkcs8ExportRemainsReadableAfterReleaseShrinking() {
        val pair = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider()).generateKeyPair()
        val password = "release-provider-test".toCharArray()
        val encoded = SshPrivateKeyExportCodec.encode(requireNotNull(pair.private.encoded), password)
        val parsed = SshPrivateKeyFileParser.parse(encoded, password)
        try {
            assertEquals(SshKeyAlgorithm.SSH_ED25519, parsed.algorithm)
            assertArrayEquals(SshPublicKeyCodec.encode(pair.public), parsed.publicKeyBlob)
        } finally {
            password.fill('\u0000')
            encoded.fill(0)
            parsed.pkcs8PrivateKey.fill(0)
        }
    }
}
