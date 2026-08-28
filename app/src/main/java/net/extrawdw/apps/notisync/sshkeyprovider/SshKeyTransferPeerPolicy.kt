package net.extrawdw.apps.notisync.sshkeyprovider

import net.extrawdw.notisync.peer.trust.Peer
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.SshAgentLimits

internal data class SshKeyTransferPeer(
    val clientId: ClientId,
    val displayName: String,
)

/**
 * [peers] is the trust store's active (trusted and currently sealable) peer set. A transfer target must
 * additionally be one of the user's devices and explicitly advertise routable SSH key-provider support.
 * SSH_AGENT_V1 is deliberately irrelevant: Android provides keys but does not expose a local SSH agent.
 */
internal fun eligibleSshKeyTransferPeers(peers: List<Peer>): List<SshKeyTransferPeer> = peers.asSequence()
    .filter { peer ->
        peer.ownDevice &&
            Capability.CAPABILITY_ROUTING_V1 in peer.capabilities &&
            Capability.SSH_KEY_PROVIDER_V1 in peer.capabilities
    }
    .map { peer -> SshKeyTransferPeer(peer.clientId, peer.displayName) }
    .distinctBy(SshKeyTransferPeer::clientId)
    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, SshKeyTransferPeer::displayName).thenBy { it.clientId.value })
    .toList()

internal fun sshKeyTransferRecipients(target: ClientId): Recipients =
    Recipients.OnlyCapable(target, SshAgentLimits.NORMAL_PROVIDER_CAPABILITIES)
