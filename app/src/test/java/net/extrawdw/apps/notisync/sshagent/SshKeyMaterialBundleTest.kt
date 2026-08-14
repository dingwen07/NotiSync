package net.extrawdw.apps.notisync.sshagent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SshKeyMaterialBundleTest {
    @Test
    fun roundTripsKeystoreSigningCopyExportBackup() {
        val encoded = SshKeyMaterialBundle(exportEnvelope = byteArrayOf(1, 2, 3, 4)).encode()
        val decoded = SshKeyMaterialBundle.decode(encoded)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), decoded.exportEnvelope)
        assertNull(decoded.operationalEnvelope)
    }

    @Test
    fun roundTripsWrappedSigningKeyWithoutDuplicateBackup() {
        val encoded = SshKeyMaterialBundle(operationalEnvelope = byteArrayOf(6, 5, 4)).encode()
        val decoded = SshKeyMaterialBundle.decode(encoded)
        assertNull(decoded.exportEnvelope)
        assertArrayEquals(byteArrayOf(6, 5, 4), decoded.operationalEnvelope)
    }

    @Test
    fun rejectsDuplicatePrivateKeyRepresentations() {
        assertThrows(IllegalArgumentException::class.java) {
            SshKeyMaterialBundle(
                exportEnvelope = byteArrayOf(9, 8, 7),
                operationalEnvelope = byteArrayOf(6, 5, 4),
            ).encode()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedBundle() {
        val encoded = SshKeyMaterialBundle(exportEnvelope = byteArrayOf(1, 2, 3)).encode()
        SshKeyMaterialBundle.decode(encoded.copyOf(encoded.size - 1))
    }
}
