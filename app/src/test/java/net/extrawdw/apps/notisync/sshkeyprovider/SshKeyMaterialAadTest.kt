package net.extrawdw.apps.notisync.sshkeyprovider

import net.extrawdw.notisync.protocol.SshKeyAlgorithm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class SshKeyMaterialAadTest {
    private val hash = ByteArray(32) { it.toByte() }

    @Test
    fun encodingIsDeterministic() {
        assertArrayEquals(
            SshKeyMaterialAad.exportCopy("key-id", SshKeyAlgorithm.SSH_ED25519, hash),
            SshKeyMaterialAad.exportCopy("key-id", SshKeyAlgorithm.SSH_ED25519, hash),
        )
    }

    @Test
    fun identityFieldsAreSeparated() {
        val export = SshKeyMaterialAad.exportCopy("key-id", SshKeyAlgorithm.SSH_ED25519, hash)
        val operational = SshKeyMaterialAad.wrappedOperational("key-id", SshKeyAlgorithm.SSH_ED25519, hash)
        val differentKey = SshKeyMaterialAad.exportCopy("other-key", SshKeyAlgorithm.SSH_ED25519, hash)
        val differentAlgorithm = SshKeyMaterialAad.exportCopy("key-id", SshKeyAlgorithm.SSH_RSA, hash)
        val differentHash = SshKeyMaterialAad.exportCopy(
            "key-id",
            SshKeyAlgorithm.SSH_ED25519,
            hash.copyOf().also { it[0] = 99 },
        )

        assertFalse(export.contentEquals(differentKey))
        assertFalse(export.contentEquals(operational))
        assertFalse(export.contentEquals(differentAlgorithm))
        assertFalse(export.contentEquals(differentHash))
    }

    @Test
    fun rejectsInvalidPublicHash() {
        assertThrows(IllegalArgumentException::class.java) {
            SshKeyMaterialAad.exportCopy("key-id", SshKeyAlgorithm.SSH_ED25519, ByteArray(31))
        }
    }
}
