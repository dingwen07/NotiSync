package net.extrawdw.notisync.sshagent.endpoint

import java.security.SecureRandom
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.SshAgentLimits
import net.extrawdw.notisync.protocol.SshAgentSync
import net.extrawdw.notisync.protocol.SshAgentSyncKind
import net.extrawdw.notisync.protocol.SshForgetAuthorization
import net.extrawdw.notisync.sshagent.bridge.ProviderRoster
import net.extrawdw.notisync.sshagent.bridge.SshApplicationBridge
import net.extrawdw.notisync.sshagent.cache.AgentMetadataStore
import net.extrawdw.notisync.sshagent.cache.AuthorizationForgetOutbox
import net.extrawdw.notisync.sshagent.signing.SignCoordinator

class AuthorizationLockCoordinator(
    private val requesterClientId: ClientId,
    private val lockState: AgentLockState,
    private val metadata: AgentMetadataStore,
    private val roster: ProviderRoster,
    private val bridge: SshApplicationBridge,
    private val signing: SignCoordinator,
    private val forgetOutbox: AuthorizationForgetOutbox,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun lock(passphrase: ByteArray): Boolean {
        if (lockState.isLocked()) {
            passphrase.fill(0)
            return false
        }
        signing.suspendForLock()
        if (!lockState.lock(passphrase)) {
            signing.resumeAfterUnlock()
            return false
        }
        val oldNamespace = metadata.advanceAuthorizationEpoch().first
        val providers = roster.activeProviderIds().sortedBy(ClientId::value)
        if (providers.isNotEmpty()) {
            val requestedAt = now()
            val forget = SshForgetAuthorization(
                requestId = randomId(),
                requesterClientId = requesterClientId,
                authorizationGeneration = oldNamespace.generation,
                invalidatedThroughEpoch = oldNamespace.epoch,
                requestedAt = requestedAt,
                expiresAt = requestedAt + SshAgentLimits.MAX_FORGET_LIFETIME_MILLIS,
                targetProviderClientIds = providers,
            )
            // Persist before returning LOCK success; daemon acceptance then makes deletion safe.
            forgetOutbox.enqueue(forget)
            runCatching {
                bridge.sendNormal(
                    SshAgentSync(kind = SshAgentSyncKind.FORGET_AUTHORIZATION, forgetAuthorization = forget),
                    providers,
                )
                forgetOutbox.markAccepted(forget.requestId)
            }
        }
        return true
    }

    @Synchronized
    fun unlock(passphrase: ByteArray): Boolean {
        if (!lockState.unlock(passphrase)) return false
        signing.resumeAfterUnlock()
        return true
    }

    fun isLocked(): Boolean = lockState.isLocked()

    private fun randomId(): String = ByteArray(16).also(RANDOM::nextBytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val RANDOM = SecureRandom()
    }
}
