package net.extrawdw.apps.notisync.sshagent

import net.extrawdw.notisync.protocol.SshExportCopyBackendPolicy
import net.extrawdw.notisync.protocol.SshKeyAlgorithm
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeyStoragePolicyTest {
    @Test
    fun ed25519DirectSignerSkipsStrongBox() {
        assertFalse(
            SshKeyStoragePolicy.shouldAttemptOperationalStrongBox(
                strongBoxAvailable = true,
                algorithm = SshKeyAlgorithm.SSH_ED25519,
            ),
        )
    }

    @Test
    fun rsaAndP256OperationalKeysTryStrongBoxWhenAvailable() {
        assertTrue(SshKeyStoragePolicy.shouldAttemptOperationalStrongBox(true, SshKeyAlgorithm.SSH_RSA))
        assertTrue(SshKeyStoragePolicy.shouldAttemptOperationalStrongBox(true, SshKeyAlgorithm.ECDSA_NISTP256))
        assertFalse(SshKeyStoragePolicy.shouldAttemptOperationalStrongBox(false, SshKeyAlgorithm.SSH_RSA))
    }

    @Test
    fun anyRejectedEd25519DirectImportUsesWrappedOperationalFallback() {
        assertTrue(SshKeyStoragePolicy.shouldUseWrappedOperationalFallback(SshKeyAlgorithm.SSH_ED25519))
        assertFalse(SshKeyStoragePolicy.shouldUseWrappedOperationalFallback(SshKeyAlgorithm.SSH_RSA))
        assertFalse(SshKeyStoragePolicy.shouldUseWrappedOperationalFallback(SshKeyAlgorithm.ECDSA_NISTP256))
    }

    @Test
    fun wrappedOperationalAesTriesStrongBoxWhenAvailable() {
        assertTrue(SshKeyStoragePolicy.shouldAttemptWrappedOperationalStrongBox(true))
        assertFalse(SshKeyStoragePolicy.shouldAttemptWrappedOperationalStrongBox(false))
    }

    @Test
    fun defaultBackendSelectionSkipsExportStrongBoxCandidate() {
        assertTrue(
            SshKeyStoragePolicy.shouldAttemptExportStrongBox(
                strongBoxAvailable = true,
                policy = SshExportCopyBackendPolicy.BEST_AVAILABLE,
            ),
        )
        assertFalse(
            SshKeyStoragePolicy.shouldAttemptExportStrongBox(
                strongBoxAvailable = true,
                policy = SshExportCopyBackendPolicy.TEE_ONLY,
            ),
        )
    }
}
