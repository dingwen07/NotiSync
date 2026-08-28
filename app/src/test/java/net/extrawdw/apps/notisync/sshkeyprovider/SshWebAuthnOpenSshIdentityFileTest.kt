package net.extrawdw.apps.notisync.sshkeyprovider

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import net.extrawdw.notisync.ssh.core.SshKeyType
import net.extrawdw.notisync.ssh.core.SshPublicKeyCodec
import net.extrawdw.notisync.ssh.core.SshWireReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SshWebAuthnOpenSshIdentityFileTest {
    @Test
    fun encodesOpenSshSecurityKeyIdentityFields() {
        val credential = authenticatorBoundCredential()
        val identity = SshWebAuthnOpenSshIdentityFile.encode(
            credential = credential,
            comment = "YubiKey test",
            checkValue = 0x1020_3040,
        )

        val text = identity.decodeToString()
        assertTrue(text.startsWith("$OPENSSH_BEGIN\n"))
        assertTrue(text.endsWith("\n$OPENSSH_END\n"))
        assertFalse(text.lineSequence().any { it.length > 70 && !it.startsWith("-----") })
        val binary = Base64.getMimeDecoder().decode(
            text.substringAfter(OPENSSH_BEGIN).substringBefore(OPENSSH_END),
        )
        assertArrayEquals(OPENSSH_AUTH_MAGIC, binary.copyOfRange(0, OPENSSH_AUTH_MAGIC.size))

        val outer = SshWireReader(binary.copyOfRange(OPENSSH_AUTH_MAGIC.size, binary.size))
        assertEquals("none", outer.readUtf8())
        assertEquals("none", outer.readUtf8())
        assertArrayEquals(ByteArray(0), outer.readString())
        assertEquals(1L, outer.readUInt32())
        assertArrayEquals(credential.publicKeyBlob, outer.readString())
        val privateFields = SshWireReader(outer.readString())
        outer.requireEnd()

        assertEquals(0x1020_3040L, privateFields.readUInt32())
        assertEquals(0x1020_3040L, privateFields.readUInt32())
        assertEquals(SshKeyType.WEBAUTHN_SK_ECDSA_NISTP256.wireName, privateFields.readUtf8())
        assertEquals("nistp256", privateFields.readUtf8())

        val publicFields = SshWireReader(credential.publicKeyBlob)
        assertEquals(SshKeyType.WEBAUTHN_SK_ECDSA_NISTP256.wireName, publicFields.readUtf8())
        assertEquals("nistp256", publicFields.readUtf8())
        assertArrayEquals(publicFields.readString(), privateFields.readString())
        assertEquals(credential.rpId, publicFields.readUtf8())
        publicFields.requireEnd()

        assertEquals(credential.rpId, privateFields.readUtf8())
        assertEquals(0x25, privateFields.readByte())
        assertArrayEquals(credential.credentialId, privateFields.readString())
        assertArrayEquals(ByteArray(0), privateFields.readString())
        assertEquals("YubiKey test", privateFields.readUtf8())
        assertArrayEquals(
            ByteArray(privateFields.remaining) { (it + 1).toByte() },
            privateFields.readRemaining(),
        )
        privateFields.requireEnd()
    }

    @Test
    fun sshKeygenReadsExportedIdentityWhenAvailable() {
        val sshKeygen = "/usr/bin/ssh-keygen"
        assumeTrue(Files.isExecutable(java.nio.file.Path.of(sshKeygen)))
        val credential = authenticatorBoundCredential()
        val identity = SshWebAuthnOpenSshIdentityFile.encode(
            credential,
            "OpenSSH compatibility test",
            checkValue = 7,
        )
        val path = Files.createTempFile("notisync-id-ecdsa-sk-", "")
        try {
            Files.write(path, identity)
            runCatching {
                Files.setPosixFilePermissions(
                    path,
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            }
            val process = ProcessBuilder(sshKeygen, "-y", "-f", path.toString())
                .redirectErrorStream(true)
                .start()
            process.outputStream.close()
            assertTrue("ssh-keygen timed out", process.waitFor(10, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText().trim()
            assertEquals("ssh-keygen rejected the identity: $output", 0, process.exitValue())
            val publicFields = output.split(Regex("\\s+"), limit = 3)
            assertEquals(SshKeyType.WEBAUTHN_SK_ECDSA_NISTP256.wireName, publicFields[0])
            assertArrayEquals(credential.publicKeyBlob, Base64.getDecoder().decode(publicFields[1]))
        } finally {
            identity.fill(0)
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun rejectsBackupEligibleCredential() {
        val credential = authenticatorBoundCredential().copy(backupEligible = true)

        assertTrue(
            runCatching {
                SshWebAuthnOpenSshIdentityFile.encode(credential, "Synced passkey", checkValue = 1)
            }.isFailure,
        )
    }

    private fun authenticatorBoundCredential(): RegisteredSshWebAuthnCredential {
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        return RegisteredSshWebAuthnCredential(
            publicKeyBlob = SshPublicKeyCodec.encodeWebAuthnEcdsaP256(
                keyPair.public,
                SshWebAuthnCredential.RP_ID,
            ),
            credentialId = ByteArray(64) { (it + 1).toByte() },
            userHandle = "notisync-ssh:test".encodeToByteArray(),
            rpId = SshWebAuthnCredential.RP_ID,
            cosePublicKey = byteArrayOf(1),
            backupEligible = false,
            backupState = false,
        )
    }

    private companion object {
        const val OPENSSH_BEGIN = "-----BEGIN OPENSSH PRIVATE KEY-----"
        const val OPENSSH_END = "-----END OPENSSH PRIVATE KEY-----"
        val OPENSSH_AUTH_MAGIC = "openssh-key-v1\u0000".encodeToByteArray()
    }
}
