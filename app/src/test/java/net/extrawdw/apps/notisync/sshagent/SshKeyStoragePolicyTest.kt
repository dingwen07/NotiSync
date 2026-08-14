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
    fun onlyFrameworkRejectedEd25519ImportUsesWrappedOperationalFallback() {
        val frameworkFailure = java.security.KeyStoreException(
            "java.lang.IllegalArgumentException: Unsupported key algorithm: Ed25519",
        )
        assertTrue(
            SshKeyStoragePolicy.shouldUseWrappedOperationalFallback(
                SshKeyAlgorithm.SSH_ED25519,
                frameworkFailure,
            ),
        )
        assertFalse(
            SshKeyStoragePolicy.shouldUseWrappedOperationalFallback(
                SshKeyAlgorithm.SSH_RSA,
                frameworkFailure,
            ),
        )
        assertFalse(
            SshKeyStoragePolicy.shouldUseWrappedOperationalFallback(
                SshKeyAlgorithm.SSH_ED25519,
                IllegalStateException("detached public key mismatch"),
            ),
        )
    }

    @Test
    fun wrappedOperationalAesTriesStrongBoxWhenAvailable() {
        assertTrue(SshKeyStoragePolicy.shouldAttemptWrappedOperationalStrongBox(true))
        assertFalse(SshKeyStoragePolicy.shouldAttemptWrappedOperationalStrongBox(false))
    }

    @Test
    fun teeOnlyAffectsExportCopyCandidate() {
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
