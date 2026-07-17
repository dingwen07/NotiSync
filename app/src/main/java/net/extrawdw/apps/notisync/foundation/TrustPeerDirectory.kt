package net.extrawdw.apps.notisync.foundation

import net.extrawdw.apps.notisync.channel.PeerDirectory
import net.extrawdw.apps.notisync.channel.Recipients
import net.extrawdw.apps.notisync.channel.SenderKey
import net.extrawdw.apps.notisync.data.TrustState
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.crypto.RecipientKey
import java.util.Base64

/**
 * [PeerDirectory] over the trust roster — the foundation's implementation of the channel's key port.
 *
 * Reads `TrustStore.activePeers` (TRUSTED ∩ current-key-epoch held) at call time and NEVER caches, so a
 * newly paired, just-revoked, or just-converged peer is reflected immediately. The base64 key fields are
 * decoded here so the channel deals only in bytes; the per-epoch anti-rollback gate lives in the trust
 * store ([TrustState.peerOperationalSpki]).
 */
class TrustPeerDirectory(private val trust: TrustState) : PeerDirectory {
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

    override fun recipients(scope: Recipients): List<RecipientKey> {
        val peers = trust.activePeers.value
        val selected = when (scope) {
            Recipients.OwnMesh -> peers.filter { it.ownDevice }
            Recipients.AllTrusted -> peers
            // Own mesh minus the devices that asked (over a FILTER) not to receive this capture, and minus any
            // platform family that can't consume it (e.g. iOS for Android group summaries).
            is Recipients.OwnMeshFiltered -> {
                peers.filter {
                    it.ownDevice &&
                        it.clientId !in scope.excluded &&
                        capabilityOrLegacyPlatformAllows(
                            it.capabilities,
                            it.platform,
                            scope,
                        )
                }
            }
            // Unicast is own-mesh only, matching the original sendCard/sendAssetSync `&& it.ownDevice`
            // guard: a body-controlled id (e.g. a notification's sourceClientId) must never cause a send
            // to a trusted "other" (non-own) contact device.
            is Recipients.Only -> peers.filter { it.clientId == scope.id && it.ownDevice }
        }
        // Seal to each recipient's CURRENT HPKE epoch — bound into PerRecipientKey.recipientEpoch + the
        // signed EnvelopeAuth, so the recipient selects the matching (possibly retained) private keyset.
        return selected.map {
            RecipientKey(
                it.clientId,
                b64.decode(it.hpkePublicKeyB64),
                it.currentEpoch
            )
        }
    }

    override fun unsealableRecipients(scope: Recipients): Set<ClientId> {
        // Trusted peers we hold no usable key-epoch for (the convergence-pull target set). OwnMesh/AllTrusted
        // repair the whole set — a notification to own-mesh should heal every keyless device, and pulling a
        // trusted "other" contact's public key-epoch is harmless. Only(id) is precise (unicast targets one id).
        val needing = trust.peersNeedingKeyEpoch(System.currentTimeMillis())
        return when (scope) {
            Recipients.OwnMesh, Recipients.AllTrusted -> needing.toSet()
            // Don't drive key-epoch repair for a peer we're intentionally NOT sending this capture to.
            is Recipients.OwnMeshFiltered -> {
                val remaining = needing.toSet() - scope.excluded
                // Skip the roster re-scan when there's nothing left to repair (the steady-state case).
                if (remaining.isEmpty()) remaining else remaining.filterTo(mutableSetOf()) { id ->
                    capabilityOrLegacyPlatformAllows(
                        trust.peerCapabilities(id),
                        trust.peerPlatform(id).orEmpty(),
                        scope,
                    )
                }
            }
            is Recipients.Only -> if (scope.id in needing) setOf(scope.id) else emptySet()
        }
    }

    private fun capabilityOrLegacyPlatformAllows(
        capabilities: List<Capability>,
        platform: String,
        scope: Recipients.OwnMeshFiltered,
    ): Boolean {
        val normalizedPlatform = platform.normalizedPlatform()
        if (normalizedPlatform in scope.excludedPlatforms.normalizedPlatforms()) return false
        // Existing capabilities predate capability routing and are safe to enforce across the whole fleet.
        // The marker is needed only before relying on newly-added declarations instead of platform fallback.
        val existingRequired = scope.requiredCapabilities.filterTo(mutableSetOf()) { it in LEGACY_CAPABILITIES }
        if (!capabilities.containsAll(existingRequired)) return false
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
