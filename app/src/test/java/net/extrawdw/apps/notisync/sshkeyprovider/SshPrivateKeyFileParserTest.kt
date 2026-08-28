package net.extrawdw.apps.notisync.sshkeyprovider

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import net.extrawdw.notisync.protocol.SshKeyAlgorithm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SshPrivateKeyFileParserTest {
    @Test
    fun detectsEncryptionFromOpenSshCipherHeader() {
        assertFalse(SshPrivateKeyFileParser.isEncrypted(fixture("id_ed25519_unencrypted")))
        assertTrue(SshPrivateKeyFileParser.isEncrypted(fixture("id_ecdsa_encrypted")))
    }

    @Test
    fun parsesUnencryptedEd25519() {
        val parsed = SshPrivateKeyFileParser.parse(fixture("id_ed25519_unencrypted"), null)
        try {
            assertEquals(SshKeyAlgorithm.SSH_ED25519, parsed.algorithm)
        } finally {
            parsed.pkcs8PrivateKey.fill(0)
        }
    }

    @Test
    fun inspectionReturnsPublicOnlyPreviewForUnencryptedKey() {
        val inspection = SshPrivateKeyFileParser.inspect(fixture("id_ed25519_unencrypted"))

        assertFalse(inspection.encrypted)
        val preview = requireNotNull(inspection.preview)
        assertEquals(SshKeyAlgorithm.SSH_ED25519, preview.algorithm)
        assertTrue(preview.authorizedKey.startsWith("ssh-ed25519 "))
        assertTrue(preview.fingerprint.startsWith("SHA256:"))
    }

    @Test
    fun inspectionPreservesOpenSshPrivateKeyComment() {
        val inspection = SshPrivateKeyFileParser.inspect(fixture("id_ed25519_with_comment"))

        assertEquals("NotiSync comment fixture", inspection.comment)
        assertEquals("NotiSync comment fixture", requireNotNull(inspection.preview).comment)
    }

    @Test
    fun parsesEncryptedP256WithCorrectPassphrase() {
        val passphrase = "fixture-passphrase".toCharArray()
        val parsed = try {
            SshPrivateKeyFileParser.parse(fixture("id_ecdsa_encrypted"), passphrase)
        } finally {
            passphrase.fill('\u0000')
        }
        try {
            assertEquals(SshKeyAlgorithm.ECDSA_NISTP256, parsed.algorithm)
        } finally {
            parsed.pkcs8PrivateKey.fill(0)
        }
    }

    @Test
    fun encryptedInspectionRequiresPassphraseBeforePublicPreview() {
        val bytes = fixture("id_ecdsa_encrypted")
        val inspection = SshPrivateKeyFileParser.inspect(bytes)
        assertTrue(inspection.encrypted)
        assertEquals(null, inspection.preview)

        val passphrase = "fixture-passphrase".toCharArray()
        val preview = try {
            SshPrivateKeyFileParser.preview(bytes, passphrase)
        } finally {
            passphrase.fill('\u0000')
        }
        assertEquals(SshKeyAlgorithm.ECDSA_NISTP256, preview.algorithm)
        assertTrue(preview.authorizedKey.startsWith("ecdsa-sha2-nistp256 "))
        assertTrue(preview.fingerprint.startsWith("SHA256:"))
    }

    @Test
    fun rejectsWrongPassphrase() {
        assertThrows(IllegalArgumentException::class.java) {
            SshPrivateKeyFileParser.parse(fixture("id_ecdsa_encrypted"), "wrong".toCharArray())
        }
    }

    @Test
    fun inspectionRejectsTextThatIsNotAPrivateKey() {
        assertThrows(IllegalArgumentException::class.java) {
            SshPrivateKeyFileParser.inspect("not a private key".encodeToByteArray())
        }
    }

    @Test
    fun encryptedPkcs8ExportRoundTripsThroughImporter() {
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val privateBytes = requireNotNull(keyPair.private.encoded)
        val password = "export-test-password".toCharArray()
        val pem = try {
            SshPrivateKeyExportCodec.encode(privateBytes, password)
        } finally {
            privateBytes.fill(0)
            password.fill('\u0000')
        }
        try {
            assertTrue(SshPrivateKeyFileParser.isEncrypted(pem))
            val importPassword = "export-test-password".toCharArray()
            val parsed = try {
                SshPrivateKeyFileParser.parse(pem, importPassword)
            } finally {
                importPassword.fill('\u0000')
            }
            try {
                assertEquals(SshKeyAlgorithm.ECDSA_NISTP256, parsed.algorithm)
            } finally {
                parsed.pkcs8PrivateKey.fill(0)
            }
        } finally {
            pem.fill(0)
        }
    }

    @Test
    fun blankPasswordExportsPlaintextPkcs8ThatRoundTrips() {
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val privateBytes = requireNotNull(keyPair.private.encoded)
        val pem = try {
            SshPrivateKeyExportCodec.encode(privateBytes, null)
        } finally {
            privateBytes.fill(0)
        }
        try {
            assertFalse(SshPrivateKeyFileParser.isEncrypted(pem))
            val parsed = SshPrivateKeyFileParser.parse(pem, null)
            try {
                assertEquals(SshKeyAlgorithm.ECDSA_NISTP256, parsed.algorithm)
            } finally {
                parsed.pkcs8PrivateKey.fill(0)
            }
        } finally {
            pem.fill(0)
        }
    }

    private fun fixture(name: String): ByteArray = requireNotNull(
        javaClass.getResourceAsStream("/ssh/$name"),
    ).use { it.readBytes() }

}
