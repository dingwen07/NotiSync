package net.extrawdw.apps.notisync.sshkeyprovider

import net.extrawdw.notisync.protocol.SshExportCopyBackendPolicy
import net.extrawdw.notisync.protocol.SshKeyAlgorithm

/** Pure routing policy for direct signers, the narrow Ed25519 import fallback, and export copies. */
internal object SshKeyStoragePolicy {
    fun shouldAttemptOperationalStrongBox(
        strongBoxAvailable: Boolean,
        algorithm: SshKeyAlgorithm,
    ): Boolean = strongBoxAvailable && algorithm != SshKeyAlgorithm.SSH_ED25519

    fun shouldUseWrappedOperationalFallback(algorithm: SshKeyAlgorithm): Boolean =
        algorithm == SshKeyAlgorithm.SSH_ED25519

    fun shouldAttemptWrappedOperationalStrongBox(strongBoxAvailable: Boolean): Boolean =
        strongBoxAvailable

    fun shouldAttemptExportStrongBox(
        strongBoxAvailable: Boolean,
        policy: SshExportCopyBackendPolicy,
    ): Boolean = strongBoxAvailable && policy == SshExportCopyBackendPolicy.BEST_AVAILABLE
}
