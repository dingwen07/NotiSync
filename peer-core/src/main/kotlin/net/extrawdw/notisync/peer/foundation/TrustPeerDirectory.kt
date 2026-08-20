package net.extrawdw.notisync.peer.foundation

import java.util.Base64
import net.extrawdw.notisync.peer.channel.AudienceSnapshot
import net.extrawdw.notisync.peer.channel.PeerDirectory
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.SenderKey
import net.extrawdw.notisync.peer.trust.TrustDirectoryPeer
import net.extrawdw.notisync.peer.trust.TrustState
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.crypto.RecipientKey

/**
 * [PeerDirectory] over the trust roster — the foundation's implementation of the channel's key port.
 *
 * Outbound resolution consumes one [TrustState.directorySnapshot], so sealable recipients and targeted
 * key-repair peers cannot come from different roster versions. The base64 key fields are decoded here so the
 * channel deals only in bytes; the inbound per-epoch anti-rollback gate remains in
 * [TrustState.peerOperationalSpki].
 */
class TrustPeerDirectory(
    private val trust: TrustState,
    private val now: () -> Long = System::currentTimeMillis,
) : PeerDirectory {
    private val b64 = Base64.getDecoder()

    override fun resolveSender(id: ClientId, signerEpoch: Int): SenderKey? {
        val peer = trust.activePeers.value.firstOrNull { it.clientId == id } ?: return null
        // Epoch 0 ⇒ the sender's pinned identity key (always-valid root). Epoch ≥1 ⇒ the operational key,
        // resolved only if it is ≥ the floor and carries ENVELOPE_SIGN — else null and the channel drops.
        val verifySpki = if (signerEpoch == 0) {
            b64.decode(peer.identityPublicKeyB64)
        } else {
            trust.peerOperationalSpki(id, signerEpoch) ?: return null
        }
        return SenderKey(verifySpki, peer.ownDevice)
    }

    override fun resolveAudience(scope: Recipients): AudienceSnapshot {
        val snapshot = trust.directorySnapshot(now()).peers
        val selected = snapshot.filter { it.matches(scope) }
        val recipients = selected.mapNotNull { directoryPeer ->
            directoryPeer.sealablePeer?.let { peer ->
                // Seal to the peer's CURRENT HPKE epoch — bound into PerRecipientKey.recipientEpoch + the
                // signed EnvelopeAuth, so the receiver selects its matching retained private keyset.
                RecipientKey(
                    peer.clientId,
                    b64.decode(peer.hpkePublicKeyB64),
                    peer.currentEpoch,
                )
            }
        }
        val unsealable = selected
            .asSequence()
            .filter(TrustDirectoryPeer::needsKeyEpoch)
            .map(TrustDirectoryPeer::clientId)
            .toSet()
        return AudienceSnapshot(recipients, unsealable)
    }

    private fun TrustDirectoryPeer.matches(scope: Recipients): Boolean = when (scope) {
        Recipients.OwnMesh -> ownDevice
        Recipients.AllTrusted -> true
        is Recipients.OwnMeshFiltered -> ownDevice &&
            clientId !in scope.excluded &&
            capabilityOrLegacyPlatformAllows(capabilities, platform, scope)
        is Recipients.Only -> clientId == scope.id && ownDevice
        is Recipients.OnlyCapable -> clientId == scope.id &&
            ownDevice &&
            Capability.CAPABILITY_ROUTING_V1 in capabilities &&
            capabilities.containsAll(scope.requiredCapabilities)
        is Recipients.OnlyCapableSet -> clientId in scope.ids &&
            ownDevice &&
            Capability.CAPABILITY_ROUTING_V1 in capabilities &&
            capabilities.containsAll(scope.requiredCapabilities)
    }

    private fun capabilityOrLegacyPlatformAllows(
        capabilities: List<Capability>,
        platform: String,
        scope: Recipients.OwnMeshFiltered,
    ): Boolean {
        val normalizedPlatform = platform.normalizedPlatform()
        if (normalizedPlatform in scope.excludedPlatforms.normalizedPlatforms()) return false
        if (capabilities.any(scope.forbiddenCapabilities::contains)) return false
        // Existing capabilities predate capability routing and are safe to enforce across the whole fleet.
        // The marker is needed only before relying on newly-added declarations instead of platform fallback.
        val existingRequired = scope.requiredCapabilities.filterTo(mutableSetOf()) { it in LEGACY_CAPABILITIES }
        if (!capabilities.containsAll(existingRequired)) return false
        if (scope.requireCapabilityRoutingV1 && Capability.CAPABILITY_ROUTING_V1 !in capabilities) return false
        return if (Capability.CAPABILITY_ROUTING_V1 in capabilities) {
            capabilities.containsAll(scope.requiredCapabilities)
        } else {
            normalizedPlatform !in scope.legacyExcludedPlatforms.normalizedPlatforms()
        }
    }

    private fun Set<String>.normalizedPlatforms(): Set<String> =
        mapNotNullTo(mutableSetOf()) { it.normalizedPlatform().takeIf { normalized -> normalized.isNotEmpty() } }

    private fun String.normalizedPlatform(): String = trim().lowercase()

    private companion object {
        val LEGACY_CAPABILITIES = setOf(
            Capability.CAPTURE,
            Capability.DISPLAY,
            Capability.DISMISS_SYNC,
            Capability.PROVIDE_ASSETS,
            Capability.BACKGROUND_WAKE,
            Capability.FOREGROUND_CONNECTION,
        )
    }
}
