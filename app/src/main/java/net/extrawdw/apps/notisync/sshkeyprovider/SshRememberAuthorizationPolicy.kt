package net.extrawdw.apps.notisync.sshkeyprovider

import java.security.MessageDigest
import java.util.Base64
import net.extrawdw.notisync.protocol.SshApprovalPolicy
import net.extrawdw.notisync.protocol.SshDestinationContext
import net.extrawdw.notisync.protocol.SshDestinationProvenance
import net.extrawdw.notisync.protocol.DesktopProcessContext
import net.extrawdw.notisync.protocol.SshRememberScope
import net.extrawdw.notisync.protocol.SshUserVerificationPolicy

/** The lifetime boundary is part of the scope contract, not an implementation detail of SQLite. */
internal enum class SshRememberAuthorizationStorage { DISK, PROCESS_MEMORY }

internal val SshRememberScope.authorizationStorage: SshRememberAuthorizationStorage
    get() = when (this) {
        SshRememberScope.PEER,
        SshRememberScope.PEER_HOST_KEY,
        -> SshRememberAuthorizationStorage.DISK
        SshRememberScope.APPLICATION_PROCESS -> SshRememberAuthorizationStorage.PROCESS_MEMORY
    }

internal enum class SshRememberAuthorizationChoice(
    val scope: SshRememberScope,
    val applicationBound: Boolean,
    val hostBound: Boolean,
) {
    PEER(SshRememberScope.PEER, applicationBound = false, hostBound = false),
    PEER_HOST(SshRememberScope.PEER_HOST_KEY, applicationBound = false, hostBound = true),
    APPLICATION(SshRememberScope.APPLICATION_PROCESS, applicationBound = true, hostBound = false),
    APPLICATION_HOST(SshRememberScope.APPLICATION_PROCESS, applicationBound = true, hostBound = true),
}

internal data class SshRememberAuthorizationOptions(
    val choices: Set<SshRememberAuthorizationChoice>,
    val applicationAnchor: SshApplicationAnchor?,
)

/** Pure eligibility and matching rules shared by the approval UI and authorization stores. */
internal object SshRememberAuthorizationPolicy {
    fun keyAllowsRememberedAuthorization(
        approvalPolicy: SshApprovalPolicy,
        userVerificationPolicy: SshUserVerificationPolicy,
    ): Boolean = approvalPolicy == SshApprovalPolicy.ALLOW_REMEMBER &&
        userVerificationPolicy == SshUserVerificationPolicy.NONE

    fun availableDiskScopes(destination: SshDestinationContext): Set<SshRememberScope> = buildSet {
        add(SshRememberScope.PEER)
        if (verifiedHostKeySha256(destination) != null) add(SshRememberScope.PEER_HOST_KEY)
    }

    fun availableOptions(
        destination: SshDestinationContext,
        processContext: DesktopProcessContext,
    ): SshRememberAuthorizationOptions {
        val hostKeySha256 = verifiedHostKeySha256(destination)
        val applicationAnchor = SshApplicationAnchorSelector.select(processContext).recommended
        return SshRememberAuthorizationOptions(
            choices = buildSet {
                add(SshRememberAuthorizationChoice.PEER)
                if (hostKeySha256 != null) add(SshRememberAuthorizationChoice.PEER_HOST)
                if (applicationAnchor != null) {
                    add(SshRememberAuthorizationChoice.APPLICATION)
                    if (hostKeySha256 != null) add(SshRememberAuthorizationChoice.APPLICATION_HOST)
                }
            },
            applicationAnchor = applicationAnchor,
        )
    }

    fun hostKeySha256ForPersistentAuthorization(
        scope: SshRememberScope,
        destination: SshDestinationContext,
    ): ByteArray? = when (scope) {
        SshRememberScope.PEER -> null
        SshRememberScope.PEER_HOST_KEY -> verifiedHostKeySha256(destination)
        SshRememberScope.APPLICATION_PROCESS -> null
    }

    fun persistentAuthorizationMatches(
        scope: SshRememberScope,
        storedHostKeySha256: ByteArray?,
        destination: SshDestinationContext,
    ): Boolean = when (scope) {
        SshRememberScope.PEER -> storedHostKeySha256 == null
        SshRememberScope.PEER_HOST_KEY -> {
            val current = verifiedHostKeySha256(destination)
            storedHostKeySha256 != null && current != null &&
                MessageDigest.isEqual(storedHostKeySha256, current)
        }
        // Application authorizations, optionally host-bound, are handled by the volatile store.
        SshRememberScope.APPLICATION_PROCESS -> false
    }

    fun verifiedHostKeySha256(destination: SshDestinationContext): ByteArray? {
        if (destination.provenance != SshDestinationProvenance.VERIFIED_SESSION_BIND) return null
        val hostKey = destination.serverHostKeyBlob ?: return null
        val claimedDigest = destination.serverHostKeyBlobSha256 ?: return null
        val computedDigest = MessageDigest.getInstance("SHA-256").digest(hostKey)
        return computedDigest.takeIf { MessageDigest.isEqual(it, claimedDigest) }
    }
}

internal fun ByteArray.toSshHostKeyFingerprint(): String {
    require(size == net.extrawdw.notisync.protocol.SshAgentLimits.DIGEST_BYTES) {
        "invalid SSH host-key fingerprint"
    }
    return "SHA256:${Base64.getEncoder().withoutPadding().encodeToString(this)}"
}

internal fun SshKnownHost.fingerprint(): String = hostKeySha256.toSshHostKeyFingerprint()
