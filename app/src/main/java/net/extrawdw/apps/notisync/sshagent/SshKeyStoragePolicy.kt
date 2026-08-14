package net.extrawdw.apps.notisync.sshagent

import net.extrawdw.notisync.protocol.SshExportCopyBackendPolicy
import net.extrawdw.notisync.protocol.SshKeyAlgorithm

/** Pure routing policy for direct signers, the narrow Ed25519 import fallback, and export copies. */
internal object SshKeyStoragePolicy {
    fun shouldAttemptOperationalStrongBox(
        strongBoxAvailable: Boolean,
        algorithm: SshKeyAlgorithm,
    ): Boolean = strongBoxAvailable && algorithm != SshKeyAlgorithm.SSH_ED25519

    fun shouldUseWrappedOperationalFallback(
        algorithm: SshKeyAlgorithm,
        failure: Throwable,
    ): Boolean = algorithm == SshKeyAlgorithm.SSH_ED25519 &&
        generateSequence(failure) { it.cause }.any { cause ->
            val message = cause.message.orEmpty()
            message.contains("Unsupported key algorithm:", ignoreCase = true) &&
                (message.contains("Ed25519", ignoreCase = true) ||
                    message.contains("EdDSA", ignoreCase = true) ||
                    message.contains("1.3.101.112"))
        }

    fun shouldAttemptWrappedOperationalStrongBox(strongBoxAvailable: Boolean): Boolean =
        strongBoxAvailable

    fun shouldAttemptExportStrongBox(
        strongBoxAvailable: Boolean,
        policy: SshExportCopyBackendPolicy,
    ): Boolean = strongBoxAvailable && policy == SshExportCopyBackendPolicy.BEST_AVAILABLE
}
