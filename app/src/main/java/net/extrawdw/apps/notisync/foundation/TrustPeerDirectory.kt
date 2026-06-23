package net.extrawdw.apps.notisync.foundation

import net.extrawdw.apps.notisync.channel.PeerDirectory
import net.extrawdw.apps.notisync.channel.Recipients
import net.extrawdw.apps.notisync.channel.SenderKey
import net.extrawdw.apps.notisync.data.TrustState
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
            // Unicast is own-mesh only, matching the original sendCard/sendAssetSync `&& it.ownDevice`
            // guard: a body-controlled id (e.g. a notification's sourceClientId) must never cause a send
            // to a trusted "other" (non-own) contact device.
            is Recipients.Only -> peers.filter { it.clientId == scope.id && it.ownDevice }
        }
        // Seal to each recipient's CURRENT HPKE epoch — bound into PerRecipientKey.recipientEpoch + the
        // signed EnvelopeAuth, so the recipient selects the matching (possibly retained) private keyset.
        return selected.map { RecipientKey(it.clientId, b64.decode(it.hpkePublicKeysetB64), it.currentEpoch) }
    }
}
